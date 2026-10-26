package com.example.multihoprelay.model

/**
 * Represents a node reachable either directly (1-hop neighbor) or indirectly
 * through one or more intermediate relay nodes.
 */
data class ReachableNode(
    val nodeId: NodeId,
    val deviceName: String = "",
    val isDirect: Boolean,
    val isConnected: Boolean,
    val nextHop: NodeId?,
    val hopCount: Int,
    val fullPath: List<NodeId> = emptyList(),
    val isAvailable: Boolean = true
) {
    val statusText: String
        get() = when {
            isDirect && isConnected -> "Direct · 1 hop"
            isDirect && !isConnected -> "Direct · Discovered"
            nextHop != null && isAvailable -> "Via Node ${nextHop.value} · $hopCount hops"
            else -> "Route unavailable"
        }

    val displayName: String
        get() = if (deviceName.isNotBlank()) "Node ${nodeId.value} ($deviceName)" else "Node ${nodeId.value}"
}
