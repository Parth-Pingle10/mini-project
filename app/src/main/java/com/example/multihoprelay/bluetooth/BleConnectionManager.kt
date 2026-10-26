package com.example.multihoprelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import com.example.multihoprelay.model.Neighbor
import com.example.multihoprelay.model.NeighborStatus
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import com.example.multihoprelay.networking.FragmentAssembler
import com.example.multihoprelay.networking.LinkReassemblyBuffer
import com.example.multihoprelay.networking.MalformedPacketException
import com.example.multihoprelay.networking.MeshTransport
import com.example.multihoprelay.networking.PacketCodec
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

private enum class LinkRole { CLIENT, SERVER }

private data class Link(
    val device: BluetoothDevice,
    var role: LinkRole,
    var nodeId: NodeId?,
    var rssi: Int,
    var lastSeenMillis: Long,
    var status: NeighborStatus = NeighborStatus.DISCOVERED,
    val reassembly: LinkReassemblyBuffer = LinkReassemblyBuffer(),
    var gattClient: BleGattClient? = null,
    var mtuPayloadSize: Int = GattProfile.DEFAULT_ATT_PAYLOAD_SIZE,
    var protocolVersion: Int = GattProfile.PROTOCOL_VERSION
)

/**
 * Central coordinator that turns the low-level BLE primitives ([BleScanner], [BleAdvertiser],
 * [BleGattServer], [BleGattClient]) into the transport-agnostic [MeshTransport] interface
 * that [com.example.multihoprelay.networking.RoutingManager] / RelayManager / TransferManager
 * are written against.
 *
 * Connection symmetry-breaking: when two nodes discover each other via scanning, BOTH could
 * try to open a GATT client connection to the other, which would create two redundant links.
 * To avoid this, a node only initiates a client connection if its own [NodeId] sorts lower
 * (lexicographically) than the peer's; otherwise it waits for the peer to connect to it (as
 * GATT server). This is a simple, deterministic tie-break requiring no extra negotiation.
 */
