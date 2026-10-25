package com.example.multihoprelay.model

/** A discovered path from this node to [destination], reachable via [nextHop] within radio range. */
data class Route(
    val destination: NodeId,
    val nextHop: NodeId,
    val hopCount: Int,
    val fullPath: List<NodeId> = emptyList(),
    val discoveredAtMillis: Long = System.currentTimeMillis(),
    val lastConfirmedAtMillis: Long = System.currentTimeMillis()
) {
    fun isExpired(nowMillis: Long, ttlMillis: Long): Boolean =
        nowMillis - lastConfirmedAtMillis > ttlMillis

    companion object {
        const val DEFAULT_ROUTE_TTL_MILLIS = 2 * 60 * 1000L // 2 minutes
    }
}

enum class NeighborStatus {
    DISCOVERED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/** A directly reachable (1-hop) neighbor, discovered via BLE scan/advertisement/connection. */
data class Neighbor(
    val nodeId: NodeId,
    val rssi: Int,
    val lastSeenMillis: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false,
    val status: NeighborStatus = if (isConnected) NeighborStatus.CONNECTED else NeighborStatus.DISCOVERED,
    val deviceAddress: String? = null,
    val protocolVersion: Int = 1
) {
    fun isStale(nowMillis: Long, staleAfterMillis: Long = 15_000L): Boolean =
        nowMillis - lastSeenMillis > staleAfterMillis
}
