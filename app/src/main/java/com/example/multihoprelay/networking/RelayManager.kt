package com.example.multihoprelay.networking

import com.example.multihoprelay.data.database.dao.RelayQueueDao
import com.example.multihoprelay.data.database.entity.RelayPacketEntity
import com.example.multihoprelay.data.settings.RelaySettings
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import com.example.multihoprelay.model.RelayPacketState
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class RelayStats(
    val relayModeEnabled: Boolean = false,
    val activeRelays: Int = 0,
    val queuedPackets: Int = 0,
    val storageUsedBytes: Long = 0,
    val storageLimitBytes: Long = 0,
    val packetsForwarded: Long = 0,
    val packetsDropped: Long = 0,
    val currentBandwidthBps: Long = 0
)

/**
 * Implements the relay ("Relay Mode") behavior described in the requirements:
 *
 *  - Strictly opt-in via [SettingsRepository]; when disabled this device forwards nothing
 *    on behalf of other nodes (it still participates in routing discovery replies for its
 *    own routes, and still sends/receives its own transfers).
 *  - Blind relay: only ever touches the fixed [Packet] header fields (source, destination,
 *    hop count, packet id, sequence number). [Packet.payload] is opaque encrypted bytes that
 *    this code never attempts to parse, decrypt, or interpret in any way.
 *  - Store-and-forward: every accepted packet is persisted to Room ([RelayQueueDao]) before
 *    any forwarding attempt, so a temporary loss of the next-hop connection does not lose the
 *    packet - it stays QUEUED and is retried on a timer until forwarded or expired.
 *  - Enforces configurable limits: maximum hop count, maximum storage, maximum packet age,
 *    maximum concurrent forwards, and a simple bandwidth cap (token-bucket-like delay).
 */
