package com.example.multihoprelay.simulation

import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Neighbor
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.networking.MeshTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory implementation of [MeshTransport] used by the Test/Simulation Mode screen
 * (and by unit tests) to exercise [com.example.multihoprelay.networking.RoutingManager],
 * `RelayManager`, and `TransferManager` end-to-end WITHOUT any real BLE radios - deterministic,
 * fast, and runnable on a single machine or in CI.
 *
 * Deliberately kept separate from [com.example.multihoprelay.bluetooth.BleConnectionManager]:
 * production networking code never imports anything from this `simulation` package, and this
 * package never imports `android.bluetooth.*`.
 */
class SimulatedMeshTransport(override val selfNodeId: NodeId, private val network: SimulatedNetwork) : MeshTransport {

    private val _neighbors = MutableStateFlow<List<Neighbor>>(emptyList())
    override val neighbors: StateFlow<List<Neighbor>> = _neighbors

    private val _incomingPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 256)
    override val incomingPackets: SharedFlow<Packet> = _incomingPackets

    internal fun setNeighbors(ids: List<NodeId>) {
        _neighbors.value = ids.map { Neighbor(it, rssi = -50, isConnected = true) }
    }

    internal suspend fun deliver(packet: Packet) {
        _incomingPackets.emit(packet)
    }

    override suspend fun sendToNeighbor(neighborId: NodeId, packet: Packet): Boolean =
        network.send(selfNodeId, neighborId, packet)

    override suspend fun broadcastToNeighbors(packet: Packet, excluding: NodeId?) {
        _neighbors.value.filter { it.nodeId != excluding }.forEach { neighbor ->
            network.send(selfNodeId, neighbor.nodeId, packet)
        }
    }
}

/**
 * The shared "radio medium" for [SimulatedMeshTransport] instances: an explicit adjacency
 * graph (which pairs of simulated nodes are "in range" of each other), used to model
 * topologies like `A - B - C` (A and C not directly reachable) for the demo scenario.
 */
class SimulatedNetwork {
    private val nodes = ConcurrentHashMap<NodeId, SimulatedMeshTransport>()
    private val adjacency = ConcurrentHashMap<NodeId, MutableSet<NodeId>>()

    /** Optional artificial packet-loss rate (0.0-1.0) and link latency, for testing store-and-forward. */
    var packetLossRate: Double = 0.0
    var simulatedLatencyMillis: Long = 20L

    fun addNode(nodeId: NodeId): SimulatedMeshTransport {
        val transport = SimulatedMeshTransport(nodeId, this)
        nodes[nodeId] = transport
        adjacency.putIfAbsent(nodeId, mutableSetOf())
        return transport
    }

    fun removeNode(nodeId: NodeId) {
        nodes.remove(nodeId)
        adjacency.remove(nodeId)
        adjacency.values.forEach { it.remove(nodeId) }
        refreshAllNeighborViews()
    }

    /** Declares [a] and [b] as within radio range of each other (symmetric link). */
    fun connect(a: NodeId, b: NodeId) {
        adjacency.getOrPut(a) { mutableSetOf() }.add(b)
        adjacency.getOrPut(b) { mutableSetOf() }.add(a)
        refreshAllNeighborViews()
    }

    /** Simulates a node moving out of range / a relay disappearing. */
    fun disconnect(a: NodeId, b: NodeId) {
        adjacency[a]?.remove(b)
        adjacency[b]?.remove(a)
        refreshAllNeighborViews()
    }

    fun isDirectlyConnected(a: NodeId, b: NodeId): Boolean = adjacency[a]?.contains(b) == true

    private fun refreshAllNeighborViews() {
        nodes.forEach { (id, transport) -> transport.setNeighbors((adjacency[id] ?: emptySet()).toList()) }
    }

    suspend fun send(from: NodeId, to: NodeId, packet: Packet): Boolean {
        if (!isDirectlyConnected(from, to)) return false
        if (packetLossRate > 0.0 && Math.random() < packetLossRate) return true // "sent" but dropped in transit
        val target = nodes[to] ?: return false
        if (simulatedLatencyMillis > 0) kotlinx.coroutines.delay(simulatedLatencyMillis)
        target.deliver(packet)
        return true
    }
}
