package com.example.multihoprelay.networking

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE ATT payloads are small (typically 20 bytes with the default 23-byte MTU, up to
 * roughly 500 bytes after MTU negotiation on modern stacks). An encoded [com.example.multihoprelay.model.Packet]
 * - especially a CHUNK_DATA packet carrying encrypted file data - is almost always larger
 * than that, so it must be split into link-layer fragments before being written to a GATT
 * characteristic, and reassembled on the other side.
 *
 * Fragment wire format (each fragment is one BLE write):
 * ```
 * Offset  Field              Size
 * 0       fragmentFlags      1 byte   (bit 0: isFirst, bit 1: isLast)
 * 1-4     logicalMessageId   4 bytes  (random id grouping fragments of one Packet)
 * 5-6     fragmentIndex      2 bytes
 * 7-8     totalFragments     2 bytes
 * 9..     fragmentPayload    remaining bytes
 * ```
 */
object FragmentAssembler {

    private const val FRAGMENT_HEADER_SIZE = 9
    const val FLAG_FIRST = 0x01
    const val FLAG_LAST = 0x02

    /** Splits [data] into fragments no larger than [mtuPayloadSize] bytes (after the fragment header). */
    fun fragment(data: ByteArray, mtuPayloadSize: Int, logicalMessageId: Int): List<ByteArray> {
        val chunkSize = (mtuPayloadSize - FRAGMENT_HEADER_SIZE).coerceAtLeast(1)
        val totalFragments = ((data.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        val fragments = mutableListOf<ByteArray>()

        for (i in 0 until totalFragments) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, data.size)
            val slice = data.copyOfRange(start, end)

            var flags = 0
            if (i == 0) flags = flags or FLAG_FIRST
            if (i == totalFragments - 1) flags = flags or FLAG_LAST

            val buffer = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + slice.size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(flags.toByte())
            buffer.putInt(logicalMessageId)
            buffer.putShort(i.toShort())
            buffer.putShort(totalFragments.toShort())
            buffer.put(slice)
            fragments.add(buffer.array())
        }
        return fragments
    }

    data class ParsedFragment(
        val isFirst: Boolean,
        val isLast: Boolean,
        val logicalMessageId: Int,
        val fragmentIndex: Int,
        val totalFragments: Int,
        val payload: ByteArray
    )

    fun parseFragment(bytes: ByteArray): ParsedFragment {
        if (bytes.size < FRAGMENT_HEADER_SIZE) {
            throw MalformedPacketException("Fragment too short: ${bytes.size} bytes")
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val flags = buffer.get().toInt() and 0xFF
        val logicalMessageId = buffer.int
        val fragmentIndex = buffer.short.toInt() and 0xFFFF
        val totalFragments = buffer.short.toInt() and 0xFFFF
        val payload = ByteArray(bytes.size - FRAGMENT_HEADER_SIZE).also { buffer.get(it) }
        if (totalFragments <= 0 || fragmentIndex >= totalFragments) {
            throw MalformedPacketException("Invalid fragment indices")
        }
        return ParsedFragment(
            isFirst = (flags and FLAG_FIRST) != 0,
            isLast = (flags and FLAG_LAST) != 0,
            logicalMessageId = logicalMessageId,
            fragmentIndex = fragmentIndex,
            totalFragments = totalFragments,
            payload = payload
        )
    }
}

/**
 * Stateful reassembly buffer for one BLE link (one connected peer). Not thread-safe by
 * itself; callers (e.g. [com.example.multihoprelay.bluetooth.BleConnectionManager]) must
 * serialize access per-connection, which the GATT callback threading model does naturally.
 */
class LinkReassemblyBuffer {

    private data class InProgress(
        val totalFragments: Int,
        val parts: Array<ByteArray?>,
        val firstSeenMillis: Long = System.currentTimeMillis()
    )

    private val inProgress = ConcurrentHashMap<Int, InProgress>()

    /** Feed one received fragment. Returns the fully reassembled message bytes once complete, else null. */
    fun onFragmentReceived(bytes: ByteArray): ByteArray? {
        val fragment = FragmentAssembler.parseFragment(bytes)

        val entry = inProgress.getOrPut(fragment.logicalMessageId) {
            InProgress(fragment.totalFragments, arrayOfNulls(fragment.totalFragments))
        }
        if (fragment.totalFragments != entry.totalFragments) {
            // Inconsistent - drop and restart tracking this message id to avoid corrupting state.
            inProgress.remove(fragment.logicalMessageId)
            return null
        }
        if (fragment.fragmentIndex < entry.parts.size) {
            entry.parts[fragment.fragmentIndex] = fragment.payload
        }

        if (entry.parts.all { it != null }) {
            inProgress.remove(fragment.logicalMessageId)
            val totalLen = entry.parts.sumOf { it!!.size }
            val out = ByteBuffer.allocate(totalLen)
            entry.parts.forEach { out.put(it!!) }
            return out.array()
        }
        return null
    }

    /** Removes reassembly state older than [maxAgeMillis] to bound memory if a peer vanishes mid-message. */
    fun evictStale(maxAgeMillis: Long = 30_000L) {
        val now = System.currentTimeMillis()
        val stale = inProgress.filterValues { now - it.firstSeenMillis > maxAgeMillis }.keys
        stale.forEach { inProgress.remove(it) }
    }

    fun clear() = inProgress.clear()
}
