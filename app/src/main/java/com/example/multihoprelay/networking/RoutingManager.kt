package com.example.multihoprelay.networking

import com.example.multihoprelay.crypto.IdentityProvider
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import com.example.multihoprelay.model.Route
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A deliberately simple, reactive (on-demand) routing protocol loosely inspired by AODV -
 * appropriate for a small university-project mesh (tens of nodes, not internet-scale).
 *
 * Route discovery:
 *  - To find a path to an unknown destination, this node floods a ROUTE_REQUEST (RREQ) to
 *    all current neighbors. Each relay that has not already seen that (originNode, requestId)
 *    pair re-broadcasts it to its own neighbors, appending itself to the visited path and
 *    incrementing hop count, until maxHopCount is hit or the destination is reached.
 *  - The destination (or any node that already has a fresh route to it) replies with a
 *    unicast ROUTE_REPLY (RREP) that walks back along the reverse of the visited path.
 *  - Every node that forwards the RREP also learns/refreshes a route to the origin, which is
 *    why routes tend to become bidirectional after one discovery.
 *
 * Loop prevention: each RREQ carries the list of nodes already visited; a relay refuses to
 * forward (or accept) an RREQ that already contains its own NodeId, and a duplicate-packet
 * cache (by packetId) drops repeated floods regardless of path.
 *
 * Route expiration: entries older than [Route.DEFAULT_ROUTE_TTL_MILLIS] are dropped by
 * [pruneExpiredRoutes], forcing rediscovery - this naturally handles a relay disappearing.
 */
