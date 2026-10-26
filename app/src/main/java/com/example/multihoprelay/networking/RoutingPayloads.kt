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

/** Single entry in a route advertisement representing reachability to a destination. */
data class RouteAdvertisementEntry(
    val destinationNodeId: NodeId,
    val deviceName: String,
    val hopCount: Int,
    val isAvailable: Boolean = true,
    val publicKey: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RouteAdvertisementEntry) return false
        return destinationNodeId == other.destinationNodeId &&
            deviceName == other.deviceName &&
            hopCount == other.hopCount &&
            isAvailable == other.isAvailable &&
            publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int = destinationNodeId.hashCode()
}

/**
 * Route advertisement payload exchanged periodically and on topology changes.
 * Propagates reachable node identities, device names, hop counts, and public keys
 * across intermediate relay nodes without broadcast storms.
 */
data class RouteAdvertisementPayload(
    val sequenceNumber: Long,
    val advertiserNode: NodeId,
    val entries: List<RouteAdvertisementEntry>
) {
    fun encode(): ByteArray {
        var estimatedSize = 8 + NodeId.LENGTH + 4
        entries.forEach { entry ->
            val nameBytes = entry.deviceName.toByteArray(Charsets.UTF_8)
            estimatedSize += NodeId.LENGTH + 4 + nameBytes.size + 4 + 1 + 4 + entry.publicKey.size
        }
        val buffer = ByteBuffer.allocate(estimatedSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(sequenceNumber)
        buffer.put(advertiserNode.value.toByteArray(Charsets.US_ASCII))
        buffer.putInt(entries.size)
        entries.forEach { entry ->
            buffer.put(entry.destinationNodeId.value.toByteArray(Charsets.US_ASCII))
            val nameBytes = entry.deviceName.toByteArray(Charsets.UTF_8)
            buffer.putInt(nameBytes.size)
            buffer.put(nameBytes)
            buffer.putInt(entry.hopCount)
            buffer.put(if (entry.isAvailable) 1.toByte() else 0.toByte())
            buffer.putInt(entry.publicKey.size)
            buffer.put(entry.publicKey)
        }
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): RouteAdvertisementPayload {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            if (bytes.size < 8 + NodeId.LENGTH + 4) throw MalformedPacketException("Truncated RouteAdvertisement")
            val sequenceNumber = buffer.long
            val advertiserNode = NodeId(readNodeIdString(buffer))
            val count = buffer.int
            if (count < 0 || count > 256) throw MalformedPacketException("Bad entry count in RouteAdvertisement: $count")
            val list = mutableListOf<RouteAdvertisementEntry>()
            for (i in 0 until count) {
                if (buffer.remaining() < NodeId.LENGTH + 4) throw MalformedPacketException("Truncated entry in RouteAdvertisement")
                val destNode = NodeId(readNodeIdString(buffer))
                val nameLen = buffer.int
                if (nameLen < 0 || buffer.remaining() < nameLen) throw MalformedPacketException("Bad device name length")
                val nameBytes = ByteArray(nameLen).also { buffer.get(it) }
                val deviceName = String(nameBytes, Charsets.UTF_8)
                val hopCount = buffer.int
                val isAvailable = buffer.get().toInt() != 0
                val keyLen = buffer.int
                if (keyLen < 0 || buffer.remaining() < keyLen) throw MalformedPacketException("Bad key length in RouteAdvertisement")
                val pubKey = if (keyLen in 1..2048) ByteArray(keyLen).also { buffer.get(it) } else ByteArray(0)
                list.add(RouteAdvertisementEntry(destNode, deviceName, hopCount, isAvailable, pubKey))
            }
            return RouteAdvertisementPayload(sequenceNumber, advertiserNode, list)
        }
    }
}

private fun readNodeIdString(buffer: ByteBuffer): String {
    val bytes = ByteArray(NodeId.LENGTH)
    buffer.get(bytes)
    return String(bytes, Charsets.US_ASCII)
}