class RelayManager(
    private val transport: MeshTransport,
    private val routingManager: RoutingManager,
    private val relayQueueDao: RelayQueueDao,
    private val relaySettingsFlow: Flow<RelaySettings>,
    private val scope: CoroutineScope,
    private val logger: MeshLogger
) {
    private var currentSettings = RelaySettings()
    private val recentlyForwarded = ConcurrentHashMap<String, Long>() // packetId -> forwardedAtMillis, replay guard
    private val packetsForwarded = AtomicLong(0)
    private val packetsDropped = AtomicLong(0)
    private val bandwidthWindow = ConcurrentHashMap<Long, Long>() // second-bucket -> bytes forwarded

    private val _stats = MutableStateFlow(RelayStats())
    val stats: StateFlow<RelayStats> = _stats

    private var forwardSemaphore = Semaphore(currentSettings.maxConcurrentRelays)
    private var jobs: MutableList<Job> = mutableListOf()

    private val relayableTypes = setOf(
        PacketType.TRANSFER_INIT, PacketType.TRANSFER_ACCEPT, PacketType.TRANSFER_REJECT,
        PacketType.CHUNK_DATA, PacketType.CHUNK_ACK, PacketType.TRANSFER_COMPLETE,
        PacketType.TRANSFER_CANCEL, PacketType.CHUNK_RESEND_REQUEST, PacketType.TRANSFER_CONFIRM
    )

    fun start(startPeriodicMaintenance: Boolean = true) {
        jobs += relaySettingsFlow.onEach { settings ->
            currentSettings = settings
            if (forwardSemaphore.availablePermits != settings.maxConcurrentRelays) {
                forwardSemaphore = Semaphore(settings.maxConcurrentRelays)
            }
            refreshStats()
        }.launchIn(scope)

        jobs += transport.incomingPackets.onEach { packet -> onPacketReceived(packet) }.launchIn(scope)

        if (startPeriodicMaintenance) {
            jobs += scope.launch {
                while (true) {
                    delay(5_000L)
                    retryQueuedPackets()
                    cleanupExpired()
                    refreshStats()
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun onPacketReceived(packet: Packet) {
        if (packet.type !in relayableTypes) return // routing packets are handled by RoutingManager
        if (packet.destinationNodeId == transport.selfNodeId) return // it's ours, TransferManager handles it
        if (packet.destinationNodeId == NodeId.BROADCAST) return

        if (!currentSettings.relayModeEnabled) {
            logger.debug("RELAY", "Relay Mode is OFF - dropping packet ${packet.packetId} not addressed to us")
            packetsDropped.incrementAndGet()
            refreshStats()
            return
        }

        val packetKey = packet.packetId.toString()
        if (recentlyForwarded.containsKey(packetKey) || relayQueueDao.exists(packetKey)) {
            logger.debug("RELAY", "Duplicate/replayed packet ${packet.packetId} ignored")
            return
        }

        if (packet.isExpiredByHopCount()) {
            logger.warn("RELAY", "Packet ${packet.packetId} dropped - exceeded max hop count (${packet.maxHopCount})")
            packetsDropped.incrementAndGet()
            refreshStats()
            return
        }
        if (packet.hopCount + 1 > currentSettings.maxHops) {
            logger.warn("RELAY", "Packet ${packet.packetId} dropped - exceeds configured max hops")
            packetsDropped.incrementAndGet()
            refreshStats()
            return
        }

        val sizeBytes = packet.payload.size
        val currentUsage = relayQueueDao.totalStorageBytes()
        if (currentUsage + sizeBytes > currentSettings.maxRelayStorageBytes) {
            logger.warn(
                "RELAY",
                "Packet ${packet.packetId} dropped - relay storage limit reached " +
                    "(${currentUsage / 1024}KB / ${currentSettings.maxRelayStorageBytes / 1024}KB)"
            )
            packetsDropped.incrementAndGet()
            refreshStats()
            return
        }

        val now = System.currentTimeMillis()
        val entity = RelayPacketEntity(
            packetId = packetKey,
            transferId = packet.transferId?.toString(),
            packetTypeCode = packet.type.code,
            sourceNodeId = packet.sourceNodeId.value,
            destinationNodeId = packet.destinationNodeId.value,
            nextHop = "", // resolved at forward time
            hopCount = packet.hopCount,
            maxHopCount = packet.maxHopCount,
            sequenceNumber = packet.sequenceNumber,
            payload = packet.payload,
            sizeBytes = sizeBytes,
            state = RelayPacketState.QUEUED,
            receivedAtMillis = now,
            expiresAtMillis = now + currentSettings.maxPacketAgeMillis
        )
        relayQueueDao.insert(entity)
        logger.info(
            "RELAY",
            "Packet ${shortId(packet.packetId.toString())} queued " +
                "(${packet.sourceNodeId} -> ${packet.destinationNodeId}, hop ${packet.hopCount}, $sizeBytes bytes)"
        )
        refreshStats()

        attemptForward(entity, packet)
    }

    private suspend fun retryQueuedPackets() {
        if (!currentSettings.relayModeEnabled) return
        val queued = relayQueueDao.getByState(RelayPacketState.QUEUED)
        queued.forEach { entity ->
            val packet = entityToPacket(entity)
            attemptForward(entity, packet)
        }
    }

    private fun attemptForward(entity: RelayPacketEntity, packet: Packet) {
        val route = routingManager.getRoute(packet.destinationNodeId)
        val directNeighbor = transport.neighbors.value.any { it.nodeId == packet.destinationNodeId }
        val nextHop = when {
            directNeighbor -> packet.destinationNodeId
            route != null -> route.nextHop
            else -> null
        }

        if (nextHop == null) {
            logger.debug(
                "RELAY",
                "No route yet for ${shortId(packet.packetId.toString())} -> " +
                    "${packet.destinationNodeId}; keeping queued for store-and-forward"
            )
            return
        }

        scope.launch {
            forwardSemaphore.withPermit {
                throttleForBandwidth(entity.sizeBytes)
                val forwardPacket = packet.withIncrementedHop()
                val success = transport.sendToNeighbor(nextHop, forwardPacket)
                if (success) {
                    packetsForwarded.incrementAndGet()
                    recordBandwidth(entity.sizeBytes)
                    recentlyForwarded[entity.packetId] = System.currentTimeMillis()
                    relayQueueDao.deleteById(entity.packetId)
                    logger.info(
                        "RELAY",
                        "Packet ${shortId(entity.packetId)} forwarded to $nextHop " +
                            "(next hop toward ${packet.destinationNodeId})"
                    )
                } else {
                    val updated = entity.copy(
                        state = RelayPacketState.QUEUED,
                        retryCount = entity.retryCount + 1,
                        lastAttemptMillis = System.currentTimeMillis(),
                        nextHop = nextHop.value
                    )
                    relayQueueDao.update(updated)
                    logger.warn(
                        "RELAY",
                        "Forward attempt failed for ${shortId(entity.packetId)} " +
                            "toward $nextHop (retry ${updated.retryCount})"
                    )
                }
                refreshStats()
            }
        }
    }

    private suspend fun throttleForBandwidth(sizeBytes: Int) {
        val limit = currentSettings.maxBandwidthBytesPerSecond
        if (limit <= 0) return
        val currentSecondUsage = bandwidthWindow[currentSecondBucket()] ?: 0L
        if (currentSecondUsage + sizeBytes > limit) {
            delay(250L) // simple backoff; re-checked by caller loop cadence
        }
    }

    private fun recordBandwidth(sizeBytes: Int) {
        val bucket = currentSecondBucket()
        bandwidthWindow[bucket] = (bandwidthWindow[bucket] ?: 0L) + sizeBytes
        // Keep the window small.
        bandwidthWindow.keys.filter { it < bucket - 5 }.forEach { bandwidthWindow.remove(it) }
    }

    private fun currentSecondBucket(): Long = System.currentTimeMillis() / 1000

    private suspend fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val expiredCount = relayQueueDao.deleteExpired(now)
        if (expiredCount > 0) {
            logger.info("RELAY", "$expiredCount expired relay packet(s) purged from store-and-forward queue")
        }
    }

    private suspend fun refreshStats() {
        val queuedCount = relayQueueDao.countByState(RelayPacketState.QUEUED)
        val usage = relayQueueDao.totalStorageBytes()
        _stats.value = RelayStats(
            relayModeEnabled = currentSettings.relayModeEnabled,
            activeRelays = currentSettings.maxConcurrentRelays - forwardSemaphore.availablePermits,
            queuedPackets = queuedCount,
            storageUsedBytes = usage,
            storageLimitBytes = currentSettings.maxRelayStorageBytes,
            packetsForwarded = packetsForwarded.get(),
            packetsDropped = packetsDropped.get(),
            currentBandwidthBps = bandwidthWindow[currentSecondBucket() - 1] ?: 0L
        )
    }

    suspend fun clearRelayCache() {
        relayQueueDao.clearAll()
        recentlyForwarded.clear()
        refreshStats()
        logger.info("RELAY", "Relay cache cleared by user")
    }

    private fun entityToPacket(entity: RelayPacketEntity): Packet = Packet(
        type = PacketType.fromCode(entity.packetTypeCode),
        packetId = java.util.UUID.fromString(entity.packetId),
        transferId = entity.transferId?.let { java.util.UUID.fromString(it) },
        sourceNodeId = NodeId(entity.sourceNodeId),
        destinationNodeId = NodeId(entity.destinationNodeId),
        hopCount = entity.hopCount,
        maxHopCount = entity.maxHopCount,
        sequenceNumber = entity.sequenceNumber,
        payload = entity.payload
    )

    private fun shortId(uuid: String): String = uuid.take(8)
}
