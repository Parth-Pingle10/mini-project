package com.example.multihoprelay.networking

import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Neighbor
import com.example.multihoprelay.model.Packet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over "send a packet to a 1-hop neighbor and receive packets from 1-hop
 * neighbors". [RoutingManager], [RelayManager] and [TransferManager] are written entirely
 * against this interface and know nothing about BLE, GATT, or the in-memory simulation bus.
 *
 * Two implementations exist:
 *  - [com.example.multihoprelay.bluetooth.BleMeshTransport] - real BLE GATT client/server.
 *  - [com.example.multihoprelay.simulation.SimulatedMeshTransport] - an in-memory bus used
 *    by the Test/Simulation Mode screen and by unit tests, so multi-hop routing and relay
 *    behavior can be exercised deterministically without any physical radios.
 */
interface MeshTransport {

    /** This node's own identifier. */
    val selfNodeId: NodeId

    /** Currently known 1-hop neighbors (BLE-connected or recently advertising/scanned). */
    val neighbors: StateFlow<List<Neighbor>>

    /** Emits every packet received from any directly-connected neighbor, already reassembled. */
    val incomingPackets: Flow<Packet>

    /**
     * Sends [packet] to the directly-connected neighbor [neighborId]. Returns true if the
     * link-layer write succeeded (does not guarantee application-level delivery - that is
     * handled by higher layers via CHUNK_ACK / retry).
     */
    suspend fun sendToNeighbor(neighborId: NodeId, packet: Packet): Boolean

    /** Broadcasts [packet] to all currently connected neighbors (used for RREQ flooding). */
    suspend fun broadcastToNeighbors(packet: Packet, excluding: NodeId? = null)
}
