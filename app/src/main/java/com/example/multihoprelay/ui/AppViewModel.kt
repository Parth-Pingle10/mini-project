package com.example.multihoprelay.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.multihoprelay.AppContainer
import com.example.multihoprelay.data.database.entity.TransferEntity
import com.example.multihoprelay.data.settings.RelaySettings
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.PendingIncomingTransfer
import com.example.multihoprelay.model.Route
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferInfo
import com.example.multihoprelay.model.TransferState
import com.example.multihoprelay.networking.RelayStats
import com.example.multihoprelay.util.LogEntry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as com.example.multihoprelay.MultiHopRelayApp).container

    val pendingIncomingRequest: StateFlow<PendingIncomingTransfer?> by lazy {
        container.transferManager.pendingIncomingRequest
    }

    init {
        viewModelScope.launch {
            container.transferManager.pendingIncomingRequest.collect { req ->
                if (req != null) {
                    container.logger.info("TRANSFER", "INCOMING_REQUEST_COLLECTED: transferId=${req.transferId} file=${req.fileName}")
                }
            }
        }
    }

    val nodeId: NodeId get() = container.nodeId()
    val publicKeyBase64: String get() = container.keyManager.getPublicKeyBase64()

    val neighbors: StateFlow<List<com.example.multihoprelay.model.Neighbor>> by lazy {
        container.connectionManager.neighbors
    }

    val isScanning: StateFlow<Boolean> by lazy {
        container.connectionManager.isScanning
    }

    fun refreshNearbyDevices() {
        container.connectionManager.refreshDiscovery()
    }

    fun connectToNeighbor(nodeId: NodeId): Boolean =
        container.connectionManager.connectToNeighbor(nodeId)

    fun disconnectFromNeighbor(nodeId: NodeId): Boolean =
        container.connectionManager.disconnectFromNeighbor(nodeId)

    val routes: StateFlow<Map<NodeId, Route>> by lazy {
        container.routingManager.routesFlow
    }

    val relayStats: StateFlow<RelayStats> by lazy { container.relayManager.stats }

    val relaySettings: StateFlow<RelaySettings> = container.settingsRepository.relaySettingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RelaySettings())

    val transfers: StateFlow<List<TransferInfo>> = container.database.transferDao().observeAll()
        .map { list -> list.map { it.toTransferInfo() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntry>> get() = container.logger.entries

    fun logInfo(tag: String, message: String) = container.logger.info(tag, message)

    fun startMesh() = container.startMesh()
    fun stopMesh() = container.stopMesh()

    fun setRelayModeEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.setRelayModeEnabled(enabled) }
    }

    fun setMaxRelayStorageBytes(bytes: Long) {
        viewModelScope.launch { container.settingsRepository.setMaxRelayStorageBytes(bytes) }
    }

    fun setMaxPacketAgeMillis(millis: Long) {
        viewModelScope.launch { container.settingsRepository.setMaxPacketAgeMillis(millis) }
    }

    fun setMaxHops(hops: Int) {
        viewModelScope.launch { container.settingsRepository.setMaxHops(hops) }
    }

    fun setMaxConcurrentRelays(count: Int) {
        viewModelScope.launch { container.settingsRepository.setMaxConcurrentRelays(count) }
    }

    fun setMaxBandwidth(bps: Long) {
        viewModelScope.launch { container.settingsRepository.setMaxBandwidthBytesPerSecond(bps) }
    }

    fun clearRelayCache() {
        viewModelScope.launch { container.relayManager.clearRelayCache() }
    }

    fun clearLogs() = container.logger.clear()

    fun exportLogs(): String = container.logger.exportAsText()

    /** Explicit "Reset Identity" action - user must confirm in the UI first; see SettingsScreen. */
    fun resetIdentity(): NodeId {
        stopMesh()
        val newId = container.keyManager.resetIdentity()
        container.logger.info("IDENTITY", "Identity reset by user - new NodeId: $newId")
        return newId
    }

    fun sendFile(destination: NodeId, file: File, fileName: String): UUID =
        container.transferManager.startSend(destination, file, fileName)

    fun acceptIncomingTransfer(transferId: UUID) {
        viewModelScope.launch {
            container.transferManager.acceptIncomingTransfer(transferId)
        }
    }

    fun rejectIncomingTransfer(transferId: UUID) {
        viewModelScope.launch {
            container.transferManager.rejectIncomingTransfer(transferId)
        }
    }

    fun cancelTransfer(transferId: UUID) = container.transferManager.cancelTransfer(transferId)

    private fun TransferEntity.toTransferInfo(): TransferInfo {
        val route = if (routeCsv.isBlank()) {
            emptyList()
        } else {
            routeCsv.split(",").mapNotNull { NodeId.fromStringOrNull(it.trim()) }
        }
        val safePeerId = NodeId.fromStringOrNull(peerNodeId) ?: NodeId("00000000")
        val safeTransferId = try { UUID.fromString(transferId) } catch (_: Exception) { UUID.randomUUID() }
        return TransferInfo(
            transferId = safeTransferId,
            direction = direction,
            peerNodeId = safePeerId,
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            totalChunks = totalChunks,
            chunksDone = if (state == TransferState.COMPLETED) totalChunks else chunksDone,
            state = state,
            route = route,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            errorMessage = errorMessage,
            outputFilePath = outputFilePath
        )
    }
}
