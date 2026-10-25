package com.example.multihoprelay.networking

import com.example.multihoprelay.crypto.EncryptionManager
import com.example.multihoprelay.crypto.HashManager
import com.example.multihoprelay.crypto.IdentityProvider
import com.example.multihoprelay.crypto.SealedBox
import com.example.multihoprelay.crypto.SignatureManager
import com.example.multihoprelay.data.database.dao.TransferDao
import com.example.multihoprelay.data.database.entity.TransferChunkEntity
import com.example.multihoprelay.data.database.entity.TransferEntity
import com.example.multihoprelay.data.storage.TransferFileStore
import com.example.multihoprelay.model.ChunkMeta
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import com.example.multihoprelay.model.PendingIncomingTransfer
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

sealed class TransferResult {
    data class Success(val transferId: UUID) : TransferResult()
    data class Failure(val transferId: UUID, val reason: String, val state: TransferState) : TransferResult()
}

/**
 * Orchestrates the full sender and receiver sides of a file transfer, driving the state
 * machine described in the requirements:
 *
 * DISCOVERING -> ROUTE_FOUND -> PREPARING -> ENCRYPTING -> TRANSFERRING -> (relayed by other
 * nodes, invisible to this class) -> RECEIVING -> VERIFYING -> COMPLETED, with FAILED /
 * CANCELLED / EXPIRED / NO_ROUTE as terminal failure states.
 *
 * This class never talks to BLE APIs directly - it only knows [MeshTransport] (send-to-
 * neighbor / receive-from-neighbor) and [RoutingManager] (find a next hop toward a distant
 * node). That is what lets the exact same class run against real BLE or the simulation bus.
 */
