package com.example.multihoprelay.networking

import com.example.multihoprelay.data.settings.RelaySettings
import com.example.multihoprelay.fakes.FakeRelayQueueDao
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import com.example.multihoprelay.model.RelayPacketState
import com.example.multihoprelay.simulation.SimulatedIdentityProvider
import com.example.multihoprelay.simulation.SimulatedNetwork
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * Verifies [RelayManager]'s core contract: it forwards packets addressed to other nodes only
 * when Relay Mode is on, never inspects/decrypts payloads, persists packets before forwarding
 * (store-and-forward), retries when the next hop is temporarily unavailable, and enforces the
 * configured limits (hop count, storage, packet age).
 */
@RunWith(RobolectricTestRunner::class)
class RelayManagerTest {

    private fun controlPacket(source: NodeId, destination: NodeId, hopCount: Int = 0, payload: ByteArray = "opaque-ciphertext".toByteArray()) =
        Packet(
            type = PacketType.CHUNK_DATA,
            transferId = UUID.randomUUID(),
            sourceNodeId = source,
            destinationNodeId = destination,
            hopCount = hopCount,
            payload = payload
        )

    @Test
    fun `relay forwards a packet addressed to another node when relay mode is on`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val logger = MeshLogger()

        val a = SimulatedIdentityProvider("A")
        val b = SimulatedIdentityProvider("B")
        val c = SimulatedIdentityProvider("C")
        val transportA = network.addNode(a.nodeId())
        val transportB = network.addNode(b.nodeId())
        val transportC = network.addNode(c.nodeId())
        network.connect(a.nodeId(), b.nodeId())
        network.connect(b.nodeId(), c.nodeId())

        val routingB = RoutingManager(transportB, b, this, logger)
        routingB.start()
        routingB.onNeighborDiscovered(c.nodeId()) // B knows C directly

        val dao = FakeRelayQueueDao()
        val settingsFlow = MutableStateFlow(RelaySettings(relayModeEnabled = true))
        val relayB = RelayManager(transportB, routingB, dao, settingsFlow, this, logger)
        relayB.start()
        testScheduler.runCurrent()

        var receivedAtC: Packet? = null
        val collectJob = launch { transportC.incomingPackets.collect { receivedAtC = it } }

        val packet = controlPacket(a.nodeId(), c.nodeId())
        transportA.sendToNeighbor(b.nodeId(), packet)
        repeat(10) { testScheduler.runCurrent() }

