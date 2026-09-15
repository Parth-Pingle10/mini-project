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
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val chunkSize: Int = ChunkManager.DEFAULT_CHUNK_SIZE
) {
    private val encryptionManager = EncryptionManager()
    private val signatureManager = SignatureManager()

    // Per-transfer in-memory state not worth persisting on every mutation (raw source file
    // handle for the sender, pending continuation callbacks, etc).
    private val sendJobs = ConcurrentHashMap<UUID, Job>()
    private val pendingAccept = ConcurrentHashMap<UUID, (Boolean) -> Unit>()
    private val pendingChunkAcks = ConcurrentHashMap<UUID, MutableSet<Int>>()

    private var incomingJob: Job? = null

    fun start() {
        incomingJob = transport.incomingPackets.onEach { packet -> handleIncoming(packet) }.launchIn(scope)
    }

    fun stop() {
        sendJobs.values.forEach { it.cancel() }
        sendJobs.clear()
        incomingJob?.cancel()
        incomingJob = null
    }

    // ------------------------------------------------------------------------------------
    // SENDER SIDE
    // ------------------------------------------------------------------------------------

    /** Kicks off an outgoing transfer of [sourceFile] to [destination]. Returns immediately; observe Room for progress. */
    fun startSend(destination: NodeId, sourceFile: File, fileName: String): UUID {
        val transferId = UUID.randomUUID()
        sendJobs[transferId] = scope.launch {
            runSend(transferId, destination, sourceFile, fileName)
        }
        return transferId
    }

    fun cancelTransfer(transferId: UUID) {
        sendJobs[transferId]?.cancel()
        scope.launch {
            transferDao.getById(transferId.toString())?.let {
                transferDao.update(it.copy(state = TransferState.CANCELLED, updatedAtMillis = System.currentTimeMillis()))
            }
        }
    }

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

        val recipientKeyBytes = routingManager.getKnownPublicKey(destination)
        if (recipientKeyBytes == null) {
            updateState(transferId, TransferState.FAILED, "Recipient public key unknown")
            return
        }
        val recipientPublicKey = identityProvider.decodePublicKey(recipientKeyBytes)

        updateState(transferId, TransferState.PREPARING)
        val totalChunks = ChunkManager.totalChunks(sourceFile.length(), chunkSize)
        val fileHash = ChunkManager.fullFileSha256(sourceFile)
        val chunkMetas = ChunkManager.chunkHashes(sourceFile, totalChunks, chunkSize)
            .mapIndexed { index, hash -> ChunkMeta(index, hash) }
        transferDao.upsertChunks(chunkMetas.map {
            TransferChunkEntity(transferId.toString(), it.index, it.sha256, received = false)
        })

        updateState(transferId, TransferState.ENCRYPTING)
        val fileKey = encryptionManager.generateFileKey()
        val wrappedKey = encryptionManager.wrapFileKey(fileKey, recipientPublicKey)
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
        val signature = signatureManager.sign(identityProvider.privateKey(), initPayload.signedBytes())
        val signedInit = initPayload.copy(signature = signature)

        logger.info("CRYPTO", "Transfer $transferId: file key generated, wrapped for recipient, chunks encrypted per-chunk")

        val accepted = sendControlAndAwaitAccept(transferId, destination, route.nextHop, signedInit)
        if (!accepted) {
            updateState(transferId, TransferState.FAILED, "Receiver rejected or did not respond")
            return
        }

        updateState(transferId, TransferState.TRANSFERRING)
        pendingChunkAcks[transferId] = ConcurrentHashMap.newKeySet()

        for (meta in chunkMetas) {
            if (!sendOneChunk(transferId, destination, sourceFile, meta.index, totalChunks, fileKey)) {
                updateState(transferId, TransferState.FAILED, "Failed to send chunk ${meta.index}")
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
        logger.info("TRANSFER", "Transfer $transferId: all $totalChunks chunks sent, TRANSFER_COMPLETE sent")

        // Give the receiver a window to request retransmission of anything it's missing.
        val resendHandled = awaitResendOrTimeout(transferId)
        if (!resendHandled) {
            updateState(transferId, TransferState.FAILED, "Receiver did not confirm completion")
            return
        }

        updateState(transferId, TransferState.COMPLETED)
        logger.info("TRANSFER", "Transfer $transferId: COMPLETED")
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

    private suspend fun awaitResendOrTimeout(
        transferId: UUID
    ): Boolean {
        // Simple bounded wait-and-retry loop: give the receiver time to ask for missing chunks.
        // (destination/sourceFile/totalChunks/fileKey are intentionally not needed here since
        // any resend is driven reactively by onResendRequest() when a CHUNK_RESEND_REQUEST
        // arrives; this loop just waits and checks for cancellation.)
        repeat(3) {
            delay(4_000L)
            val entity = transferDao.getById(transferId.toString()) ?: return false
            if (entity.state == TransferState.CANCELLED) return false
        }
        return true
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
        val result = withTimeoutOrNull(15_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                pendingAccept[transferId] = { accepted -> if (cont.isActive) cont.resumeWith(Result.success(accepted)) }
                scope.launch { transport.sendToNeighbor(firstHop, packet) }
            }
        }
        pendingAccept.remove(transferId)
        return result ?: false
    }

    // ------------------------------------------------------------------------------------
    // RECEIVER SIDE
    // ------------------------------------------------------------------------------------

    private suspend fun handleIncoming(packet: Packet) {
        if (packet.destinationNodeId != identityProvider.nodeId()) return
        when (packet.type) {
            PacketType.TRANSFER_INIT -> onTransferInit(packet)
            PacketType.TRANSFER_ACCEPT -> pendingAccept.remove(packet.transferId)?.invoke(true)
            PacketType.TRANSFER_REJECT -> pendingAccept.remove(packet.transferId)?.invoke(false)
            PacketType.CHUNK_DATA -> onChunkData(packet)
            PacketType.CHUNK_ACK -> onChunkAck(packet)
            PacketType.TRANSFER_COMPLETE -> onTransferComplete(packet)
            PacketType.TRANSFER_CANCEL -> onTransferCancel(packet)
            PacketType.CHUNK_RESEND_REQUEST -> onResendRequest(packet)
            else -> Unit
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

        // Self-certifying identity check: the claimed sender's NodeId must match the hash of
        // the public key actually embedded in this message. A relay cannot forge this without
        // also forging a valid signature below, which it cannot do without the private key.
        val claimedSenderKey = identityProvider.decodePublicKey(init.senderPublicKey)
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

        val staging = fileStorage.stagingFileFor(transferId)
        ChunkManager.preallocate(staging, init.fileSizeBytes)

        val entity = TransferEntity(
            transferId = transferId.toString(),
            direction = TransferDirection.INCOMING,
            peerNodeId = packet.sourceNodeId.value,
            fileName = init.fileName,
            fileSizeBytes = init.fileSizeBytes,
            chunkSizeBytes = init.chunkSizeBytes,
            totalChunks = init.totalChunks,
            state = TransferState.RECEIVING,
            routeCsv = "",
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            localFileUri = staging.absolutePath,
            fileSha256 = init.fileSha256,
            wrappedFileKeyForReceiver = init.wrappedFileKey,
            senderPublicKeyForReceiver = init.senderPublicKey,
            transferInitSignature = init.signature
        )
        transferDao.upsert(entity)
        // We don't have plaintext chunk hashes ahead of time on the receiver side from
        // TRANSFER_INIT alone in this protocol version (only the final file hash), so chunk
        // rows are created lazily as data arrives; missing-chunk detection instead compares
        // against totalChunks.

        sendPacketToward(
            packet.sourceNodeId,
            Packet(
                type = PacketType.TRANSFER_ACCEPT,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = packet.sourceNodeId
            )
        )
        logger.info("TRANSFER", "Transfer $transferId: accepted, awaiting chunks")
    }

    private suspend fun rejectTransfer(transferId: UUID, sender: NodeId) {
        sendPacketToward(
            sender,
            Packet(
                type = PacketType.TRANSFER_REJECT,
                transferId = transferId,
                sourceNodeId = identityProvider.nodeId(),
                destinationNodeId = sender
            )
        )
    }

    private suspend fun onChunkData(packet: Packet) {
        val transferId = packet.transferId ?: return
        val entity = transferDao.getById(transferId.toString()) ?: run {
            logger.warn("TRANSFER", "CHUNK_DATA for unknown transfer $transferId dropped")
            return
        }
        if (entity.state.isTerminal) return

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
        pendingChunkAcks[transferId]?.add(ack.chunkIndex)
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

        val staging = File(entity.localFileUri ?: return)
        val actualHash = ChunkManager.fullFileSha256(staging)
        val expectedHash = entity.fileSha256

        if (expectedHash == null || !HashManager.constantTimeEquals(actualHash, expectedHash)) {
            logger.error("TRANSFER", "Transfer $transferId: FINAL HASH MISMATCH - file rejected")
            updateState(transferId, TransferState.FAILED, "File integrity verification failed")
            fileStorage.deleteStaging(transferId)
            return
        }

        val finalFile = fileStorage.finalFileFor(transferId, entity.fileName)
        fileStorage.moveToFinal(staging, finalFile)

        transferDao.update(
            entity.copy(
                state = TransferState.COMPLETED,
                outputFilePath = finalFile.absolutePath,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
        logger.info("TRANSFER", "Transfer $transferId: COMPLETED, file verified and saved (not auto-opened)")
    }

    private suspend fun onTransferCancel(packet: Packet) {
        val transferId = packet.transferId ?: return
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
        val direct = transport.neighbors.value.any { it.nodeId == destination }
        val nextHop = if (direct) destination else routingManager.getRoute(destination)?.nextHop
        if (nextHop == null) {
            logger.warn("TRANSFER", "No known route to $destination for packet ${packet.type}")
            return false
        }
        return transport.sendToNeighbor(nextHop, packet)
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
}
