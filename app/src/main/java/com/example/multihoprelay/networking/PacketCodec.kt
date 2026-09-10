package com.example.multihoprelay.networking

import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Thrown when bytes cannot be decoded into a valid [Packet]. Callers must reject, never crash. */
class MalformedPacketException(message: String) : Exception(message)

/**
 * Encodes/decodes [Packet] to/from the compact binary wire format documented in [Packet]'s
 * KDoc. A manual, fixed-layout binary codec (rather than a general-purpose serialization
 * library) is used deliberately here: it keeps every byte of the header accounted for,
 * makes the fragmentation boundary in [FragmentAssembler] simple to reason about, and lets
 * relay nodes parse only the small routing header without touching the payload at all.
 */
object PacketCodec {

    private const val HEADER_FIXED_SIZE =
        1 + 1 + 16 + 16 + NodeId.LENGTH + NodeId.LENGTH + 1 + 1 + 8 + 4
    private const val CRC_SIZE = 4
    const val MAX_PACKET_SIZE = 1_000_000 // guards against maliciously huge length fields

    fun encode(packet: Packet): ByteArray {
        require(packet.payload.size <= Packet.MAX_PAYLOAD_BYTES) {
            "Payload too large: ${packet.payload.size} bytes"
        }
        val totalSize = HEADER_FIXED_SIZE + packet.payload.size + CRC_SIZE
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.put(packet.version.toByte())
        buffer.put(packet.type.code.toByte())
        putUuid(buffer, packet.packetId)
        putUuid(buffer, packet.transferId ?: Packet.ZERO_TRANSFER_ID)
        buffer.put(packet.sourceNodeId.value.toByteArray(Charsets.US_ASCII))
        buffer.put(packet.destinationNodeId.value.toByteArray(Charsets.US_ASCII))
        buffer.put(packet.hopCount.toByte())
        buffer.put(packet.maxHopCount.toByte())
        buffer.putLong(packet.sequenceNumber)
        buffer.putInt(packet.payload.size)
        buffer.put(packet.payload)

        val crc = CRC32()
        crc.update(buffer.array(), 0, HEADER_FIXED_SIZE + packet.payload.size)
        buffer.putInt(crc.value.toInt())

        return buffer.array()
    }

    fun decode(bytes: ByteArray): Packet {
        if (bytes.size < HEADER_FIXED_SIZE + CRC_SIZE) {
            throw MalformedPacketException("Packet too short: ${bytes.size} bytes")
        }
        if (bytes.size > MAX_PACKET_SIZE) {
            throw MalformedPacketException("Packet exceeds maximum size: ${bytes.size} bytes")
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xFF
        val typeCode = buffer.get().toInt() and 0xFF
        val packetId = readUuid(buffer)
        val transferIdRaw = readUuid(buffer)
        val sourceBytes = ByteArray(NodeId.LENGTH).also { buffer.get(it) }
        val destBytes = ByteArray(NodeId.LENGTH).also { buffer.get(it) }
        val hopCount = buffer.get().toInt() and 0xFF
        val maxHopCount = buffer.get().toInt() and 0xFF
        val sequenceNumber = buffer.long
        val payloadLength = buffer.int

        if (payloadLength < 0 || payloadLength > Packet.MAX_PAYLOAD_BYTES) {
            throw MalformedPacketException("Invalid payload length: $payloadLength")
        }
        if (buffer.remaining() < payloadLength + CRC_SIZE) {
            throw MalformedPacketException("Truncated packet: declared $payloadLength payload bytes")
        }

        val payload = ByteArray(payloadLength).also { buffer.get(it) }
        val receivedCrc = buffer.int

        val computedCrc = CRC32()
        computedCrc.update(bytes, 0, HEADER_FIXED_SIZE + payloadLength)
        if (computedCrc.value.toInt() != receivedCrc) {
            throw MalformedPacketException("CRC mismatch - packet corrupted in transit")
        }

        if (version != Packet.PROTOCOL_VERSION) {
            throw MalformedPacketException("Unsupported protocol version: $version")
        }

        val type = try {
            PacketType.fromCode(typeCode)
        } catch (e: IllegalArgumentException) {
            throw MalformedPacketException("Unknown packet type: $typeCode")
        }

        val sourceNodeId = try {
            NodeId(String(sourceBytes, Charsets.US_ASCII))
        } catch (e: IllegalArgumentException) {
            throw MalformedPacketException("Invalid source node id")
        }
        val destNodeId = try {
            NodeId(String(destBytes, Charsets.US_ASCII))
        } catch (e: IllegalArgumentException) {
            throw MalformedPacketException("Invalid destination node id")
        }

        return Packet(
            version = version,
            type = type,
            packetId = packetId,
            transferId = if (transferIdRaw == Packet.ZERO_TRANSFER_ID) null else transferIdRaw,
            sourceNodeId = sourceNodeId,
            destinationNodeId = destNodeId,
            hopCount = hopCount,
            maxHopCount = maxHopCount,
            sequenceNumber = sequenceNumber,
            payload = payload
        )
    }

    private fun putUuid(buffer: ByteBuffer, uuid: UUID) {
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
    }

    private fun readUuid(buffer: ByteBuffer): UUID =
        UUID(buffer.long, buffer.long)
}
