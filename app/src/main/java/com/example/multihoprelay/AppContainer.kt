package com.example.multihoprelay

import android.content.Context
import com.example.multihoprelay.bluetooth.BleConnectionManager
import com.example.multihoprelay.crypto.KeyManager
import com.example.multihoprelay.crypto.KeyManagerIdentityProvider
import com.example.multihoprelay.data.database.AppDatabase
import com.example.multihoprelay.data.settings.SettingsRepository
import com.example.multihoprelay.data.storage.FileStorage
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.networking.RelayManager
import com.example.multihoprelay.networking.RoutingManager
import com.example.multihoprelay.networking.TransferManager
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container (no DI framework needed for a project this size). Lives
 * for the process lifetime, created once in [MultiHopRelayApp]. Owns the single, real
 * [BleConnectionManager]-backed mesh stack that all screens observe.
 */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob())

    val logger = MeshLogger()
    val keyManager = KeyManager(context)
    val settingsRepository = SettingsRepository(context)
    val database = AppDatabase.getInstance(context)
    val fileStorage = FileStorage(context)

    /** Ensures a node identity exists; call once at app startup before any mesh operation. */
    fun ensureIdentity(): NodeId {
        if (!keyManager.hasIdentity()) {
            val id = keyManager.generateIdentity()
            logger.info("IDENTITY", "Generated new node identity: $id")
            return id
        }
        return keyManager.getNodeId()!!
    }

    private val identityProvider by lazy { KeyManagerIdentityProvider(keyManager) }

    private var _connectionManager: BleConnectionManager? = null
    private var _routingManager: RoutingManager? = null
    private var _relayManager: RelayManager? = null
    private var _transferManager: TransferManager? = null

    val connectionManager: BleConnectionManager
        get() = _connectionManager ?: buildMeshStack().connectionManager

    val routingManager: RoutingManager
        get() = _routingManager ?: buildMeshStack().routingManager

    val relayManager: RelayManager
        get() = _relayManager ?: buildMeshStack().relayManager

    val transferManager: TransferManager
        get() = _transferManager ?: buildMeshStack().transferManager

    private data class MeshStack(
        val connectionManager: BleConnectionManager,
        val routingManager: RoutingManager,
        val relayManager: RelayManager,
        val transferManager: TransferManager
    )

    private fun buildMeshStack(): MeshStack {
        val nodeId = ensureIdentity()
        val connMgr = BleConnectionManager(context, nodeId, appScope, logger)
        val routing = RoutingManager(connMgr, identityProvider, appScope, logger)
        val relay = RelayManager(connMgr, routing, database.relayQueueDao(), settingsRepository.relaySettingsFlow, appScope, logger)
        val transfer = TransferManager(connMgr, routing, identityProvider, database.transferDao(), fileStorage, appScope, logger)
        connMgr.setOnNeighborLostListener { lostNodeId -> routing.onNeighborLost(lostNodeId) }
        _connectionManager = connMgr
        _routingManager = routing
        _relayManager = relay
        _transferManager = transfer
        return MeshStack(connMgr, routing, relay, transfer)
    }

    private var meshStarted = false

    /** Starts BLE advertising/scanning + routing/relay/transfer processing. Requires permissions already granted. */
    fun startMesh() {
        if (meshStarted) return
        meshStarted = true
        val stack = buildMeshStack()
        stack.connectionManager.start()
        stack.routingManager.start()
        stack.relayManager.start()
        stack.transferManager.start()
        appScope.launch {
            stack.connectionManager.neighbors.collect { list ->
                list.filter { it.isConnected }.forEach { stack.routingManager.onNeighborDiscovered(it.nodeId) }
            }
        }
        logger.info("APP", "Mesh stack started for node ${nodeId()}")
    }

    fun stopMesh() {
        if (!meshStarted) return
        meshStarted = false
        _connectionManager?.stop()
        _routingManager?.stop()
        _relayManager?.stop()
        logger.info("APP", "Mesh stack stopped")
    }

    fun nodeId(): NodeId = keyManager.getNodeId() ?: ensureIdentity()

    companion object {
        @Volatile private var instance: AppContainer? = null
        fun getInstance(context: Context): AppContainer =
            instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
    }
}