class BleConnectionManager(
    private val context: Context,
    override val selfNodeId: NodeId,
    private val scope: CoroutineScope,
    private val logger: MeshLogger,
    private val publicKeyProvider: (() -> ByteArray)? = null
) : MeshTransport {

    private val links = ConcurrentHashMap<String, Link>() // keyed by device address
    private val scanCache = ConcurrentHashMap<String, NodeId>() // device address -> nodeId, from scan results

    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    override val neighbors: StateFlow<List<Neighbor>> = _neighbors

    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<Packet> = _incomingPackets

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val advertiser = BleAdvertiser(context, logger)
    private val scanner = BleScanner(context, logger)
    private lateinit var gattServer: BleGattServer

    private var scanJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var maintenanceJob: Job? = null
    private var onNeighborLostCallback: ((NodeId) -> Unit)? = null

    fun setOnNeighborLostListener(listener: (NodeId) -> Unit) {
        onNeighborLostCallback = listener
    }

    fun start() {
        gattServer = BleGattServer(
            context = context,
            logger = logger,
            onFragmentReceived = { device, bytes -> onFragmentFromLink(device, LinkRole.SERVER, bytes) },
            onDeviceConnected = { device -> onServerDeviceConnected(device) },
            onDeviceDisconnected = { device -> onServerDeviceDisconnected(device.address) },
            onMtuChangedCallback = { device, mtu ->
                links[device.address]?.mtuPayloadSize = mtu - 3
                logger.debug("BLE", "MTU negotiated: $mtu with ${links[device.address]?.nodeId ?: device.address}")
            }
        )
        gattServer.start()
        advertiser.startAdvertising(selfNodeId) { }

        startScan(timeoutMillis = 15_000L)

        maintenanceJob = scope.launch {
            while (true) {
                delay(5_000L)
                pruneStaleNeighbors()
            }
        }
    }

    fun stop() {
        stopScan()
        maintenanceJob?.cancel()
        maintenanceJob = null
        advertiser.stopAdvertising()
        gattServer.stop()
        links.values.forEach { it.gattClient?.disconnect() }
        links.clear()
        _isScanning.value = false
        _neighbors.value = emptyList()
    }

    /**
     * Manually triggers a connection to a discovered neighbor.
     */
    fun connectToNeighbor(nodeId: NodeId): Boolean {
        val link = links.values.firstOrNull { it.nodeId == nodeId } ?: run {
            logger.warn("BLE", "connectToNeighbor: Node $nodeId not found in scan records")
            return false
        }
        if (link.status == NeighborStatus.CONNECTED || link.status == NeighborStatus.CONNECTING) {
            return true
        }
        link.status = NeighborStatus.CONNECTING
        link.role = LinkRole.CLIENT
        logger.info("BLE", "User requested connection to $nodeId")
        connectGattClient(link)
        publishNeighbors()
        return true
    }

    /**
     * Manually disconnects from a neighbor.
     */
    fun disconnectFromNeighbor(nodeId: NodeId): Boolean {
        val link = links.values.firstOrNull { it.nodeId == nodeId } ?: return false
        logger.info("BLE", "User requested disconnect from $nodeId")
        link.status = NeighborStatus.DISCONNECTED
        link.gattClient?.disconnect()
        link.gattClient = null
        publishNeighbors()
        onNeighborLostCallback?.invoke(nodeId)
        return true
    }

    /**
     * Manually triggers a fresh BLE discovery cycle.
     * Does NOT disconnect existing connections.
     */
    fun refreshDiscovery(timeoutMillis: Long = 10_000L) {
        logger.info("BLE", "Discovery refresh requested")
        // Prune stale disconnected or expired discovered entries before restarting scan
        val now = System.currentTimeMillis()
        val staleKeys = links.filterValues {
            it.status == NeighborStatus.DISCONNECTED ||
                (it.status == NeighborStatus.DISCOVERED && now - it.lastSeenMillis > 15_000L)
        }.keys
        staleKeys.forEach { links.remove(it) }
        publishNeighbors()

        startScan(timeoutMillis)
    }

    private fun startScan(timeoutMillis: Long) {
        scanJob?.cancel()
        scanTimeoutJob?.cancel()

        _isScanning.value = true
        scanJob = scope.launch {
            try {
                scanner.scanFlow().collect { observation ->
                    scanCache[observation.device.address] = observation.nodeId
                    onScanObservation(observation)
                }
            } finally {
                _isScanning.value = false
            }
        }

        scanTimeoutJob = scope.launch {
            delay(timeoutMillis)
            if (scanJob?.isActive == true) {
                logger.debug("BLE", "Scan timeout reached after ${timeoutMillis}ms")
                scanJob?.cancel()
                _isScanning.value = false
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        _isScanning.value = false
    }

    private fun onScanObservation(observation: ScanObservation) {
        val address = observation.device.address
        val existing = links[address]
        if (existing != null) {
            existing.rssi = observation.rssi
            existing.lastSeenMillis = System.currentTimeMillis()
            existing.protocolVersion = observation.protocolVersion
            if (existing.nodeId == null) {
                existing.nodeId = observation.nodeId
            }

            // If it was disconnected, attempt reconnect if we are the client initiator
            if (existing.status == NeighborStatus.DISCONNECTED) {
                val weInitiate = selfNodeId.value < observation.nodeId.value
                if (weInitiate) {
                    existing.status = NeighborStatus.CONNECTING
                    logger.info("BLE", "Connecting to ${observation.nodeId}")
                    connectGattClient(existing)
                } else {
                    existing.status = NeighborStatus.DISCOVERED
                    logger.info("BLE", "Discovered node: ${observation.nodeId} RSSI=${observation.rssi}")
                }
            }
            publishNeighbors()
            return
        }

        // Symmetry-breaking: only initiate a client connection if we "win" the tie-break.
        val weInitiate = selfNodeId.value < observation.nodeId.value
        if (!weInitiate) {
            // Track as DISCOVERED; expect peer (which has lower Node ID) to connect to our GATT server.
            links[address] = Link(
                device = observation.device,
                role = LinkRole.SERVER,
                nodeId = observation.nodeId,
                rssi = observation.rssi,
                lastSeenMillis = System.currentTimeMillis(),
                status = NeighborStatus.DISCOVERED,
                protocolVersion = observation.protocolVersion
            )
            logger.info("BLE", "Discovered node: ${observation.nodeId} RSSI=${observation.rssi}")
            publishNeighbors()
            return
        }

        val link = Link(
            device = observation.device,
            role = LinkRole.CLIENT,
            nodeId = observation.nodeId,
            rssi = observation.rssi,
            lastSeenMillis = System.currentTimeMillis(),
            status = NeighborStatus.CONNECTING,
            protocolVersion = observation.protocolVersion
        )
        links[address] = link
        logger.info("BLE", "Discovered node: ${observation.nodeId} RSSI=${observation.rssi}")
        logger.info("BLE", "Connecting to ${observation.nodeId}")

        connectGattClient(link)
        publishNeighbors()
    }

    private fun connectGattClient(link: Link) {
        val address = link.device.address
        val client = BleGattClient(
            context = context,
            device = link.device,
            logger = logger,
            onFragmentReceived = { bytes -> onFragmentFromLink(link.device, LinkRole.CLIENT, bytes) },
            onStateChanged = { state -> onClientStateChanged(address, state) }
        )
        link.gattClient = client
        client.connect()
    }

    private fun onClientStateChanged(address: String, state: ClientConnectionState) {
        val link = links[address] ?: return
        when (state) {
            ClientConnectionState.CONNECTED -> {
                link.status = NeighborStatus.CONNECTED
                link.mtuPayloadSize = link.gattClient?.mtuPayloadSize ?: link.mtuPayloadSize
                logger.info("BLE", "GATT connected: ${link.nodeId ?: address}")
                logger.info("BLE", "Neighbor connected: ${link.nodeId ?: address}")
                // Send initial HELLO packet with public key so remote GATT server learns our Node ID and public key
                scope.launch {
                    val keyPayload = publicKeyProvider?.invoke() ?: selfNodeId.value.toByteArray(Charsets.US_ASCII)
                    val helloPacket = Packet(
                        type = PacketType.HELLO,
                        sourceNodeId = selfNodeId,
                        destinationNodeId = link.nodeId ?: NodeId.BROADCAST,
                        payload = keyPayload
                    )
                    sendOverLink(link, helloPacket)
                }
                publishNeighbors()
            }
            ClientConnectionState.DISCONNECTED, ClientConnectionState.FAILED -> {
                val lostNodeId = link.nodeId
                link.status = NeighborStatus.DISCONNECTED
                link.gattClient?.disconnect()
                link.gattClient = null
                logger.warn("BLE", "Connection lost: ${lostNodeId ?: address}")
                publishNeighbors()
                if (lostNodeId != null) {
                    onNeighborLostCallback?.invoke(lostNodeId)
                }
            }
            ClientConnectionState.CONNECTING -> {
                link.status = NeighborStatus.CONNECTING
                publishNeighbors()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onServerDeviceConnected(device: BluetoothDevice) {
        val address = device.address
        val cachedId = scanCache[address]
        val link = links.getOrPut(address) {
            Link(
                device = device,
                role = LinkRole.SERVER,
                nodeId = cachedId,
                rssi = 0,
                lastSeenMillis = System.currentTimeMillis(),
                status = NeighborStatus.CONNECTED
            )
        }
        link.status = NeighborStatus.CONNECTED
        link.lastSeenMillis = System.currentTimeMillis()
        if (cachedId != null) {
            links.entries.removeIf { it.key != address && it.value.nodeId == cachedId }
            if (link.nodeId == null) {
                link.nodeId = cachedId
            }
        }
        logger.info("BLE", "GATT connected: ${link.nodeId ?: address}")
        logger.info("BLE", "Neighbor connected: ${link.nodeId ?: address}")
        publishNeighbors()
    }

    private fun onServerDeviceDisconnected(address: String) {
        val link = links[address]
        if (link != null) {
            val lostNodeId = link.nodeId
            link.status = NeighborStatus.DISCONNECTED
            logger.warn("BLE", "Connection lost: ${lostNodeId ?: address}")
            publishNeighbors()
            if (lostNodeId != null) {
                onNeighborLostCallback?.invoke(lostNodeId)
            }
        }
    }

    private fun onFragmentFromLink(device: BluetoothDevice, role: LinkRole, bytes: ByteArray) {
        val link = links[device.address] ?: links.getOrPut(device.address) {
            Link(
                device = device,
                role = role,
                nodeId = scanCache[device.address],
                rssi = 0,
                lastSeenMillis = System.currentTimeMillis(),
                status = NeighborStatus.CONNECTED
            )
        }
        link.lastSeenMillis = System.currentTimeMillis()
        logger.debug("BLE", "TRANSFER_FRAGMENT_RECEIVED: from=${device.address} bytes=${bytes.size}")

        val complete = try {
            link.reassembly.onFragmentReceived(bytes)
        } catch (e: MalformedPacketException) {
            logger.warn("BLE", "Malformed fragment from ${device.address} dropped: ${e.message}")
            return
        }
        if (complete != null) {
            try {
                val packet = PacketCodec.decode(complete)
                logger.info("BLE", "TRANSFER_PACKET_REASSEMBLED: type=${packet.type} transferId=${packet.transferId} from=${packet.sourceNodeId}")
                if (link.nodeId == null || link.nodeId != packet.sourceNodeId) {
                    link.nodeId = packet.sourceNodeId
                    link.status = NeighborStatus.CONNECTED
                    publishNeighbors()
                }
                if (packet.type == PacketType.HELLO) {
                    link.status = NeighborStatus.CONNECTED
                    publishNeighbors()
                    // If we are the server link, reply with our HELLO carrying our public key
                    if (link.role == LinkRole.SERVER) {
                        scope.launch {
                            val keyPayload = publicKeyProvider?.invoke() ?: selfNodeId.value.toByteArray(Charsets.US_ASCII)
                            val replyHello = Packet(
                                type = PacketType.HELLO,
                                sourceNodeId = selfNodeId,
                                destinationNodeId = packet.sourceNodeId,
                                payload = keyPayload
                            )
                            sendOverLink(link, replyHello)
                        }
                    }
                }
                scope.launch { _incomingPackets.emit(packet) }
            } catch (e: MalformedPacketException) {
                logger.warn("BLE", "Malformed/corrupted packet from ${device.address} rejected: ${e.message}")
            }
        }
    }

    override suspend fun sendToNeighbor(neighborId: NodeId, packet: Packet): Boolean {
        val link = links.values.firstOrNull { it.nodeId == neighborId && it.status == NeighborStatus.CONNECTED } ?: run {
            logger.warn("BLE", "sendToNeighbor: $neighborId is not a connected neighbor")
            return false
        }
        return sendOverLink(link, packet)
    }

    override suspend fun broadcastToNeighbors(packet: Packet, excluding: NodeId?) {
        links.values.filter { it.nodeId != null && it.nodeId != excluding && it.status == NeighborStatus.CONNECTED }.forEach { link ->
            sendOverLink(link, packet)
        }
    }

    private fun sendOverLink(link: Link, packet: Packet): Boolean {
        val encoded = try {
            PacketCodec.encode(packet)
        } catch (e: Exception) {
            logger.error("BLE", "Failed to encode packet ${packet.packetId}: ${e.message}")
            return false
        }
        val logicalId = Random.nextInt()
        val fragments = FragmentAssembler.fragment(encoded, link.mtuPayloadSize.coerceAtLeast(GattProfile.DEFAULT_ATT_PAYLOAD_SIZE), logicalId)

        if (packet.type == PacketType.TRANSFER_INIT) {
            logger.info("BLE", "TRANSFER_INIT_QUEUED: transferId=${packet.transferId} to=${link.nodeId ?: link.device.address}")
            logger.info("BLE", "TRANSFER_INIT_WRITE_STARTED: transferId=${packet.transferId} to=${link.nodeId ?: link.device.address} fragments=${fragments.size}")
        }

        val result = if (link.gattClient != null && link.status == NeighborStatus.CONNECTED) {
            val client = link.gattClient!!
            fragments.forEach { client.sendFragment(it) }
            true
        } else {
            when (link.role) {
                LinkRole.CLIENT -> {
                    val client = link.gattClient ?: return false
                    fragments.forEach { client.sendFragment(it) }
                    true
                }
                LinkRole.SERVER -> {
                    var allQueued = true
                    fragments.forEach {
                        val queued = gattServer.queueNotify(link.device, it)
                        if (!queued) {
                            allQueued = false
                            logger.warn("BLE", "queueNotify failed for fragment to ${link.device.address} (notifications not enabled)")
                        }
                    }
                    allQueued
                }
            }
        }

        if (result && packet.type == PacketType.TRANSFER_INIT) {
            logger.info("BLE", "TRANSFER_INIT_WRITE_COMPLETED: transferId=${packet.transferId} to=${link.nodeId ?: link.device.address}")
        }
        return result
    }

    private fun pruneStaleNeighbors() {
        val now = System.currentTimeMillis()
        // Only prune stale disconnected or idle discovered server placeholders
        val stale = links.filterValues {
            (it.status == NeighborStatus.DISCONNECTED && now - it.lastSeenMillis > 30_000L) ||
                (it.status == NeighborStatus.DISCOVERED && now - it.lastSeenMillis > 30_000L && it.gattClient == null)
        }
        stale.keys.forEach { links.remove(it) }
        if (stale.isNotEmpty()) publishNeighbors()
    }

    private fun publishNeighbors() {
        _neighbors.value = links.values
            .filter { it.nodeId != null }
            .map { link ->
                Neighbor(
                    nodeId = link.nodeId!!,
                    rssi = link.rssi,
                    lastSeenMillis = link.lastSeenMillis,
                    isConnected = (link.status == NeighborStatus.CONNECTED),
                    status = link.status,
                    deviceAddress = link.device.address,
                    protocolVersion = link.protocolVersion
                )
            }
            .sortedWith(
                compareByDescending<Neighbor> { it.status == NeighborStatus.CONNECTED }
                    .thenByDescending { it.status == NeighborStatus.CONNECTING }
                    .thenBy { it.nodeId.value }
            )
            .distinctBy { it.nodeId }
    }
}
