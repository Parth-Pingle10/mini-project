package com.example.multihoprelay.nearby

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.multihoprelay.model.Neighbor
import com.example.multihoprelay.model.NeighborStatus
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import com.example.multihoprelay.model.PendingIncomingConnection
import com.example.multihoprelay.networking.MeshTransport
import com.example.multihoprelay.networking.PacketCodec
import com.example.multihoprelay.util.MeshLogger
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Transport implementation backed by Google's official Android Nearby Connections API
 * from Google Play services.
 *
 * Uses [Strategy.P2P_CLUSTER] to discover and connect to nearby nodes in an M-to-N mesh,
 * handles connection establishment with user authorization, and enables direct packet
 * and native file payload exchange without requiring an API key or internet connection.
 */
class NearbyConnectionManager(
    private val context: Context,
    override val selfNodeId: NodeId,
    private val scope: CoroutineScope,
    private val logger: MeshLogger
) : MeshTransport {

    companion object {
        const val SERVICE_ID = "com.example.multihoprelay"
    }

    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context.applicationContext)
    }

    private val localEndpointName: String
        get() = "${selfNodeId.value}|${Build.MODEL.take(20)}"

    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    override val neighbors: StateFlow<List<Neighbor>> = _neighbors.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 128)
    override val incomingPackets: SharedFlow<Packet> = _incomingPackets.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _pendingIncomingConnection = MutableStateFlow<PendingIncomingConnection?>(null)
    val pendingIncomingConnection: StateFlow<PendingIncomingConnection?> = _pendingIncomingConnection.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val endpointToNodeId = ConcurrentHashMap<String, NodeId>()
    private val nodeIdToEndpoint = ConcurrentHashMap<NodeId, String>()
    private val nodeIdToDeviceName = ConcurrentHashMap<NodeId, String>()
    private val initiatedConnections = ConcurrentHashMap.newKeySet<String>()

    val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()
    val payloadToTransfer = ConcurrentHashMap<Long, UUID>()
    val transferToPayload = ConcurrentHashMap<UUID, Long>()

    private var onNeighborLostCallback: ((NodeId) -> Unit)? = null
    private var onNeighborConnectedCallback: ((NodeId) -> Unit)? = null
    var fileTransferUpdateListener: ((transferId: UUID, update: PayloadTransferUpdate, payload: Payload?) -> Unit)? = null

    private var bluetoothReceiver: BroadcastReceiver? = null
    private var maintenanceJob: Job? = null

    fun setOnNeighborLostListener(listener: (NodeId) -> Unit) {
        onNeighborLostCallback = listener
    }

    fun setOnNeighborConnectedListener(listener: (NodeId) -> Unit) {
        onNeighborConnectedCallback = listener
    }

    fun getDeviceName(nodeId: NodeId): String? = nodeIdToDeviceName[nodeId]

    fun start() {
        registerBluetoothStateReceiver()
        startAdvertising()
        startDiscovery()
        startPeriodicMaintenance()
    }

    fun stop() {
        _isScanning.value = false
        maintenanceJob?.cancel()
        maintenanceJob = null
        if (bluetoothReceiver != null) {
            try {
                context.applicationContext.unregisterReceiver(bluetoothReceiver)
            } catch (_: Exception) {}
            bluetoothReceiver = null
        }
        try {
            connectionsClient.stopDiscovery()
            connectionsClient.stopAdvertising()
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            logger.warn("NEARBY", "Error stopping Nearby client: ${e.message}")
        }
        _neighbors.value = emptyList()
        _pendingIncomingConnection.value = null
        endpointToNodeId.clear()
        nodeIdToEndpoint.clear()
        initiatedConnections.clear()
        incomingFilePayloads.clear()
        payloadToTransfer.clear()
        transferToPayload.clear()
        logger.info("NEARBY", "Nearby Connections stopped")
    }

    private fun registerBluetoothStateReceiver() {
        if (bluetoothReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                            logger.warn("NEARBY", "Bluetooth disabled by user - clearing all active mesh neighbors")
                            _isScanning.value = false
                            val lostNodes = _neighbors.value.map { it.nodeId }
                            _neighbors.value = emptyList()
                            _pendingIncomingConnection.value = null
                            endpointToNodeId.clear()
                            nodeIdToEndpoint.clear()
                            initiatedConnections.clear()
                            _statusMessage.value = "Bluetooth disabled"
                            lostNodes.forEach { onNeighborLostCallback?.invoke(it) }
                        }
                        BluetoothAdapter.STATE_ON -> {
                            logger.info("NEARBY", "Bluetooth enabled by user - auto-restarting Nearby discovery & advertising")
                            scope.launch {
                                delay(600L)
                                refreshDiscovery()
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            bluetoothReceiver = receiver
            logger.info("NEARBY", "Registered Bluetooth state change receiver")
        } catch (e: Exception) {
            logger.warn("NEARBY", "Failed to register Bluetooth state receiver: ${e.message}")
        }
    }

    private fun startPeriodicMaintenance() {
        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            while (true) {
                delay(8_000L)
                pruneStaleDiscoveredNeighbors()
            }
        }
    }

    private fun pruneStaleDiscoveredNeighbors() {
        val now = System.currentTimeMillis()
        val currentList = _neighbors.value
        val stale = currentList.filter { !it.isConnected && it.status == NeighborStatus.DISCOVERED && it.isStale(now, 25_000L) }
        if (stale.isNotEmpty()) {
            val staleIds = stale.map { it.nodeId }.toSet()
            logger.info("NEARBY", "Pruning ${stale.size} stale discovered neighbor(s): ${staleIds.joinToString { it.value }}")
            staleIds.forEach { staleId ->
                val ep = nodeIdToEndpoint.remove(staleId)
                if (ep != null) {
                    endpointToNodeId.remove(ep)
                    initiatedConnections.remove(ep)
                }
            }
            _neighbors.value = currentList.filter { !staleIds.contains(it.nodeId) }
            staleIds.forEach { onNeighborLostCallback?.invoke(it) }
        }
    }

    fun refreshDiscovery() {
        logger.info("NEARBY", "Refreshing Nearby discovery and advertising")
        // Preserve genuinely CONNECTED neighbors; remove stale unconnected ones so they can be freshly discovered with valid endpoints
        val connectedNeighbors = _neighbors.value.filter { it.isConnected }
        _neighbors.value = connectedNeighbors
        val connectedNodeIds = connectedNeighbors.map { it.nodeId }.toSet()
        nodeIdToEndpoint.keys.retainAll(connectedNodeIds)
        val connectedEndpoints = nodeIdToEndpoint.values.toSet()
        endpointToNodeId.keys.retainAll(connectedEndpoints)
        initiatedConnections.clear()

        try {
            connectionsClient.stopDiscovery()
            connectionsClient.stopAdvertising()
        } catch (e: Exception) {
            logger.debug("NEARBY", "Stop discovery/advertising warning: ${e.message}")
        }
        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        connectionsClient.startAdvertising(
            localEndpointName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            logger.info("NEARBY", "Advertising started as $localEndpointName with service ID $SERVICE_ID")
            _statusMessage.value = "Advertising active"
        }.addOnFailureListener { e ->
            logger.error("NEARBY", "Failed to start advertising: ${e.message}")
            _statusMessage.value = "Advertising error: ${e.message}"
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_CLUSTER)
            .build()

        _isScanning.value = true
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            logger.info("NEARBY", "Discovery started for service ID $SERVICE_ID")
            _statusMessage.value = "Discovery active"
        }.addOnFailureListener { e ->
            _isScanning.value = false
            logger.error("NEARBY", "Failed to start discovery: ${e.message}")
            _statusMessage.value = "Discovery error: ${e.message}"
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val (peerNodeId, devName) = parseEndpointName(info.endpointName)
            if (peerNodeId == selfNodeId) {
                logger.debug("NEARBY", "Ignored self endpoint $endpointId ($peerNodeId)")
                return
            }
            logger.info("NEARBY", "Discovered endpoint $endpointId: Node $peerNodeId ($devName)")

            val oldEndpoint = nodeIdToEndpoint[peerNodeId]
            if (oldEndpoint != null && oldEndpoint != endpointId) {
                endpointToNodeId.remove(oldEndpoint)
                initiatedConnections.remove(oldEndpoint)
            }

            endpointToNodeId[endpointId] = peerNodeId
            nodeIdToEndpoint[peerNodeId] = endpointId
            nodeIdToDeviceName[peerNodeId] = devName

            val current = _neighbors.value.firstOrNull { it.nodeId == peerNodeId }
            if (current == null || !current.isConnected) {
                updateNeighbor(peerNodeId, endpointId, NeighborStatus.DISCOVERED, isConnected = false)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            val peerNodeId = endpointToNodeId.remove(endpointId) ?: return
            logger.info("NEARBY", "Endpoint lost: $endpointId ($peerNodeId)")
            if (nodeIdToEndpoint[peerNodeId] == endpointId) {
                nodeIdToEndpoint.remove(peerNodeId)
                val current = _neighbors.value.firstOrNull { it.nodeId == peerNodeId }
                if (current?.status != NeighborStatus.CONNECTED) {
                    _neighbors.value = _neighbors.value.filter { it.nodeId != peerNodeId }
                    onNeighborLostCallback?.invoke(peerNodeId)
                }
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val (peerNodeId, devName) = parseEndpointName(info.endpointName)
            endpointToNodeId[endpointId] = peerNodeId
            nodeIdToEndpoint[peerNodeId] = endpointId
            nodeIdToDeviceName[peerNodeId] = devName

            logger.info(
                "NEARBY",
                "Connection initiated with endpoint $endpointId: Node $peerNodeId ($devName), auth=${info.authenticationDigits}, incoming=${info.isIncomingConnection}"
            )

            if (initiatedConnections.contains(endpointId)) {
                // Outgoing connection requested by us: accept automatically on our side
                logger.info("NEARBY", "Auto-accepting outgoing connection to $peerNodeId ($endpointId)")
                connectionsClient.acceptConnection(endpointId, payloadCallback)
                    .addOnFailureListener { e ->
                        logger.error("NEARBY", "Failed to accept initiated connection to $endpointId: ${e.message}")
                        initiatedConnections.remove(endpointId)
                        endpointToNodeId.remove(endpointId)
                        if (nodeIdToEndpoint[peerNodeId] == endpointId) {
                            nodeIdToEndpoint.remove(peerNodeId)
                        }
                        _neighbors.value = _neighbors.value.filter { it.nodeId != peerNodeId }
                    }
            } else {
                // Incoming connection requested by peer: prompt the user with popup
                updateNeighbor(peerNodeId, endpointId, NeighborStatus.CONNECTING, isConnected = false)
                _pendingIncomingConnection.value = PendingIncomingConnection(
                    endpointId = endpointId,
                    endpointName = devName,
                    peerNodeId = peerNodeId,
                    authenticationDigits = info.authenticationDigits
                )
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            initiatedConnections.remove(endpointId)
            val peerNodeId = endpointToNodeId[endpointId]
            if (resolution.status.isSuccess || resolution.status.statusCode == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                logger.info("NEARBY", "Connection successfully established with node ${peerNodeId?.value} (endpoint $endpointId)")
                if (peerNodeId != null) {
                    updateNeighbor(peerNodeId, endpointId, NeighborStatus.CONNECTED, isConnected = true)
                    onNeighborConnectedCallback?.invoke(peerNodeId)
                }
            } else {
                logger.warn("NEARBY", "Connection to endpoint $endpointId (${peerNodeId?.value}) resolved with status: ${resolution.status.statusCode}")
                if (peerNodeId != null) {
                    if (nodeIdToEndpoint[peerNodeId] == endpointId) {
                        nodeIdToEndpoint.remove(peerNodeId)
                    }
                    endpointToNodeId.remove(endpointId)
                    _neighbors.value = _neighbors.value.filter { it.nodeId != peerNodeId }
                    onNeighborLostCallback?.invoke(peerNodeId)
                    refreshDiscovery()
                }
            }
            if (_pendingIncomingConnection.value?.endpointId == endpointId) {
                _pendingIncomingConnection.value = null
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerNodeId = endpointToNodeId.remove(endpointId)
            logger.info("NEARBY", "Disconnected from endpoint $endpointId (${peerNodeId?.value})")
            if (peerNodeId != null) {
                if (nodeIdToEndpoint[peerNodeId] == endpointId) {
                    nodeIdToEndpoint.remove(peerNodeId)
                }
                // DYNAMIC CLEANUP: Remove the disconnected device completely from nearby devices list
                _neighbors.value = _neighbors.value.filter { it.nodeId != peerNodeId }
                onNeighborLostCallback?.invoke(peerNodeId)
            }
            if (_pendingIncomingConnection.value?.endpointId == endpointId) {
                _pendingIncomingConnection.value = null
            }
            // Automatically refresh discovery so active devices can be immediately rediscovered
            refreshDiscovery()
        }
    }

    fun acceptConnection(endpointId: String) {
        if (_pendingIncomingConnection.value?.endpointId == endpointId) {
            _pendingIncomingConnection.value = null
        }
        connectionsClient.acceptConnection(endpointId, payloadCallback)
            .addOnSuccessListener {
                logger.info("NEARBY", "Accepted incoming connection request from endpoint $endpointId")
            }
            .addOnFailureListener { e ->
                logger.error("NEARBY", "Failed to accept incoming connection from $endpointId: ${e.message}")
                val peerNodeId = endpointToNodeId.remove(endpointId)
                if (peerNodeId != null) {
                    if (nodeIdToEndpoint[peerNodeId] == endpointId) {
                        nodeIdToEndpoint.remove(peerNodeId)
                    }
                    _neighbors.value = _neighbors.value.filter { it.nodeId != peerNodeId }
                    onNeighborLostCallback?.invoke(peerNodeId)
                }
            }
    }

    fun rejectConnection(endpointId: String) {
        if (_pendingIncomingConnection.value?.endpointId == endpointId) {
            _pendingIncomingConnection.value = null
        }
        connectionsClient.rejectConnection(endpointId)
            .addOnSuccessListener {
                logger.info("NEARBY", "Rejected incoming connection request from endpoint $endpointId")
            }
            .addOnFailureListener { e ->
                logger.error("NEARBY", "Failed to reject incoming connection from $endpointId: ${e.message}")
            }
        val peerNodeId = endpointToNodeId.remove(endpointId)
        if (peerNodeId != null) {
            if (nodeIdToEndpoint[peerNodeId] == endpointId) {
                nodeIdToEndpoint.remove(peerNodeId)
            }
            _neighbors.value = _neighbors.value.filter { it.nodeId != peerNodeId }
            onNeighborLostCallback?.invoke(peerNodeId)
        }
    }

    fun connectToNeighbor(nodeId: NodeId): Boolean {
        val endpointId = nodeIdToEndpoint[nodeId] ?: run {
            logger.warn("NEARBY", "Cannot connect to $nodeId: endpoint not found in discovery map")
            refreshDiscovery()
            return false
        }
        updateNeighbor(nodeId, endpointId, NeighborStatus.CONNECTING, isConnected = false)
        initiatedConnections.add(endpointId)
        connectionsClient.requestConnection(
            localEndpointName,
            endpointId,
            connectionLifecycleCallback
        ).addOnSuccessListener {
            logger.info("NEARBY", "Connection requested to node $nodeId (endpoint $endpointId)")
        }.addOnFailureListener { e ->
            logger.error("NEARBY", "Failed to request connection to node $nodeId ($endpointId): ${e.message}")
            initiatedConnections.remove(endpointId)
            if (nodeIdToEndpoint[nodeId] == endpointId) {
                nodeIdToEndpoint.remove(nodeId)
            }
            endpointToNodeId.remove(endpointId)
            _neighbors.value = _neighbors.value.filter { it.nodeId != nodeId }
            refreshDiscovery()
        }

        // Safety timeout: if after 25s the connection is still in CONNECTING state, abort and refresh
        scope.launch {
            delay(25_000L)
            val current = _neighbors.value.firstOrNull { it.nodeId == nodeId }
            if (current?.status == NeighborStatus.CONNECTING) {
                logger.warn("NEARBY", "Connection attempt to $nodeId ($endpointId) timed out after 25s")
                initiatedConnections.remove(endpointId)
                if (nodeIdToEndpoint[nodeId] == endpointId) {
                    nodeIdToEndpoint.remove(nodeId)
                }
                endpointToNodeId.remove(endpointId)
                _neighbors.value = _neighbors.value.filter { it.nodeId != nodeId }
                refreshDiscovery()
            }
        }
        return true
    }

    fun disconnectFromNeighbor(nodeId: NodeId): Boolean {
        val endpointId = nodeIdToEndpoint[nodeId] ?: return false
        try {
            connectionsClient.disconnectFromEndpoint(endpointId)
        } catch (e: Exception) {
            logger.warn("NEARBY", "Error disconnecting from endpoint $endpointId: ${e.message}")
        }
        endpointToNodeId.remove(endpointId)
        nodeIdToEndpoint.remove(nodeId)
        _neighbors.value = _neighbors.value.filter { it.nodeId != nodeId }
        onNeighborLostCallback?.invoke(nodeId)
        refreshDiscovery()
        return true
    }

    override suspend fun sendToNeighbor(neighborId: NodeId, packet: Packet): Boolean {
        val endpointId = nodeIdToEndpoint[neighborId] ?: run {
            logger.warn("NEARBY", "Cannot send packet ${packet.type} to $neighborId: endpoint not found")
            return false
        }
        val bytes = try {
            PacketCodec.encode(packet)
        } catch (e: Exception) {
            logger.error("NEARBY", "Failed to encode packet ${packet.type}: ${e.message}")
            return false
        }
        val payload = Payload.fromBytes(bytes)
        return try {
            connectionsClient.sendPayload(endpointId, payload)
            true
        } catch (e: Exception) {
            logger.error("NEARBY", "Failed to send payload to $neighborId ($endpointId): ${e.message}")
            false
        }
    }

    override suspend fun broadcastToNeighbors(packet: Packet, excluding: NodeId?) {
        val targetEndpoints = _neighbors.value
            .filter { it.isConnected && it.nodeId != excluding }
            .mapNotNull { nodeIdToEndpoint[it.nodeId] }
        if (targetEndpoints.isEmpty()) return
        val bytes = try {
            PacketCodec.encode(packet)
        } catch (e: Exception) {
            logger.error("NEARBY", "Failed to encode broadcast packet: ${e.message}")
            return
        }
        val payload = Payload.fromBytes(bytes)
        try {
            connectionsClient.sendPayload(targetEndpoints, payload)
        } catch (e: Exception) {
            logger.error("NEARBY", "Failed to broadcast payload: ${e.message}")
        }
    }

    /**
     * Sends native Nearby Connections file payload.
     */
    fun sendNearbyFilePayload(destNodeId: NodeId, transferId: UUID, file: File): Long? {
        val endpointId = nodeIdToEndpoint[destNodeId] ?: run {
            logger.error("NEARBY", "sendNearbyFilePayload: no endpointId found for destination $destNodeId")
            return null
        }
        val filePayload = try {
            Payload.fromFile(file)
        } catch (e: Exception) {
            logger.error("NEARBY", "sendNearbyFilePayload: Failed to create Payload.fromFile: ${e.message}")
            return null
        }
        val payloadId = filePayload.id
        payloadToTransfer[payloadId] = transferId
        transferToPayload[transferId] = payloadId

        // Send header packet so receiver maps payloadId to transferId
        val headerBytes = ByteBuffer.allocate(16)
            .putLong(payloadId)
            .putLong(file.length())
            .array()
        val headerPacket = Packet(
            type = PacketType.TRANSFER_FILE_PAYLOAD,
            transferId = transferId,
            sourceNodeId = selfNodeId,
            destinationNodeId = destNodeId,
            payload = headerBytes
        )
        scope.launch {
            sendToNeighbor(destNodeId, headerPacket)
        }

        try {
            connectionsClient.sendPayload(endpointId, filePayload)
            logger.info("NEARBY", "sendNearbyFilePayload: File payload $payloadId queued for transfer $transferId to $endpointId")
            return payloadId
        } catch (e: Exception) {
            logger.error("NEARBY", "sendNearbyFilePayload: Failed to queue file payload: ${e.message}")
            payloadToTransfer.remove(payloadId)
            transferToPayload.remove(transferId)
            return null
        }
    }

    /**
     * Safely opens an InputStream from an incoming Nearby Connections file payload.
     * Uses asUri() with ContentResolver first to comply with Android Scoped Storage,
     * with fallbacks to ParcelFileDescriptor and java.io.File.
     */
    fun openPayloadInputStream(payload: Payload): java.io.InputStream? {
        val pFile = payload.asFile() ?: return null

        // 1. Preferred modern Android approach: content URI granted by Google Play services
        val uri = pFile.asUri()
        if (uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    logger.info("NEARBY", "Opened payload stream using content URI: $uri")
                    return stream
                }
            } catch (e: Exception) {
                logger.warn("NEARBY", "Failed to open inputStream from payload content URI $uri: ${e.message}")
            }
        }

        // 2. Secondary approach: ParcelFileDescriptor
        try {
            val pfd = pFile.asParcelFileDescriptor()
            val stream = java.io.FileInputStream(pfd.fileDescriptor)
            logger.info("NEARBY", "Opened payload stream using parcel file descriptor")
            return stream
        } catch (e: Exception) {
            logger.warn("NEARBY", "Failed to open inputStream from parcel file descriptor: ${e.message}")
        }

        // 3. Fallback: java.io.File for older Android versions
        try {
            @Suppress("DEPRECATION")
            val javaFile = pFile.asJavaFile()
            if (javaFile != null && javaFile.exists() && javaFile.canRead()) {
                val stream = java.io.FileInputStream(javaFile)
                logger.info("NEARBY", "Opened payload stream using java.io.File: ${javaFile.absolutePath}")
                return stream
            }
        } catch (e: Exception) {
            logger.warn("NEARBY", "Failed to open inputStream from java.io.File: ${e.message}")
        }

        return null
    }

    fun cancelFilePayload(transferId: UUID) {
        val payloadId = transferToPayload.remove(transferId) ?: return
        payloadToTransfer.remove(payloadId)
        try {
            connectionsClient.cancelPayload(payloadId)
        } catch (e: Exception) {
            logger.warn("NEARBY", "Failed to cancel payload $payloadId: ${e.message}")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val packet = try {
                        PacketCodec.decode(bytes)
                    } catch (e: Exception) {
                        logger.warn("NEARBY", "Malformed packet received from $endpointId: ${e.message}")
                        return
                    }
                    if (packet.type == PacketType.TRANSFER_FILE_PAYLOAD && packet.transferId != null) {
                        try {
                            val buf = ByteBuffer.wrap(packet.payload)
                            val payloadId = buf.long
                            val fileSize = buf.long
                            payloadToTransfer[payloadId] = packet.transferId
                            transferToPayload[packet.transferId] = payloadId
                            logger.info("NEARBY", "Mapped incoming payloadId=$payloadId to transferId=${packet.transferId} (size=$fileSize)")
                        } catch (e: Exception) {
                            logger.error("NEARBY", "Failed to decode TRANSFER_FILE_PAYLOAD: ${e.message}")
                        }
                    }
                    scope.launch {
                        _incomingPackets.emit(packet)
                    }
                }
                Payload.Type.FILE -> {
                    logger.info("NEARBY", "Incoming FILE payload received: payloadId=${payload.id} from endpoint $endpointId")
                    incomingFilePayloads[payload.id] = payload
                }
                else -> Unit
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val payloadId = update.payloadId
            val transferId = payloadToTransfer[payloadId]
            val payload = incomingFilePayloads[payloadId]
            if (transferId != null) {
                fileTransferUpdateListener?.invoke(transferId, update, payload)
            }
            if (update.status == PayloadTransferUpdate.Status.SUCCESS ||
                update.status == PayloadTransferUpdate.Status.FAILURE ||
                update.status == PayloadTransferUpdate.Status.CANCELED
            ) {
                if (update.status != PayloadTransferUpdate.Status.SUCCESS) {
                    incomingFilePayloads.remove(payloadId)
                    payloadToTransfer.remove(payloadId)
                }
            }
        }
    }

    private fun updateNeighbor(
        nodeId: NodeId,
        endpointId: String?,
        status: NeighborStatus,
        isConnected: Boolean
    ) {
        val current = _neighbors.value.toMutableList()
        val index = current.indexOfFirst { it.nodeId == nodeId }
        val updated = Neighbor(
            nodeId = nodeId,
            rssi = 0,
            lastSeenMillis = System.currentTimeMillis(),
            isConnected = isConnected,
            status = status,
            deviceAddress = endpointId
        )
        if (index >= 0) {
            current[index] = updated
        } else {
            current.add(updated)
        }
        _neighbors.value = current
    }

    private fun parseEndpointName(name: String): Pair<NodeId, String> {
        val parts = name.split("|", limit = 2)
        val parsedId = NodeId.fromStringOrNull(parts[0]) ?: NodeId(parts[0].take(8).padEnd(8, '0'))
        val devName = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else "Android Device"
        return Pair(parsedId, devName)
    }
}
