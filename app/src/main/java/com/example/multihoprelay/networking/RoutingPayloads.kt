package com.example.multihoprelay.networking

import com.example.multihoprelay.model.NodeId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Periodic 1-hop announcement payload - never forwarded by relays. */
data class HelloPayload(val protocolVersion: Int, val relayModeEnabled: Boolean) {
    fun encode(): ByteArray = byteArrayOf(protocolVersion.toByte(), if (relayModeEnabled) 1 else 0)
    companion object {
        fun decode(bytes: ByteArray): HelloPayload {
            if (bytes.size < 2) throw MalformedPacketException("Truncated HELLO")
            return HelloPayload(bytes[0].toInt() and 0xFF, bytes[1].toInt() != 0)
        }
    }
}

/**
 * Route request payload (RREQ), reactive AODV-style route discovery. Flooded outward from
 * the requester; each forwarding node appends itself to [visitedPath] for loop prevention
 * and so the eventual RREP can record the full discovered path for display in the UI.
 */
data class RouteRequestPayload(
    val requestId: Long,
    val originNode: NodeId,
    val targetNode: NodeId,
    val visitedPath: List<NodeId>
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(8 + NodeId.LENGTH + NodeId.LENGTH + 4 + visitedPath.size * NodeId.LENGTH)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(requestId)
        buffer.put(originNode.value.toByteArray(Charsets.US_ASCII))
        buffer.put(targetNode.value.toByteArray(Charsets.US_ASCII))
        buffer.putInt(visitedPath.size)
        visitedPath.forEach { buffer.put(it.value.toByteArray(Charsets.US_ASCII)) }
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): RouteRequestPayload {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (bytes.size < 8 + NodeId.LENGTH * 2 + 4) throw MalformedPacketException("Truncated RREQ")
            val requestId = buffer.long
            val origin = NodeId(readNodeIdString(buffer))
            val target = NodeId(readNodeIdString(buffer))
            val count = buffer.int
            if (count < 0 || count > 64) throw MalformedPacketException("Bad visited path count")
            val path = (0 until count).map { NodeId(readNodeIdString(buffer)) }
            return RouteRequestPayload(requestId, origin, target, path)
        }
    }
}

/**
 * Route reply payload (RREP), unicast back along the reverse path taken by the RREQ.
 *
 * [targetPublicKey] carries the destination node's RSA public key (X.509 encoded), set by
 * the destination itself when it originates the RREP. This is how a sender discovers a
 * multi-hop destination's public key without ever needing a direct connection to it: the
 * key rides back along the same reverse path as the route itself. It is not secret - public
 * keys are meant to be shared - so no confidentiality property is weakened by relays seeing it.
 */
data class RouteReplyPayload(
    val requestId: Long,
    val originNode: NodeId,
    val targetNode: NodeId,
    val fullPath: List<NodeId>,
    val targetPublicKey: ByteArray = ByteArray(0)
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(
            8 + NodeId.LENGTH + NodeId.LENGTH + 4 + fullPath.size * NodeId.LENGTH + 4 + targetPublicKey.size
        ).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(requestId)
        buffer.put(originNode.value.toByteArray(Charsets.US_ASCII))
        buffer.put(targetNode.value.toByteArray(Charsets.US_ASCII))
        buffer.putInt(fullPath.size)
        fullPath.forEach { buffer.put(it.value.toByteArray(Charsets.US_ASCII)) }
        buffer.putInt(targetPublicKey.size)
        buffer.put(targetPublicKey)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteReplyPayload) return false
        return requestId == other.requestId && originNode == other.originNode &&
            targetNode == other.targetNode && fullPath == other.fullPath &&
            targetPublicKey.contentEquals(other.targetPublicKey)
    }

    override fun hashCode(): Int = requestId.hashCode()

    companion object {
        fun decode(bytes: ByteArray): RouteReplyPayload {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (bytes.size < 8 + NodeId.LENGTH * 2 + 4) throw MalformedPacketException("Truncated RREP")
            val requestId = buffer.long
            val origin = NodeId(readNodeIdString(buffer))
            val target = NodeId(readNodeIdString(buffer))
            val count = buffer.int
            if (count < 0 || count > 64) throw MalformedPacketException("Bad path count")
            val path = (0 until count).map { NodeId(readNodeIdString(buffer)) }
            val keyLen = if (buffer.remaining() >= 4) buffer.int else 0
            val key = if (keyLen in 1..2048 && buffer.remaining() >= keyLen) {
                ByteArray(keyLen).also { buffer.get(it) }
            } else ByteArray(0)
            return RouteReplyPayload(requestId, origin, target, path, key)
        }
    }
}

/** Route error payload (RERR), sent when a node discovers a next hop is no longer reachable. */
data class RouteErrorPayload(val unreachableNode: NodeId, val reporterNode: NodeId) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(NodeId.LENGTH * 2)
        buffer.put(unreachableNode.value.toByteArray(Charsets.US_ASCII))
        buffer.put(reporterNode.value.toByteArray(Charsets.US_ASCII))
        return buffer.array()
    }
    companion object {
        fun decode(bytes: ByteArray): RouteErrorPayload {
            if (bytes.size < NodeId.LENGTH * 2) throw MalformedPacketException("Truncated RERR")
            val buffer = ByteBuffer.wrap(bytes)
            return RouteErrorPayload(NodeId(readNodeIdString(buffer)), NodeId(readNodeIdString(buffer)))
        }
    }
}

private fun readNodeIdString(buffer: ByteBuffer): String {
    val bytes = ByteArray(NodeId.LENGTH)
    buffer.get(bytes)
    return String(bytes, Charsets.US_ASCII)
}
