package com.example.multihoprelay.networking

import com.example.multihoprelay.simulation.SimulatedIdentityProvider
import com.example.multihoprelay.simulation.SimulatedNetwork
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises multi-hop route discovery end-to-end over [SimulatedNetwork], covering the
 * scenarios called out in the requirements: `A -> B -> C`, `A -> B -> D -> C`, and route
 * failure/rediscovery when an intermediate relay disappears. Uses Robolectric only so that
 * [MeshLogger]'s `android.util.Log` calls resolve (no other Android API is touched).
 */
@RunWith(RobolectricTestRunner::class)
class RoutingManagerTest {

    private class Node(name: String, network: SimulatedNetwork, scope: kotlinx.coroutines.CoroutineScope) {
        val identity = SimulatedIdentityProvider(name)
        val transport = network.addNode(identity.nodeId())
        val logger = MeshLogger()
        val routing = RoutingManager(transport, identity, scope, logger, routeTtlMillis = 60_000L)
        fun start() = routing.start()
        fun stop() = routing.stop()
    }

    @Test
    fun `A discovers a route to C through relay B (three node chain)`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val a = Node("A", network, this)
        val b = Node("B", network, this)
        val c = Node("C", network, this)
        val nodes = listOf(a, b, c)
        nodes.forEach { it.start() }

        network.connect(a.identity.nodeId(), b.identity.nodeId())
        network.connect(b.identity.nodeId(), c.identity.nodeId())
        advanceUntilIdle()

        val route = a.routing.discoverRoute(c.identity.nodeId(), timeoutMillis = 5_000L)
        advanceUntilIdle()

        assertNotNull("A should find a route to C via B", route)
        assertEquals(b.identity.nodeId(), route!!.nextHop)
        assertEquals(listOf(a.identity.nodeId(), b.identity.nodeId(), c.identity.nodeId()), route.fullPath)
        nodes.forEach { it.stop() }
    }

    @Test
    fun `A discovers a route to C through two relays (A-B-D-C)`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val a = Node("A", network, this)
        val b = Node("B", network, this)
        val d = Node("D", network, this)
        val c = Node("C", network, this)
        val nodes = listOf(a, b, d, c)
        nodes.forEach { it.start() }

        network.connect(a.identity.nodeId(), b.identity.nodeId())
        network.connect(b.identity.nodeId(), d.identity.nodeId())
        network.connect(d.identity.nodeId(), c.identity.nodeId())
        advanceUntilIdle()

        val route = a.routing.discoverRoute(c.identity.nodeId(), timeoutMillis = 5_000L)
        advanceUntilIdle()

        assertNotNull(route)
        assertEquals(b.identity.nodeId(), route!!.nextHop)
        assertEquals(3, route.hopCount)
        nodes.forEach { it.stop() }
    }

    @Test
    fun `no route is found when nodes are disconnected`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val a = Node("A", network, this)
        val c = Node("C", network, this)
        val nodes = listOf(a, c)
        nodes.forEach { it.start() }
        // No connect() calls - A and C are never in range of each other or anything else.
        advanceUntilIdle()

        val route = a.routing.discoverRoute(c.identity.nodeId(), timeoutMillis = 2_000L)
        advanceUntilIdle()

        assertNull(route)
        nodes.forEach { it.stop() }
    }

    @Test
    fun `losing the relay invalidates the dependent route so it can be rediscovered`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val a = Node("A", network, this)
        val b = Node("B", network, this)
        val c = Node("C", network, this)
        val nodes = listOf(a, b, c)
        nodes.forEach { it.start() }

        network.connect(a.identity.nodeId(), b.identity.nodeId())
        network.connect(b.identity.nodeId(), c.identity.nodeId())
        advanceUntilIdle()

        val initialRoute = a.routing.discoverRoute(c.identity.nodeId(), timeoutMillis = 5_000L)
        advanceUntilIdle()
        assertNotNull(initialRoute)

        // Simulate B disappearing (moving out of range of both A and C).
        network.disconnect(a.identity.nodeId(), b.identity.nodeId())
        network.disconnect(b.identity.nodeId(), c.identity.nodeId())
        a.routing.onNeighborLost(b.identity.nodeId())
        advanceUntilIdle()

        assertNull("route depending on the vanished relay must be invalidated", a.routing.getRoute(c.identity.nodeId()))

        // Now bring an alternate relay D online and confirm rediscovery succeeds.
        val d = Node("D", network, this)
        d.start()
        network.connect(a.identity.nodeId(), d.identity.nodeId())
        network.connect(d.identity.nodeId(), c.identity.nodeId())
        advanceUntilIdle()

        val newRoute = a.routing.discoverRoute(c.identity.nodeId(), timeoutMillis = 5_000L)
        advanceUntilIdle()
        assertNotNull("A should rediscover a route to C via the new relay D", newRoute)
        assertEquals(d.identity.nodeId(), newRoute!!.nextHop)
        nodes.forEach { it.stop() }
        d.stop()
    }

    @Test
    fun `direct neighbors are discovered without flooding a route request`() = runTest {
        val network = SimulatedNetwork().apply { simulatedLatencyMillis = 0 }
        val a = Node("A", network, this)
        val b = Node("B", network, this)
        val nodes = listOf(a, b)
        nodes.forEach { it.start() }

        network.connect(a.identity.nodeId(), b.identity.nodeId())
        a.routing.onNeighborDiscovered(b.identity.nodeId())
        advanceUntilIdle()

        val route = a.routing.getRoute(b.identity.nodeId())
        assertNotNull(route)
        assertEquals(1, route!!.hopCount)
        nodes.forEach { it.stop() }
    }
}