class TransferManager(
    private val transport: MeshTransport,
    private val routingManager: RoutingManager,
    private val identityProvider: IdentityProvider,
    private val transferDao: TransferDao,
    private val fileStorage: TransferFileStore,
    private val scope: CoroutineScope,
    private val logger: MeshLogger,
    private val chunkSize: Int = ChunkManager.DEFAULT_CHUNK_SIZE,
    private val autoAcceptForTesting: Boolean = false,
    private val acceptanceTimeoutSeconds: Long = 60L,
    private val context: android.content.Context? = null
) {
    private val encryptionManager = EncryptionManager()
    private val signatureManager = SignatureManager()

    // Per-transfer in-memory state not worth persisting on every mutation (raw source file
    // handle for the sender, pending continuation callbacks, etc).
    private val sendJobs = ConcurrentHashMap<UUID, Job>()
    private val pendingAccept = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()
    private val pendingConfirmations = ConcurrentHashMap<UUID, CompletableDeferred<Boolean>>()
    private val pendingChunkAcks = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, CompletableDeferred<Unit>>>()

    private val _pendingIncomingRequest = MutableStateFlow<PendingIncomingTransfer?>(null)
    val pendingIncomingRequest: StateFlow<PendingIncomingTransfer?> = _pendingIncomingRequest.asStateFlow()
    private val pendingTimeoutJobs = ConcurrentHashMap<UUID, Job>()
    private val handledTransfers = ConcurrentHashMap.newKeySet<UUID>()

    private var incomingJob: Job? = null

    fun start() {
        incomingJob = transport.incomingPackets.onEach { packet -> handleIncoming(packet) }.launchIn(scope)
        if (transport is com.example.multihoprelay.nearby.NearbyConnectionManager) {
            transport.fileTransferUpdateListener = { transferId, update, payload ->
                scope.launch {
                    handleNearbyPayloadUpdate(transferId, update, payload)
                }
            }
        }
    }

    fun stop() {
        sendJobs.values.forEach { it.cancel() }
        sendJobs.clear()
        pendingAccept.values.forEach { it.cancel() }
        pendingAccept.clear()
        pendingConfirmations.values.forEach { it.cancel() }
        pendingConfirmations.clear()
        pendingChunkAcks.values.forEach { chunkMap -> chunkMap.values.forEach { it.cancel() } }
        pendingChunkAcks.clear()
        pendingTimeoutJobs.values.forEach { it.cancel() }
        pendingTimeoutJobs.clear()
        _pendingIncomingRequest.value = null
        handledTransfers.clear()
        incomingJob?.cancel()
        incomingJob = null
        if (transport is com.example.multihoprelay.nearby.NearbyConnectionManager) {
            transport.fileTransferUpdateListener = null
        }
    }

    // ------------------------------------------------------------------------------------
    // SENDER SIDE
    // ------------------------------------------------------------------------------------

    /** Kicks off an outgoing transfer of [sourceFile] to [destination]. Returns immediately; observe Room for progress. */
    fun startSend(destination: NodeId, sourceFile: File, fileName: String): UUID {
        val transferId = UUID.randomUUID()
        val job = scope.launch {
            try {
                runSend(transferId, destination, sourceFile, fileName)
            } catch (e: Exception) {
                logger.error("TRANSFER", "Transfer $transferId crashed with uncaught error: ${e.message}")
                try {
                    updateState(transferId, TransferState.FAILED, e.message ?: "Transfer encountered an unhandled error")
                } catch (dbEx: Exception) {
                    logger.error("TRANSFER", "Failed to persist FAILED state for transfer $transferId: ${dbEx.message}")
                }
            } finally {
                pendingChunkAcks.remove(transferId)
                sendJobs.remove(transferId)
            }
        }
        sendJobs[transferId] = job
        return transferId
    }

    fun cancelTransfer(transferId: UUID) {
        sendJobs[transferId]?.cancel()
        pendingAccept.remove(transferId)?.complete(false)
        pendingConfirmations.remove(transferId)?.complete(false)
        pendingChunkAcks.remove(transferId)?.values?.forEach { it.cancel() }
        if (transport is com.example.multihoprelay.nearby.NearbyConnectionManager) {
            transport.cancelFilePayload(transferId)
        }
        scope.launch {
            val entity = transferDao.getById(transferId.toString())
            fileStorage.deleteStaging(transferId)
            if (entity != null) {
                transferDao.update(entity.copy(state = TransferState.CANCELLED, updatedAtMillis = System.currentTimeMillis()))
                sendPacketToward(
                    NodeId.fromStringOrNull(entity.peerNodeId) ?: destinationNodeIdFallback(entity.peerNodeId),
                    Packet(
                        type = PacketType.TRANSFER_CANCEL,
                        transferId = transferId,
                        sourceNodeId = identityProvider.nodeId(),
                        destinationNodeId = NodeId.fromStringOrNull(entity.peerNodeId) ?: destinationNodeIdFallback(entity.peerNodeId)
                    )
                )
            } else {
                transferDao.upsert(
                    TransferEntity(
                        transferId = transferId.toString(),
                        direction = TransferDirection.OUTGOING,
                        peerNodeId = "",
                        fileName = "",
                        fileSizeBytes = 0,
                        chunkSizeBytes = chunkSize,
                        totalChunks = 0,
                        state = TransferState.CANCELLED,
                        routeCsv = "",
                        createdAtMillis = System.currentTimeMillis(),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun destinationNodeIdFallback(raw: String): NodeId = NodeId.fromStringOrNull(raw) ?: NodeId.BROADCAST

    private suspend fun runSend(transferId: UUID, destination: NodeId, sourceFile: File, fileName: String) {
        val self = identityProvider.nodeId()
        persistNewOutgoing(transferId, destination, fileName, sourceFile.length(), sourceFile.absolutePath)

        updateState(transferId, TransferState.DISCOVERING)
        logger.info("TRANSFER", "Transfer $transferId: discovering route to $destination")
        val route = routingManager.discoverRoute(destination)
        if (route == null) {
            updateState(transferId, TransferState.NO_ROUTE, "No route to $destination")
            return
        }
        updateState(transferId, TransferState.ROUTE_FOUND, route = route.fullPath)
        logger.info("TRANSFER", "Transfer $transferId: route found ${route.fullPath.joinToString(" -> ")}")

        var recipientKeyBytes = routingManager.getKnownPublicKey(destination)
        if (recipientKeyBytes == null) {
            delay(1000L)
            recipientKeyBytes = routingManager.getKnownPublicKey(destination)
        }
        if (recipientKeyBytes == null) {
            logger.warn("TRANSFER", "Transfer $transferId: recipient public key unknown for $destination")
            updateState(transferId, TransferState.FAILED, "Recipient public key unknown for $destination")
            return
        }
        val recipientPublicKey = try {
            identityProvider.decodePublicKey(recipientKeyBytes)
        } catch (e: Exception) {
            logger.error("TRANSFER", "Transfer $transferId: failed to decode recipient public key: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Invalid recipient public key: ${e.message}")
            return
        }

        updateState(transferId, TransferState.PREPARING)
        val totalChunks = ChunkManager.totalChunks(sourceFile.length(), chunkSize)
        val fileHash = try {
            ChunkManager.fullFileSha256(sourceFile)
        } catch (e: Exception) {
            logger.error("TRANSFER", "Transfer $transferId: failed to compute file hash: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Cannot read file: ${e.message}")
            return
        }
        val chunkMetas = try {
            ChunkManager.chunkHashes(sourceFile, totalChunks, chunkSize)
                .mapIndexed { index, hash -> ChunkMeta(index, hash) }
        } catch (e: Exception) {
            logger.error("TRANSFER", "Transfer $transferId: failed to compute chunk hashes: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Cannot read file chunks: ${e.message}")
            return
        }
        transferDao.upsertChunks(chunkMetas.map {
            TransferChunkEntity(transferId.toString(), it.index, it.sha256, received = false)
        })

        updateState(transferId, TransferState.ENCRYPTING)
        val fileKey = encryptionManager.generateFileKey()
        val wrappedKey = try {
            encryptionManager.wrapFileKey(fileKey, recipientPublicKey)
        } catch (e: Exception) {
            logger.error("TRANSFER", "Transfer $transferId: failed to wrap file key: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Key wrapping failed: ${e.message}")
            return
        }
        persistFileKey(transferId, fileKey)

        val initPayload = TransferInitPayload(
            fileName = fileName,
            fileSizeBytes = sourceFile.length(),
            chunkSizeBytes = chunkSize,
            totalChunks = totalChunks,
            wrappedFileKey = wrappedKey,
            senderPublicKey = identityProvider.publicKeyEncoded(),
            signature = ByteArray(0),
            fileSha256 = fileHash
        )
        val signature = try {
            signatureManager.sign(identityProvider.privateKey(), initPayload.signedBytes())
        } catch (e: Exception) {
            logger.error("TRANSFER", "Transfer $transferId: signature generation failed: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Signing failed: ${e.message}")
            return
        }
        val signedInit = initPayload.copy(signature = signature)

        logger.info("CRYPTO", "Transfer $transferId: file key generated, wrapped for recipient, chunks ready")
        logger.info("TRANSFER", "TRANSFER_INIT_CREATED: transferId=$transferId to=$destination file=$fileName size=${sourceFile.length()}")

        val accepted = sendControlAndAwaitAccept(transferId, destination, route.nextHop, signedInit)
        if (!accepted) {
            val current = transferDao.getById(transferId.toString())
            if (current?.state == TransferState.EXPIRED) {
                logger.info("TRANSFER", "Transfer $transferId: expired by receiver timeout")
                return
            }
            if (current?.state == TransferState.REJECTED) {
                logger.info("TRANSFER", "Transfer $transferId: receiver explicitly rejected the transfer")
                return
            }
            if (current?.state == TransferState.CANCELLED) {
                logger.info("TRANSFER", "Transfer $transferId: cancelled by user")
                return
            }
            updateState(transferId, TransferState.EXPIRED, "Transfer request expired — receiver did not respond")
            logger.warn("TRANSFER", "TRANSFER_TIMEOUT: transferId=$transferId reason=Transfer request expired — receiver did not respond")
            return
        }

        updateState(transferId, TransferState.TRANSFERRING)
        val isDirectConnected = transport.neighbors.value.any { it.nodeId == destination && it.isConnected }
        if (transport is com.example.multihoprelay.nearby.NearbyConnectionManager && isDirectConnected) {
            logger.info("TRANSFER", "Transfer $transferId: transferring via Nearby Connections native file payload (direct 1-hop link)")
            val payloadId = transport.sendNearbyFilePayload(destination, transferId, sourceFile)
            if (payloadId == null) {
                updateState(transferId, TransferState.FAILED, "Failed to start Nearby file payload transfer")
                return
            }
            val confirmed = awaitTransferConfirmation(transferId, timeoutMillis = 120_000L)
            if (!confirmed) {
                val entity = transferDao.getById(transferId.toString())
                if (entity?.state == TransferState.CANCELLED) return
                updateState(transferId, TransferState.FAILED, "Receiver did not confirm file validation")
                return
            }
            updateState(transferId, TransferState.COMPLETED)
            logger.info("TRANSFER", "Transfer $transferId: COMPLETED and confirmed by receiver")
            return
        }

        logger.info("TRANSFER", "Transfer $transferId: transferring via multihop chunk relay (${chunkMetas.size} chunks, route: ${route.fullPath.joinToString(" -> ")})")
        val chunkAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
        pendingChunkAcks[transferId] = chunkAcks

        // Flow-controlled chunk-by-chunk sending with bounded retries
        for (meta in chunkMetas) {
            val chunkIndex = meta.index
            var acked = false
            var attempts = 0
            val maxAttempts = 3

            while (!acked && attempts < maxAttempts) {
                val current = transferDao.getById(transferId.toString())
                if (current == null || current.state == TransferState.CANCELLED) {
                    logger.info("TRANSFER", "Transfer $transferId cancelled during chunk $chunkIndex")
                    return
                }

                attempts++
                val ackDeferred = CompletableDeferred<Unit>()
                chunkAcks[chunkIndex] = ackDeferred

                val sent = sendOneChunk(transferId, destination, sourceFile, chunkIndex, totalChunks, fileKey)
                if (!sent) {
                    logger.warn("TRANSFER", "Failed to send chunk $chunkIndex (attempt $attempts/$maxAttempts)")
                    chunkAcks.remove(chunkIndex)
                    delay(300L)
                    continue
                }

                acked = withTimeoutOrNull(8_000L) {
                    ackDeferred.await()
                    true
                } ?: false

                chunkAcks.remove(chunkIndex)
                if (!acked) {
                    logger.warn("TRANSFER", "ACK timeout for chunk $chunkIndex (attempt $attempts/$maxAttempts)")
                }
            }

            if (!acked) {
                updateState(transferId, TransferState.FAILED, "Failed to deliver chunk $chunkIndex after $maxAttempts attempts")
                return
            }
        }

        sendPacketToward(
            destination,
            Packet(
                type = PacketType.TRANSFER_COMPLETE,
                transferId = transferId,
                sourceNodeId = self,
                destinationNodeId = destination
            )
        )
        logger.info("TRANSFER", "Transfer $transferId: all $totalChunks chunks confirmed. TRANSFER_COMPLETE sent, awaiting receiver validation")

        val confirmed = awaitTransferConfirmation(transferId, timeoutMillis = 15_000L)
        if (!confirmed) {
            val entity = transferDao.getById(transferId.toString())
            if (entity?.state == TransferState.CANCELLED) return
            updateState(transferId, TransferState.FAILED, "Receiver did not confirm file validation")
            return
        }

        updateState(transferId, TransferState.COMPLETED)
        logger.info("TRANSFER", "Transfer $transferId: COMPLETED and confirmed by receiver")
    }

    private suspend fun sendOneChunk(
        transferId: UUID, destination: NodeId, sourceFile: File, index: Int, totalChunks: Int, fileKey: SecretKey
    ): Boolean {
        val plaintext = ChunkManager.readChunk(sourceFile, index, chunkSize)
        val aad = chunkAad(transferId, index)
        val sealed = encryptionManager.encrypt(fileKey, plaintext, aad)
        val payload = ChunkDataPayload(index, totalChunks, sealed.nonce, sealed.ciphertext).encode()

        val sent = sendPacketToward(
            destination,
            Packet(
                type = PacketType.CHUNK_DATA,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = destination,
                sequenceNumber = index.toLong(),
                payload = payload
            )
        )
        if (sent) {
            logger.debug("TRANSFER", "Transfer $transferId: chunk $index/${totalChunks - 1} sent (${plaintext.size} bytes)")
        }
        return sent
    }

    private suspend fun awaitTransferConfirmation(
        transferId: UUID, timeoutMillis: Long = 15_000L
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirmations[transferId] = deferred
        val result = withTimeoutOrNull(timeoutMillis) {
            deferred.await()
        }
        pendingConfirmations.remove(transferId)
        return result ?: false
    }

    private suspend fun sendControlAndAwaitAccept(
        transferId: UUID, destination: NodeId, firstHop: NodeId, initPayload: TransferInitPayload
    ): Boolean {
        val packet = Packet(
            type = PacketType.TRANSFER_INIT,
            transferId = transferId,
            sourceNodeId = identityProvider.nodeId(),
            destinationNodeId = destination,
            payload = initPayload.encode()
        )
        val deferred = CompletableDeferred<Boolean>()
        pendingAccept[transferId] = deferred
        updateState(transferId, TransferState.WAITING_FOR_APPROVAL)
        logger.info("TRANSFER", "TRANSFER_INIT_QUEUED: transferId=$transferId to=$destination")
        val sent = transport.sendToNeighbor(firstHop, packet)
        if (!sent) {
            pendingAccept.remove(transferId)
            logger.warn("TRANSFER", "TRANSFER_FAILED: transferId=$transferId reason=Failed to send TRANSFER_INIT to nextHop $firstHop")
            return false
        }
        val result = withTimeoutOrNull(acceptanceTimeoutSeconds * 1000L) {
            deferred.await()
        }
        pendingAccept.remove(transferId)
        if (result == null) {
            logger.warn("TRANSFER", "TRANSFER_TIMEOUT: transferId=$transferId reason=Sender wait timeout expired (${acceptanceTimeoutSeconds}s)")
        }
        return result ?: false
    }

    // ------------------------------------------------------------------------------------
    // RECEIVER SIDE
    // ------------------------------------------------------------------------------------

    private suspend fun handleIncoming(packet: Packet) {
        try {
            if (packet.destinationNodeId != identityProvider.nodeId()) return
            when (packet.type) {
                PacketType.TRANSFER_INIT -> {
                    logger.info("TRANSFER", "TRANSFER_INIT_RECEIVED: transferId=${packet.transferId} from=${packet.sourceNodeId}")
                    onTransferInit(packet)
                }
                PacketType.TRANSFER_ACCEPT -> {
                    logger.info("TRANSFER", "TRANSFER_RESPONSE_RECEIVED: transferId=${packet.transferId} response=ACCEPT from=${packet.sourceNodeId}")
                    pendingAccept.remove(packet.transferId)?.complete(true)
                }
                PacketType.TRANSFER_REJECT -> {
                    val isExpired = packet.payload.isNotEmpty() && String(packet.payload, Charsets.UTF_8) == "EXPIRED"
                    if (isExpired) {
                        logger.info("TRANSFER", "TRANSFER_RESPONSE_RECEIVED: transferId=${packet.transferId} response=EXPIRED from=${packet.sourceNodeId}")
                        logger.warn("TRANSFER", "TRANSFER_TIMEOUT: transferId=${packet.transferId} reason=Transfer request expired — receiver did not respond")
                        packet.transferId?.let { tid ->
                            scope.launch {
                                updateState(tid, TransferState.EXPIRED, "Transfer request expired — receiver did not respond")
                            }
                        }
                    } else {
                        logger.info("TRANSFER", "TRANSFER_RESPONSE_RECEIVED: transferId=${packet.transferId} response=REJECT from=${packet.sourceNodeId}")
                        packet.transferId?.let { tid ->
                            scope.launch {
                                updateState(tid, TransferState.REJECTED, "Receiver rejected the transfer request")
                            }
                        }
                    }
                    pendingAccept.remove(packet.transferId)?.complete(false)
                    pendingConfirmations.remove(packet.transferId)?.complete(false)
                }
                PacketType.CHUNK_DATA -> onChunkData(packet)
                PacketType.CHUNK_ACK -> onChunkAck(packet)
                PacketType.TRANSFER_COMPLETE -> onTransferComplete(packet)
                PacketType.TRANSFER_CONFIRM -> {
                    pendingConfirmations.remove(packet.transferId)?.complete(true)
                }
                PacketType.TRANSFER_CANCEL -> onTransferCancel(packet)
                PacketType.CHUNK_RESEND_REQUEST -> onResendRequest(packet)
                else -> Unit
            }
        } catch (e: Exception) {
            logger.error("TRANSFER", "Error handling packet ${packet.type} for transfer ${packet.transferId}: ${e.message}")
        }
    }

    private suspend fun onTransferInit(packet: Packet) {
        val transferId = packet.transferId ?: return
        val init = try {
            TransferInitPayload.decode(packet.payload)
        } catch (e: Exception) {
            logger.warn("TRANSFER", "Malformed TRANSFER_INIT dropped: ${e.message}")
            return
        }
        logger.info("TRANSFER", "TRANSFER_INIT_DECODED: transferId=$transferId file=${init.fileName} size=${init.fileSizeBytes} chunks=${init.totalChunks}")

        // Avoid re-processing if this transfer is already registered or handled
        if (handledTransfers.contains(transferId)) {
            logger.debug("TRANSFER", "Transfer $transferId already handled, ignoring duplicate TRANSFER_INIT")
            return
        }
        val existing = transferDao.getById(transferId.toString())
        if (existing != null) {
            logger.debug("TRANSFER", "Transfer $transferId already known in state ${existing.state}, ignoring duplicate TRANSFER_INIT")
            return
        }

        // Self-certifying identity check: the claimed sender's NodeId must match the hash of
        // the public key actually embedded in this message. A relay cannot forge this without
        // also forging a valid signature below, which it cannot do without the private key.
        val claimedSenderKey = try {
            identityProvider.decodePublicKey(init.senderPublicKey)
        } catch (e: Exception) {
            logger.error("TRANSFER", "TRANSFER_INIT rejected: invalid public key: ${e.message}")
            rejectTransfer(transferId, packet.sourceNodeId)
            return
        }
        val derivedNodeId = NodeId.fromPublicKeyBytes(claimedSenderKey.encoded)
        if (derivedNodeId != packet.sourceNodeId) {
            logger.error("TRANSFER", "TRANSFER_INIT rejected: public key does not match claimed source NodeId")
            return
        }
        if (!signatureManager.verify(claimedSenderKey, init.signedBytes(), init.signature)) {
            logger.error("TRANSFER", "TRANSFER_INIT rejected: signature verification failed (possible tampering)")
            rejectTransfer(transferId, packet.sourceNodeId)
            return
        }
        if (init.totalChunks <= 0 || init.fileSizeBytes < 0 || init.chunkSizeBytes <= 0) {
            logger.error("TRANSFER", "TRANSFER_INIT rejected: invalid metadata")
            rejectTransfer(transferId, packet.sourceNodeId)
            return
        }

        logger.info("TRANSFER", "Transfer $transferId: TRANSFER_INIT from ${packet.sourceNodeId} verified (signature OK)")
        logger.info("TRANSFER", "TRANSFER_INIT_VALIDATED: transferId=$transferId valid=true")

        val entity = TransferEntity(
            transferId = transferId.toString(),
            direction = TransferDirection.INCOMING,
            peerNodeId = packet.sourceNodeId.value,
            fileName = init.fileName,
            fileSizeBytes = init.fileSizeBytes,
            chunkSizeBytes = init.chunkSizeBytes,
            totalChunks = init.totalChunks,
            state = TransferState.PENDING_APPROVAL,
            routeCsv = "",
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            localFileUri = null,
            fileSha256 = init.fileSha256,
            wrappedFileKeyForReceiver = init.wrappedFileKey,
            senderPublicKeyForReceiver = init.senderPublicKey,
            transferInitSignature = init.signature
        )
        logger.info("TRANSFER", "INCOMING_REQUEST_CREATED: transferId=$transferId file=${init.fileName}")
        transferDao.upsert(entity)

        val timeoutSecs = acceptanceTimeoutSeconds.toInt()
        val pendingTransfer = PendingIncomingTransfer(
            transferId = transferId,
            peerNodeId = packet.sourceNodeId,
            fileName = init.fileName,
            fileSizeBytes = init.fileSizeBytes,
            totalChunks = init.totalChunks,
            createdAtMillis = System.currentTimeMillis(),
            timeoutSeconds = timeoutSecs,
            remainingSeconds = timeoutSecs
        )
        _pendingIncomingRequest.value = pendingTransfer
        logger.info("TRANSFER", "INCOMING_REQUEST_PUBLISHED: transferId=$transferId state=${TransferState.PENDING_APPROVAL}")

        if (autoAcceptForTesting) {
            scope.launch { acceptIncomingTransfer(transferId) }
        } else {
            logger.info("TRANSFER", "Transfer $transferId: waiting for user approval")
            val timeoutJob = scope.launch {
                var remaining = timeoutSecs
                while (remaining > 0) {
                    delay(1000L)
                    remaining--
                    _pendingIncomingRequest.value?.let { cur ->
                        if (cur.transferId == transferId) {
                            _pendingIncomingRequest.value = cur.copy(remainingSeconds = remaining)
                        }
                    }
                }
                expireIncomingTransfer(transferId, packet.sourceNodeId)
            }
            pendingTimeoutJobs[transferId] = timeoutJob
        }
    }

    /**
     * Called when the acceptance countdown expires on the receiver.
     */
    suspend fun expireIncomingTransfer(transferId: UUID, sender: NodeId) {
        if (!handledTransfers.add(transferId)) return
        pendingTimeoutJobs.remove(transferId)?.cancel()
        if (_pendingIncomingRequest.value?.transferId == transferId) {
            _pendingIncomingRequest.value = null
        }
        logger.warn("TRANSFER", "TRANSFER_TIMEOUT: transferId=$transferId reason=Receiver acceptance timeout expired")
        val entity = transferDao.getById(transferId.toString())
        if (entity != null && entity.state == TransferState.PENDING_APPROVAL) {
            transferDao.update(
                entity.copy(
                    state = TransferState.EXPIRED,
                    errorMessage = "Transfer request expired — receiver did not respond",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
        fileStorage.deleteStaging(transferId)
        val sent = sendPacketToward(
            sender,
            Packet(
                type = PacketType.TRANSFER_REJECT,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = sender,
                payload = "EXPIRED".toByteArray(Charsets.UTF_8)
            )
        )
        if (sent) {
            logger.info("TRANSFER", "TRANSFER_RESPONSE_SENT: transferId=$transferId response=EXPIRED to=$sender")
        }
    }

    /**
     * User tapped Allow on the incoming file dialog or card.
     * Transitions state to RECEIVING, allocates staging file, and transmits TRANSFER_ACCEPT to sender.
     */
    suspend fun acceptIncomingTransfer(transferId: UUID) {
        if (!handledTransfers.add(transferId)) {
            logger.warn("TRANSFER", "acceptIncomingTransfer: Transfer $transferId was already handled")
            return
        }
        pendingTimeoutJobs.remove(transferId)?.cancel()
        if (_pendingIncomingRequest.value?.transferId == transferId) {
            _pendingIncomingRequest.value = null
        }
        val entity = transferDao.getById(transferId.toString()) ?: run {
            logger.warn("TRANSFER", "acceptIncomingTransfer: Unknown transfer $transferId")
            return
        }
        if (entity.direction != TransferDirection.INCOMING || entity.state != TransferState.PENDING_APPROVAL) {
            logger.warn("TRANSFER", "acceptIncomingTransfer: Transfer $transferId not in PENDING_APPROVAL (current: ${entity.state})")
            return
        }
        val peerNode = NodeId.fromStringOrNull(entity.peerNodeId) ?: destinationNodeIdFallback(entity.peerNodeId)

        val isReachable = transport.neighbors.value.any { it.nodeId == peerNode && it.status == com.example.multihoprelay.model.NeighborStatus.CONNECTED } ||
                          routingManager.getRoute(peerNode) != null
        if (!isReachable) {
            logger.warn("TRANSFER", "acceptIncomingTransfer: Sender $peerNode is no longer reachable")
            updateState(transferId, TransferState.FAILED, "Sender is no longer reachable")
            logger.warn("TRANSFER", "TRANSFER_FAILED: transferId=$transferId reason=Sender is no longer reachable")
            return
        }

        logger.info("TRANSFER", "TRANSFER_ACCEPTED: transferId=$transferId peer=$peerNode")

        val staging = fileStorage.stagingFileFor(transferId)
        try {
            ChunkManager.preallocate(staging, entity.fileSizeBytes)
        } catch (e: Exception) {
            logger.error("TRANSFER", "Failed to preallocate staging file for $transferId: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Cannot allocate storage space: ${e.message}")
            logger.error("TRANSFER", "TRANSFER_FAILED: transferId=$transferId reason=Cannot allocate storage space: ${e.message}")
            rejectTransfer(transferId, peerNode)
            return
        }

        transferDao.update(
            entity.copy(
                state = TransferState.RECEIVING,
                localFileUri = staging.absolutePath,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        logger.info("TRANSFER", "Transfer $transferId: accepted by user, transitioning to RECEIVING")

        val sent = sendPacketToward(
            peerNode,
            Packet(
                type = PacketType.TRANSFER_ACCEPT,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = peerNode
            )
        )
        if (!sent) {
            logger.error("TRANSFER", "Failed to send TRANSFER_ACCEPT for transfer $transferId to $peerNode")
            updateState(transferId, TransferState.FAILED, "Cannot send acceptance to sender (link unavailable)")
            logger.error("TRANSFER", "TRANSFER_FAILED: transferId=$transferId reason=Cannot send acceptance to sender")
        } else {
            logger.info("TRANSFER", "TRANSFER_RESPONSE_SENT: transferId=$transferId response=ACCEPT to=$peerNode")
        }
    }

    /**
     * User tapped Reject on the incoming file dialog or card.
     * Transitions state to REJECTED, cleans up any resources, and transmits TRANSFER_REJECT to sender.
     */
    suspend fun rejectIncomingTransfer(transferId: UUID) {
        if (!handledTransfers.add(transferId)) {
            logger.warn("TRANSFER", "rejectIncomingTransfer: Transfer $transferId was already handled")
            return
        }
        pendingTimeoutJobs.remove(transferId)?.cancel()
        if (_pendingIncomingRequest.value?.transferId == transferId) {
            _pendingIncomingRequest.value = null
        }
        val entity = transferDao.getById(transferId.toString()) ?: run {
            logger.warn("TRANSFER", "rejectIncomingTransfer: Unknown transfer $transferId")
            return
        }
        if (entity.direction != TransferDirection.INCOMING || entity.state != TransferState.PENDING_APPROVAL) {
            logger.warn("TRANSFER", "rejectIncomingTransfer: Transfer $transferId not in PENDING_APPROVAL (current: ${entity.state})")
            return
        }
        val peerNode = NodeId.fromStringOrNull(entity.peerNodeId) ?: destinationNodeIdFallback(entity.peerNodeId)
        fileStorage.deleteStaging(transferId)
        transferDao.update(
            entity.copy(
                state = TransferState.REJECTED,
                errorMessage = "Transfer rejected by user",
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        logger.info("TRANSFER", "TRANSFER_REJECTED: transferId=$transferId peer=$peerNode")
        val sent = sendPacketToward(
            peerNode,
            Packet(
                type = PacketType.TRANSFER_REJECT,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = peerNode
            )
        )
        if (sent) {
            logger.info("TRANSFER", "TRANSFER_RESPONSE_SENT: transferId=$transferId response=REJECT to=$peerNode")
        }
    }

    private suspend fun rejectTransfer(transferId: UUID, sender: NodeId) {
        logger.info("TRANSFER", "TRANSFER_REJECTED: transferId=$transferId peer=$sender")
        val sent = sendPacketToward(
            sender,
            Packet(
                type = PacketType.TRANSFER_REJECT,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = sender
            )
        )
        if (sent) {
            logger.info("TRANSFER", "TRANSFER_RESPONSE_SENT: transferId=$transferId response=REJECT to=$sender")
        }
    }

    private suspend fun onChunkData(packet: Packet) {
        val transferId = packet.transferId ?: return
        val entity = transferDao.getById(transferId.toString()) ?: run {
            logger.warn("TRANSFER", "CHUNK_DATA for unknown transfer $transferId dropped")
            return
        }
        if (entity.state != TransferState.RECEIVING) return

        val chunkData = try {
            ChunkDataPayload.decode(packet.payload)
        } catch (e: Exception) {
            logger.warn("TRANSFER", "Malformed CHUNK_DATA dropped: ${e.message}")
            return
        }

        val existingChunk = transferDao.getChunk(transferId.toString(), chunkData.chunkIndex)
        if (existingChunk?.received == true) {
            logger.debug("TRANSFER", "Duplicate chunk ${chunkData.chunkIndex} for $transferId ignored")
            sendChunkAck(transferId, packet.sourceNodeId, chunkData.chunkIndex)
            return
        }

        val fileKey = try {
            unwrapReceiverFileKey(entity)
        } catch (e: Exception) {
            logger.error("TRANSFER", "Transfer $transferId: failed to unwrap file key: ${e.message}")
            updateState(transferId, TransferState.FAILED, "Key unwrap failure")
            return
        }

        val plaintext = try {
            encryptionManager.decrypt(
                fileKey,
                SealedBox(chunkData.nonce, chunkData.ciphertext),
                chunkAad(transferId, chunkData.chunkIndex)
            )
        } catch (e: Exception) {
            // AEADBadTagException or similar - reject outright, never use partial output.
            logger.error("TRANSFER", "Transfer $transferId: chunk ${chunkData.chunkIndex} failed authentication - discarded")
            return
        }

        val staging = File(entity.localFileUri ?: return)
        ChunkManager.writeChunk(staging, chunkData.chunkIndex, plaintext, entity.chunkSizeBytes)
        transferDao.upsertChunk(
            TransferChunkEntity(
                transferId.toString(), chunkData.chunkIndex, HashManager.sha256(plaintext), received = true
            )
        )
        logger.debug("TRANSFER", "Transfer $transferId: chunk ${chunkData.chunkIndex} verified and written")

        val received = transferDao.countReceivedChunks(transferId.toString())
        transferDao.update(
            entity.copy(state = TransferState.RECEIVING, chunksDone = received, updatedAtMillis = System.currentTimeMillis())
        )
        sendChunkAck(transferId, packet.sourceNodeId, chunkData.chunkIndex)

        if (received >= entity.totalChunks) {
            finalizeReceivedTransfer(transferId)
        }
    }

    private suspend fun sendChunkAck(transferId: UUID, sender: NodeId, chunkIndex: Int) {
        sendPacketToward(
            sender,
            Packet(
                type = PacketType.CHUNK_ACK,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = sender,
                payload = ChunkAckPayload(chunkIndex).encode()
            )
        )
    }

    private suspend fun onChunkAck(packet: Packet) {
        val transferId = packet.transferId ?: return
        val ack = try { ChunkAckPayload.decode(packet.payload) } catch (e: Exception) { return }
        pendingChunkAcks[transferId]?.get(ack.chunkIndex)?.complete(Unit)
        val existing = transferDao.getChunk(transferId.toString(), ack.chunkIndex)
        if (existing != null) {
            transferDao.upsertChunk(existing.copy(received = true, acknowledged = true))
        }
        val entity = transferDao.getById(transferId.toString())
        if (entity != null) {
            val doneCount = transferDao.countReceivedChunks(transferId.toString())
            transferDao.update(entity.copy(chunksDone = doneCount, updatedAtMillis = System.currentTimeMillis()))
        }
        logger.debug("TRANSFER", "Transfer $transferId: chunk ${ack.chunkIndex} acknowledged")
    }

    private suspend fun onTransferComplete(packet: Packet) {
        val transferId = packet.transferId ?: return
        val entity = transferDao.getById(transferId.toString()) ?: return
        val trulyMissing = (0 until entity.totalChunks).filter { idx ->
            transferDao.getChunk(transferId.toString(), idx)?.received != true
        }
        if (trulyMissing.isNotEmpty()) {
            logger.warn("TRANSFER", "Transfer $transferId: TRANSFER_COMPLETE received but ${trulyMissing.size} chunk(s) missing - requesting resend")
            sendPacketToward(
                packet.sourceNodeId,
                Packet(
                    type = PacketType.CHUNK_RESEND_REQUEST,
                    transferId = transferId,
                    sourceNodeId = identityProvider.nodeId(),
                    destinationNodeId = packet.sourceNodeId,
                    payload = ChunkResendRequestPayload(trulyMissing).encode()
                )
            )
            return
        }
        finalizeReceivedTransfer(transferId)
    }

    private suspend fun finalizeReceivedTransfer(transferId: UUID) {
        val entity = transferDao.getById(transferId.toString()) ?: return
        if (entity.state.isTerminal) return
        updateState(transferId, TransferState.VERIFYING)

        val staging = entity.localFileUri?.let { File(it) } ?: fileStorage.stagingFileFor(transferId)
        if (!staging.exists()) {
            logger.error("TRANSFER", "Transfer $transferId: staging file does not exist at ${staging.absolutePath}")
            updateState(transferId, TransferState.FAILED, "Staging file missing")
            return
        }

        val actualHash = ChunkManager.fullFileSha256(staging)
        val expectedHash = entity.fileSha256

        if (expectedHash == null || !HashManager.constantTimeEquals(actualHash, expectedHash)) {
            logger.error("TRANSFER", "Transfer $transferId: FINAL HASH MISMATCH - file rejected")
            updateState(transferId, TransferState.FAILED, "File integrity verification failed")
            fileStorage.deleteStaging(transferId)
            sendPacketToward(
                NodeId(entity.peerNodeId),
                Packet(
                    type = PacketType.TRANSFER_REJECT,
                    transferId = transferId,
                    sourceNodeId = identityProvider.nodeId(),
                    destinationNodeId = NodeId(entity.peerNodeId)
                )
            )
            return
        }

        val finalFile = fileStorage.finalFileFor(transferId, entity.fileName)
        fileStorage.moveToFinal(staging, finalFile)

        if (!finalFile.exists()) {
            logger.error("TRANSFER", "Transfer $transferId: destination file does not exist after moveToFinal")
            updateState(transferId, TransferState.FAILED, "Failed to save final file")
            return
        }

        transferDao.update(
            entity.copy(
                state = TransferState.COMPLETED,
                chunksDone = entity.totalChunks,
                outputFilePath = finalFile.absolutePath,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        logger.info("TRANSFER", "Transfer $transferId: COMPLETED, file verified and saved to ${finalFile.absolutePath}")

        sendPacketToward(
            NodeId(entity.peerNodeId),
            Packet(
                type = PacketType.TRANSFER_CONFIRM,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = NodeId(entity.peerNodeId)
            )
        )
    }

    private suspend fun onTransferCancel(packet: Packet) {
        val transferId = packet.transferId ?: return
        val current = transferDao.getById(transferId.toString())
        if (current?.state?.isTerminal == true) {
            logger.debug("TRANSFER", "Transfer $transferId already in terminal state ${current.state}, ignoring TRANSFER_CANCEL")
            return
        }
        fileStorage.deleteStaging(transferId)
        updateState(transferId, TransferState.CANCELLED, "Cancelled by peer")
    }

    private suspend fun onResendRequest(packet: Packet) {
        val transferId = packet.transferId ?: return
        val entity = transferDao.getById(transferId.toString()) ?: return
        if (entity.direction != TransferDirection.OUTGOING) return
        val request = try { ChunkResendRequestPayload.decode(packet.payload) } catch (e: Exception) { return }
        val sourceFile = entity.localFileUri?.let { File(it) } ?: return
        val fileKey = entity.rawFileKeyForSender?.let { SecretKeySpec(it, "AES") } ?: return
        logger.info("TRANSFER", "Transfer $transferId: resend requested for ${request.missingIndices.size} chunk(s)")
        request.missingIndices.forEach { index ->
            sendOneChunk(transferId, packet.sourceNodeId, sourceFile, index, entity.totalChunks, fileKey)
        }
        sendPacketToward(
            packet.sourceNodeId,
            Packet(
                type = PacketType.TRANSFER_COMPLETE,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = packet.sourceNodeId
            )
        )
    }

    // ------------------------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------------------------

    /** Sends a packet to [destination], resolving the next hop via direct-neighbor check or the route table. */
    private suspend fun sendPacketToward(destination: NodeId, packet: Packet): Boolean {
        val direct = transport.neighbors.value.any { it.nodeId == destination && it.isConnected }
        val nextHop = if (direct) destination else (routingManager.getRoute(destination)?.nextHop ?: destination)
        val sent = transport.sendToNeighbor(nextHop, packet)
        if (!sent) {
            logger.warn("TRANSFER", "Failed to send packet ${packet.type} to nextHop $nextHop for destination $destination")
        }
        return sent
    }

    private fun chunkAad(transferId: UUID, chunkIndex: Int): ByteArray {
        val buffer = ByteBuffer.allocate(16 + 4)
        buffer.putLong(transferId.mostSignificantBits)
        buffer.putLong(transferId.leastSignificantBits)
        buffer.putInt(chunkIndex)
        return buffer.array()
    }

    private suspend fun unwrapReceiverFileKey(entity: TransferEntity): SecretKey {
        val wrapped = entity.wrappedFileKeyForReceiver ?: throw IllegalStateException("No wrapped key stored")
        return encryptionManager.unwrapFileKey(wrapped, identityProvider.privateKey())
    }

    private suspend fun persistNewOutgoing(
        transferId: UUID, destination: NodeId, fileName: String, fileSize: Long, sourceFilePath: String
    ) {
        transferDao.upsert(
            TransferEntity(
                transferId = transferId.toString(),
                direction = TransferDirection.OUTGOING,
                peerNodeId = destination.value,
                fileName = fileName,
                fileSizeBytes = fileSize,
                chunkSizeBytes = chunkSize,
                totalChunks = ChunkManager.totalChunks(fileSize, chunkSize),
                state = TransferState.DISCOVERING,
                routeCsv = "",
                createdAtMillis = System.currentTimeMillis(),
                updatedAtMillis = System.currentTimeMillis(),
                localFileUri = sourceFilePath
            )
        )
    }

    private suspend fun persistFileKey(transferId: UUID, fileKey: SecretKey) {
        transferDao.getById(transferId.toString())?.let {
            transferDao.update(it.copy(rawFileKeyForSender = fileKey.encoded))
        }
    }

    private suspend fun updateState(
        transferId: UUID, state: TransferState, errorMessage: String? = null, route: List<NodeId>? = null
    ) {
        val entity = transferDao.getById(transferId.toString()) ?: return
        transferDao.update(
            entity.copy(
                state = state,
                errorMessage = errorMessage ?: entity.errorMessage,
                routeCsv = route?.joinToString(",") { it.value } ?: entity.routeCsv,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        if (state.isFailure) {
            logger.warn("TRANSFER", "Transfer $transferId -> $state${errorMessage?.let { ": $it" } ?: ""}")
        }
    }

    private suspend fun handleNearbyPayloadUpdate(
        transferId: UUID,
        update: com.google.android.gms.nearby.connection.PayloadTransferUpdate,
        payload: com.google.android.gms.nearby.connection.Payload?
    ) {
        val entity = transferDao.getById(transferId.toString()) ?: return
        when (update.status) {
            com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status.IN_PROGRESS -> {
                if (update.totalBytes > 0 && entity.totalChunks > 0) {
                    val done = ((update.bytesTransferred.toDouble() / update.totalBytes) * entity.totalChunks).toInt().coerceIn(0, entity.totalChunks)
                    if (done != entity.chunksDone) {
                        transferDao.update(entity.copy(chunksDone = done, updatedAtMillis = System.currentTimeMillis()))
                    }
                }
            }
            com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status.SUCCESS -> {
                logger.info("TRANSFER", "Nearby payload transfer SUCCESS for transfer $transferId")
                if (entity.direction == TransferDirection.INCOMING) {
                    if (entity.state == TransferState.COMPLETED || entity.state == TransferState.VERIFYING) {
                        logger.debug("TRANSFER", "Transfer $transferId already in ${entity.state}, ignoring duplicate SUCCESS update")
                        return
                    }

                    if (payload != null) {
                        val staging = fileStorage.stagingFileFor(transferId)
                        staging.parentFile?.mkdirs()

                        val inputStream: java.io.InputStream? = try {
                            (transport as? com.example.multihoprelay.nearby.NearbyConnectionManager)?.openPayloadInputStream(payload)
                                ?: payload.asFile()?.asUri()?.let { uri ->
                                    context?.contentResolver?.openInputStream(uri)
                                }
                                ?: payload.asFile()?.asParcelFileDescriptor()?.let { pfd ->
                                    java.io.FileInputStream(pfd.fileDescriptor)
                                }
                                ?: payload.asFile()?.let { pFile ->
                                    try {
                                        @Suppress("DEPRECATION")
                                        pFile.asJavaFile()?.takeIf { it.exists() && it.canRead() }?.let { java.io.FileInputStream(it) }
                                    } catch (_: Exception) { null }
                                }
                        } catch (e: Exception) {
                            logger.error("TRANSFER", "Failed to obtain stream for received payload $transferId: ${e.message}")
                            null
                        }

                        if (inputStream == null) {
                            logger.error("TRANSFER", "Could not open input stream from received payload for transfer $transferId")
                            updateState(transferId, TransferState.FAILED, "Cannot open received file stream from Nearby Connections")
                            return
                        }

                        try {
                            inputStream.use { input ->
                                java.io.FileOutputStream(staging).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("TRANSFER", "Failed to copy received file payload for $transferId: ${e.message}")
                            updateState(transferId, TransferState.FAILED, "Failed to copy received file: ${e.message}")
                            return
                        }

                        if (!staging.exists() || (entity.fileSizeBytes > 0L && staging.length() == 0L)) {
                            logger.error("TRANSFER", "Copied staging file for $transferId is empty or missing")
                            updateState(transferId, TransferState.FAILED, "Received file is empty or corrupted")
                            return
                        }

                        logger.info("TRANSFER", "Staging file written successfully for transfer $transferId: size=${staging.length()} bytes")
                        (transport as? com.example.multihoprelay.nearby.NearbyConnectionManager)?.incomingFilePayloads?.remove(update.payloadId)
                        (transport as? com.example.multihoprelay.nearby.NearbyConnectionManager)?.payloadToTransfer?.remove(update.payloadId)
                        finalizeReceivedTransfer(transferId)
                    }
                } else {
                    transferDao.update(entity.copy(chunksDone = entity.totalChunks, updatedAtMillis = System.currentTimeMillis()))
                }
            }
            com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status.FAILURE -> {
                logger.error("TRANSFER", "Nearby payload transfer FAILED for transfer $transferId")
                fileStorage.deleteStaging(transferId)
                updateState(transferId, TransferState.FAILED, "Transfer failed over Nearby Connections")
            }
            com.google.android.gms.nearby.connection.PayloadTransferUpdate.Status.CANCELED -> {
                logger.info("TRANSFER", "Nearby payload transfer CANCELED for transfer $transferId")
                fileStorage.deleteStaging(transferId)
                updateState(transferId, TransferState.CANCELLED, "Transfer cancelled")
            }
        }
    }

    /** Called when a neighbor disconnects; invalidates pending transfers associated with that node. */
    fun onNeighborLost(neighbor: NodeId) {
        scope.launch {
            val all = transferDao.getAll()
            all.forEach { entity ->
                if (entity.peerNodeId == neighbor.value) {
                    val tid = try { UUID.fromString(entity.transferId) } catch (_: Exception) { null }
                    if (entity.state == TransferState.PENDING_APPROVAL) {
                        logger.warn("TRANSFER", "Transfer ${entity.transferId} invalidated: sender $neighbor disconnected")
                        if (tid != null) {
                            handledTransfers.add(tid)
                            pendingTimeoutJobs.remove(tid)?.cancel()
                            if (_pendingIncomingRequest.value?.transferId == tid) {
                                _pendingIncomingRequest.value = null
                            }
                            logger.warn("TRANSFER", "TRANSFER_FAILED: transferId=$tid reason=Sender disconnected")
                        }
                        transferDao.update(
                            entity.copy(
                                state = TransferState.FAILED,
                                errorMessage = "Sender disconnected",
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        )
                    } else if (entity.state == TransferState.WAITING_FOR_APPROVAL) {
                        logger.warn("TRANSFER", "Transfer ${entity.transferId} failed: receiver $neighbor disconnected")
                        if (tid != null) {
                            pendingAccept.remove(tid)?.complete(false)
                            logger.warn("TRANSFER", "TRANSFER_FAILED: transferId=$tid reason=Receiver disconnected")
                        }
                        transferDao.update(
                            entity.copy(
                                state = TransferState.FAILED,
                                errorMessage = "Receiver disconnected",
                                updatedAtMillis = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }
}
