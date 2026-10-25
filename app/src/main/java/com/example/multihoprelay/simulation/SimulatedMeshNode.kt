package com.example.multihoprelay.simulation

import android.content.Context
import androidx.room.Room
import com.example.multihoprelay.data.database.AppDatabase
import com.example.multihoprelay.data.settings.RelaySettings
import com.example.multihoprelay.data.storage.FileStorage
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.networking.RelayManager
import com.example.multihoprelay.networking.RelayStats
import com.example.multihoprelay.networking.RoutingManager
import com.example.multihoprelay.networking.TransferManager
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A fully-wired virtual node used by the in-app "Test/Simulation Mode" screen: the exact same
 * [RoutingManager] / [RelayManager] / [TransferManager] classes used for real BLE, running
 * against a [SimulatedMeshTransport] instead. Each simulated node gets its own in-memory Room
 * database instance (so relay queues/transfer state are isolated per node, exactly like
 * separate physical phones would be) and its own software identity via [SimulatedIdentityProvider].
 */
class SimulatedMeshNode(
    context: Context,
    val label: String,
    network: SimulatedNetwork,
    relayModeEnabled: Boolean = false
) {
    val identity = SimulatedIdentityProvider(label)
    val nodeId: NodeId = identity.nodeId()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job)
    private val logger = MeshLogger(capacity = 300)
    val transport = network.addNode(nodeId)

    private val db = Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
    private val fileStorage = FileStorage(context.applicationContext)

    private val _relaySettings = MutableStateFlow(RelaySettings(relayModeEnabled = relayModeEnabled))
    val relaySettingsFlow: StateFlow<RelaySettings> = _relaySettings

    val routingManager = RoutingManager(transport, identity, scope, logger)
    val relayManager = RelayManager(transport, routingManager, db.relayQueueDao(), _relaySettings, scope, logger)
    val transferManager = TransferManager(transport, routingManager, identity, db.transferDao(), fileStorage, scope, logger)

    val relayStats: StateFlow<RelayStats> get() = relayManager.stats
    val logs get() = logger.entries

    fun setRelayModeEnabled(enabled: Boolean) {
        _relaySettings.value = _relaySettings.value.copy(relayModeEnabled = enabled)
    }

    fun start() {
        routingManager.start()
        relayManager.start()
        transferManager.start()
    }

    fun stop() {
        routingManager.stop()
        relayManager.stop()
        job.cancel()
    }
}