class RoutingManager(
    private val transport: MeshTransport,
    private val identityProvider: IdentityProvider,
    private val scope: CoroutineScope,
    private val logger: MeshLogger,
    private val routeTtlMillis: Long = Route.DEFAULT_ROUTE_TTL_MILLIS,
    private val maxHops: Int = Packet.DEFAULT_MAX_HOPS
) {
    private val routeTable = ConcurrentHashMap<NodeId, Route>()
    private val seenRequestIds = ConcurrentHashMap<String, Long>() // "origin:requestId" -> seenAtMillis
    private val seenPacketIds = ConcurrentHashMap<String, Long>() // packetId -> seenAtMillis, for RREQ/RREP dedup
    private val requestIdGenerator = AtomicLong(0)
    private val knownPublicKeys = ConcurrentHashMap<NodeId, ByteArray>()

    /** Returns a previously-learned public key for [nodeId], if one has passed through an RREP or HELLO. */
    fun getKnownPublicKey(nodeId: NodeId): ByteArray? = knownPublicKeys[nodeId]

    fun registerPublicKey(nodeId: NodeId, publicKeyBytes: ByteArray) {
        knownPublicKeys[nodeId] = publicKeyBytes
    }

    private val _routesFlow = MutableStateFlow<Map<NodeId, Route>>(emptyMap())
    val routesFlow: StateFlow<Map<NodeId, Route>> = _routesFlow

    private val pendingDiscoveries = ConcurrentHashMap<NodeId, MutableList<(Route?) -> Unit>>()
    private var jobs = mutableListOf<Job>()

    fun start(startPeriodicMaintenance: Boolean = true) {
        jobs += transport.incomingPackets
            .onEach { packet -> handleIncoming(packet) }
            .launchIn(scope)

        if (startPeriodicMaintenance) {
            jobs += scope.launch {
                while (true) {
                    delay(15_000L)
                    pruneExpiredRoutes()
                    evictOldDedupEntries()
                }
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    fun getRoute(destination: NodeId): Route? = routeTable[destination]

    fun allRoutes(): List<Route> = routeTable.values.toList()

    /** Records/refreshes a direct 1-hop route whenever a neighbor becomes reachable. */
    fun onNeighborDiscovered(neighbor: NodeId) {
        upsertRoute(Route(destination = neighbor, nextHop = neighbor, hopCount = 1, fullPath = listOf(transport.selfNodeId, neighbor)))
    }

    fun onNeighborLost(neighbor: NodeId) {
        // Remove any routes that depend on this neighbor as the next hop - forces rediscovery.
        val affected = routeTable.filterValues { it.nextHop == neighbor }.keys
        affected.forEach { routeTable.remove(it) }
        if (affected.isNotEmpty()) {
            logger.warn("ROUTE", "Neighbor $neighbor lost - invalidated ${affected.size} route(s)")
            publishRoutes()
            scope.launch {
                affected.forEach { dest ->
                    broadcastRouteError(neighbor, dest)
                }
            }
        }
    }

    /**
     * Attempts to find a route to [destination], flooding a ROUTE_REQUEST if no fresh route
     * is already known. Suspends up to [timeoutMillis] waiting for a ROUTE_REPLY.
     */
    suspend fun discoverRoute(destination: NodeId, timeoutMillis: Long = 8_000L): Route? {
        val existing = routeTable[destination]
        if (existing != null && !existing.isExpired(System.currentTimeMillis(), routeTtlMillis) && knownPublicKeys[destination] != null) {
            return existing
        }
        if (transport.neighbors.value.any { it.nodeId == destination } && knownPublicKeys[destination] != null) {
            onNeighborDiscovered(destination)
            return routeTable[destination]
        }

        val requestId = requestIdGenerator.incrementAndGet()
        val rreq = RouteRequestPayload(
            requestId = requestId,
            originNode = transport.selfNodeId,
            targetNode = destination,
            visitedPath = listOf(transport.selfNodeId)
        )
        seenRequestIds["${transport.selfNodeId}:$requestId"] = System.currentTimeMillis()

        logger.info("ROUTE", "Discovering route to $destination (request #$requestId)")

        val deferred = CompletableDeferred<Route?>()
        val callback: (Route?) -> Unit = { route -> deferred.complete(route) }
        val list = pendingDiscoveries.getOrPut(destination) { mutableListOf() }
        synchronized(list) {
            list.add(callback)
        }

        transport.broadcastToNeighbors(
            Packet(
                type = PacketType.ROUTE_REQUEST,
                sourceNodeId = transport.selfNodeId,
                destinationNodeId = NodeId.BROADCAST,
                maxHopCount = maxHops,
                payload = rreq.encode()
            )
        )

        val result = withTimeoutOrNull(timeoutMillis) {
            deferred.await()
        }
        synchronized(list) {
            list.remove(callback)
        }
        if (result == null) {
            logger.warn("ROUTE", "No route found to $destination (timed out)")
        }
        return result
    }

    private fun handleIncoming(packet: Packet) {
        when (packet.type) {
            PacketType.HELLO -> handleHello(packet)
            PacketType.ROUTE_REQUEST -> handleRouteRequest(packet)
            PacketType.ROUTE_REPLY -> handleRouteReply(packet)
            PacketType.ROUTE_ERROR -> handleRouteError(packet)
            else -> Unit // handled by RelayManager / TransferManager
        }
    }

    private fun handleHello(packet: Packet) {
        if (packet.payload.isNotEmpty()) {
            try {
                val derived = NodeId.fromPublicKeyBytes(packet.payload)
                if (derived == packet.sourceNodeId) {
                    registerPublicKey(packet.sourceNodeId, packet.payload)
                    logger.info("ROUTE", "Learned public key from HELLO for ${packet.sourceNodeId}")
                }
            } catch (e: Exception) {
                logger.debug("ROUTE", "Invalid public key in HELLO from ${packet.sourceNodeId}: ${e.message}")
            }
        }
        onNeighborDiscovered(packet.sourceNodeId)
    }

    private fun handleRouteRequest(packet: Packet) {
        val dedupKey = packet.packetId.toString()
        if (seenPacketIds.putIfAbsent(dedupKey, System.currentTimeMillis()) != null) {
            return // already processed this exact RREQ flood packet
        }
        val rreq = try { RouteRequestPayload.decode(packet.payload) } catch (e: Exception) {
            logger.warn("ROUTE", "Malformed RREQ dropped: ${e.message}")
            return
        }

        val requestKey = "${rreq.originNode}:${rreq.requestId}"
        val alreadySeen = seenRequestIds.putIfAbsent(requestKey, System.currentTimeMillis()) != null
        if (rreq.visitedPath.contains(transport.selfNodeId)) {
            return // loop prevention: we're already on this path
        }
        if (alreadySeen) {
            return // duplicate flood via a different path, ignore to prevent broadcast storms
        }
        if (rreq.visitedPath.size >= maxHops) {
            return // hop limit reached
        }

        val myPath = rreq.visitedPath + transport.selfNodeId
        // Learn a reverse route to the originator regardless of whether we are the target.
        upsertRoute(
            Route(
                destination = rreq.originNode,
                nextHop = myPath[myPath.size - 2],
                hopCount = myPath.size - 1,
                fullPath = myPath.reversed()
            )
        )

        if (rreq.targetNode == transport.selfNodeId) {
            logger.info("ROUTE", "I am the target of RREQ from ${rreq.originNode} - replying")
            sendRouteReply(rreq, myPath, identityProvider.publicKeyEncoded())
            return
        }

        val knownRoute = routeTable[rreq.targetNode]
        val knownKey = knownPublicKeys[rreq.targetNode]
        if (knownRoute != null && !knownRoute.isExpired(System.currentTimeMillis(), routeTtlMillis) && knownKey != null) {
            logger.info("ROUTE", "I know a route to ${rreq.targetNode} - replying on its behalf")
            sendRouteReply(rreq, myPath + knownRoute.fullPath.drop(1), knownKey)
            return
        }

        // Otherwise, continue flooding.
        scope.launch {
            transport.broadcastToNeighbors(
                packet.withIncrementedHop().copy(payload = rreq.copy(visitedPath = myPath).encode())
            )
        }
    }

    private fun sendRouteReply(rreq: RouteRequestPayload, fullPath: List<NodeId>, targetPublicKey: ByteArray) {
        val rrep = RouteReplyPayload(rreq.requestId, rreq.originNode, rreq.targetNode, fullPath, targetPublicKey)
        val nextHopBackToOrigin = fullPath[fullPath.size - 2]
        scope.launch {
            transport.sendToNeighbor(
                nextHopBackToOrigin,
                Packet(
                    type = PacketType.ROUTE_REPLY,
                    sourceNodeId = transport.selfNodeId,
                    destinationNodeId = rreq.originNode,
                    maxHopCount = maxHops,
                    payload = rrep.encode()
                )
            )
        }
    }

    private fun handleRouteReply(packet: Packet) {
        val rrep = try { RouteReplyPayload.decode(packet.payload) } catch (e: Exception) {
            logger.warn("ROUTE", "Malformed RREP dropped: ${e.message}")
            return
        }
        val path = rrep.fullPath
        val myIndex = path.indexOf(transport.selfNodeId)

        if (myIndex == -1) {
            logger.warn("ROUTE", "RREP path doesn't include self - dropping")
            return
        }

        if (rrep.targetPublicKey.isNotEmpty()) {
            knownPublicKeys[path.last()] = rrep.targetPublicKey
        }

        // Learn a route to the ultimate target using the path suffix from here onward.
        if (myIndex < path.size - 1) {
            upsertRoute(
                Route(
                    destination = path.last(),
                    nextHop = path[myIndex + 1],
                    hopCount = path.size - 1 - myIndex,
                    fullPath = path.subList(myIndex, path.size)
                )
            )
        }

        if (myIndex == 0) {
            // We are the original requester - route discovery complete.
            logger.info("ROUTE", "Route found: ${path.joinToString(" -> ")}")
            val route = routeTable[path.last()]
            pendingDiscoveries.remove(path.last())?.forEach { it(route) }
            return
        }

        // Forward the RREP back toward the origin (index 0 of the full path).
        val nextHopBackToOrigin = path[myIndex - 1]
        scope.launch {
            transport.sendToNeighbor(
                nextHopBackToOrigin,
                packet.withIncrementedHop().copy(destinationNodeId = path.first())
            )
        }
    }

    private fun handleRouteError(packet: Packet) {
        val rerr = try { RouteErrorPayload.decode(packet.payload) } catch (e: Exception) { return }
        val affected = routeTable.filterValues { it.nextHop == packet.sourceNodeId && it.destination == rerr.unreachableNode }.keys
        affected.forEach { routeTable.remove(it) }
        if (affected.isNotEmpty()) {
            logger.info("ROUTE", "Route error received for ${rerr.unreachableNode} via ${packet.sourceNodeId}")
            publishRoutes()
        }
    }

    private fun broadcastRouteError(unreachable: NodeId, originalDestination: NodeId) {
        val rerr = RouteErrorPayload(unreachable, transport.selfNodeId)
        logger.info(
            "ROUTE",
            "Broadcasting route error: $unreachable unreachable (was next hop for route to $originalDestination)"
        )
        scope.launch {
            transport.broadcastToNeighbors(
                Packet(
                    type = PacketType.ROUTE_ERROR,
                    sourceNodeId = transport.selfNodeId,
                    destinationNodeId = NodeId.BROADCAST,
                    payload = rerr.encode()
                )
            )
        }
    }

    private fun upsertRoute(route: Route) {
        val existing = routeTable[route.destination]
        if (existing == null || route.hopCount <= existing.hopCount) {
            routeTable[route.destination] = route
        } else {
            routeTable[route.destination] = existing.copy(lastConfirmedAtMillis = System.currentTimeMillis())
        }
        publishRoutes()
    }

    private fun pruneExpiredRoutes() {
        val now = System.currentTimeMillis()
        val expired = routeTable.filterValues { it.isExpired(now, routeTtlMillis) }.keys
        expired.forEach { routeTable.remove(it) }
        if (expired.isNotEmpty()) publishRoutes()
    }

    private fun evictOldDedupEntries() {
        val cutoff = System.currentTimeMillis() - 60_000L
        seenRequestIds.entries.removeAll { it.value < cutoff }
        seenPacketIds.entries.removeAll { it.value < cutoff }
    }

    private fun publishRoutes() {
        _routesFlow.value = routeTable.toMap()
    }
}