        assertEquals(packet.packetId, receivedAtC?.packetId)
        assertEquals(1, relayB.stats.value.packetsForwarded)
        collectJob.cancel()
        relayB.stop()
        routingB.stop()
    }

    @Test
    fun `relay does nothing when relay mode is off`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val logger = MeshLogger()

        val a = SimulatedIdentityProvider("A")
        val b = SimulatedIdentityProvider("B")
        val c = SimulatedIdentityProvider("C")
        val transportA = network.addNode(a.nodeId())
        val transportB = network.addNode(b.nodeId())
        network.addNode(c.nodeId())
        network.connect(a.nodeId(), b.nodeId())
        network.connect(b.nodeId(), c.nodeId())

        val routingB = RoutingManager(transportB, b, this, logger)
        routingB.start()
        routingB.onNeighborDiscovered(c.nodeId())

        val dao = FakeRelayQueueDao()
        val settingsFlow = MutableStateFlow(RelaySettings(relayModeEnabled = false)) // OFF
        val relayB = RelayManager(transportB, routingB, dao, settingsFlow, this, logger)
        relayB.start()
        testScheduler.runCurrent()

        val packet = controlPacket(a.nodeId(), c.nodeId())
        transportA.sendToNeighbor(b.nodeId(), packet)
        repeat(10) { testScheduler.runCurrent() }

        assertEquals(0, relayB.stats.value.packetsForwarded)
        assertEquals(1, relayB.stats.value.packetsDropped)
        assertTrue("nothing should be queued while relay mode is off", dao.snapshotAll().isEmpty())
        relayB.stop()
        routingB.stop()
    }

    @Test
    fun `store-and-forward - packet is queued and retried after the next hop becomes available`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val logger = MeshLogger()

        val a = SimulatedIdentityProvider("A")
        val b = SimulatedIdentityProvider("B")
        val c = SimulatedIdentityProvider("C")
        val transportA = network.addNode(a.nodeId())
        val transportB = network.addNode(b.nodeId())
        val transportC = network.addNode(c.nodeId())
        network.connect(a.nodeId(), b.nodeId())
        // B and C are NOT yet connected - simulates C being temporarily out of range.

        val routingB = RoutingManager(transportB, b, this, logger)
        routingB.start()

        val dao = FakeRelayQueueDao()
        val settingsFlow = MutableStateFlow(RelaySettings(relayModeEnabled = true, maxPacketAgeMillis = 60_000L))
        val relayB = RelayManager(transportB, routingB, dao, settingsFlow, this, logger)
        relayB.start()
        testScheduler.runCurrent()

        val packet = controlPacket(a.nodeId(), c.nodeId())
        transportA.sendToNeighbor(b.nodeId(), packet)
        repeat(10) { testScheduler.runCurrent() }

        // Packet should be persisted (queued), not forwarded yet, since B<->C isn't connected.
        assertEquals(1, dao.countByState(RelayPacketState.QUEUED))
        assertEquals(0, relayB.stats.value.packetsForwarded)

        var receivedAtC: Packet? = null
        val collectJob = launch { transportC.incomingPackets.collect { receivedAtC = it } }

        // Now C comes into range of B.
        network.connect(b.nodeId(), c.nodeId())
        routingB.onNeighborDiscovered(c.nodeId())

        // Advance past the 5-second retry tick inside RelayManager.
        advanceTimeBy(6_000L)
        repeat(10) { testScheduler.runCurrent() }

        assertEquals(packet.packetId, receivedAtC?.packetId)
        assertEquals(0, dao.countByState(RelayPacketState.QUEUED)) // removed after successful forward
        collectJob.cancel()
        relayB.stop()
        routingB.stop()
    }

    @Test
    fun `packet exceeding configured max hops is dropped, not queued`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val logger = MeshLogger()

        val a = SimulatedIdentityProvider("A")
        val b = SimulatedIdentityProvider("B")
        val c = SimulatedIdentityProvider("C")
        val transportA = network.addNode(a.nodeId())
        val transportB = network.addNode(b.nodeId())
        network.addNode(c.nodeId())
        network.connect(a.nodeId(), b.nodeId())
        network.connect(b.nodeId(), c.nodeId())

        val routingB = RoutingManager(transportB, b, this, logger)
        routingB.start()

        val dao = FakeRelayQueueDao()
        val settingsFlow = MutableStateFlow(RelaySettings(relayModeEnabled = true, maxHops = 3))
        val relayB = RelayManager(transportB, routingB, dao, settingsFlow, this, logger)
        relayB.start()
        testScheduler.runCurrent()

        val packet = controlPacket(a.nodeId(), c.nodeId(), hopCount = 3) // already at the limit
        transportA.sendToNeighbor(b.nodeId(), packet)
        repeat(10) { testScheduler.runCurrent() }

        assertEquals(0, relayB.stats.value.packetsForwarded)
        assertEquals(1, relayB.stats.value.packetsDropped)
        assertTrue(dao.snapshotAll().isEmpty())
        relayB.stop()
        routingB.stop()
    }

    @Test
    fun `relay storage limit rejects new packets once full`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val logger = MeshLogger()

        val a = SimulatedIdentityProvider("A")
        val b = SimulatedIdentityProvider("B")
        val c = SimulatedIdentityProvider("C")
        val transportA = network.addNode(a.nodeId())
        val transportB = network.addNode(b.nodeId())
        network.addNode(c.nodeId())
        network.connect(a.nodeId(), b.nodeId())
        // B<->C intentionally not connected, so packets stay queued (consuming storage).

        val routingB = RoutingManager(transportB, b, this, logger)
        routingB.start()

        val dao = FakeRelayQueueDao()
        // Tiny storage limit: only enough for one ~20-byte payload.
        val settingsFlow = MutableStateFlow(RelaySettings(relayModeEnabled = true, maxRelayStorageBytes = 25))
        val relayB = RelayManager(transportB, routingB, dao, settingsFlow, this, logger)
        relayB.start()
        testScheduler.runCurrent()

        val first = controlPacket(a.nodeId(), c.nodeId(), payload = ByteArray(20))
        val second = controlPacket(a.nodeId(), c.nodeId(), payload = ByteArray(20))
        transportA.sendToNeighbor(b.nodeId(), first)
        repeat(10) { testScheduler.runCurrent() }
        transportA.sendToNeighbor(b.nodeId(), second)
        repeat(10) { testScheduler.runCurrent() }

        assertEquals(1, dao.snapshotAll().size)
        assertTrue("second packet should be dropped due to storage limit", relayB.stats.value.packetsDropped >= 1)
        relayB.stop()
        routingB.stop()
    }

    @Test
    fun `duplicate packet id is not queued twice (loop prevention via dedup)`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val logger = MeshLogger()

        val a = SimulatedIdentityProvider("A")
        val b = SimulatedIdentityProvider("B")
        val c = SimulatedIdentityProvider("C")
        val transportA = network.addNode(a.nodeId())
        val transportB = network.addNode(b.nodeId())
        network.addNode(c.nodeId())
        network.connect(a.nodeId(), b.nodeId())
        // B<->C not connected so the packet stays queued and can be "replayed" without being forwarded away.

        val routingB = RoutingManager(transportB, b, this, logger)
        routingB.start()

        val dao = FakeRelayQueueDao()
        val settingsFlow = MutableStateFlow(RelaySettings(relayModeEnabled = true))
        val relayB = RelayManager(transportB, routingB, dao, settingsFlow, this, logger)
        relayB.start()
        testScheduler.runCurrent()

        val packet = controlPacket(a.nodeId(), c.nodeId())
        transportA.sendToNeighbor(b.nodeId(), packet) // first delivery
        repeat(10) { testScheduler.runCurrent() }
        transportA.sendToNeighbor(b.nodeId(), packet) // exact same packet "replayed"
        repeat(10) { testScheduler.runCurrent() }

        assertEquals(1, dao.snapshotAll().size)
        relayB.stop()
        routingB.stop()
    }
}
