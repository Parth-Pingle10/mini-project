package com.example.multihoprelay.networking

import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.model.Packet
import com.example.multihoprelay.model.PacketType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class PacketCodecTest {

    private fun samplePacket(payload: ByteArray = "hello mesh".toByteArray()) = Packet(
        type = PacketType.CHUNK_DATA,
        packetId = UUID.randomUUID(),
        transferId = UUID.randomUUID(),
        sourceNodeId = NodeId("AAAAAAAA"),
        destinationNodeId = NodeId("BBBBBBBB"),
        hopCount = 2,
        maxHopCount = 8,
        sequenceNumber = 42L,
        payload = payload
    )

    @Test
    fun `encode then decode round-trips all fields`() {
        val original = samplePacket()
        val encoded = PacketCodec.encode(original)
        val decoded = PacketCodec.decode(encoded)

        assertEquals(original.version, decoded.version)
        assertEquals(original.type, decoded.type)
        assertEquals(original.packetId, decoded.packetId)
        assertEquals(original.transferId, decoded.transferId)
        assertEquals(original.sourceNodeId, decoded.sourceNodeId)
        assertEquals(original.destinationNodeId, decoded.destinationNodeId)
        assertEquals(original.hopCount, decoded.hopCount)
        assertEquals(original.maxHopCount, decoded.maxHopCount)
        assertEquals(original.sequenceNumber, decoded.sequenceNumber)
        assertEquals(original, decoded) // uses content-aware equals for payload
    }

    @Test
    fun `null transferId round-trips as null`() {
        val original = samplePacket().copy(transferId = null)
        val decoded = PacketCodec.decode(PacketCodec.encode(original))
        assertEquals(null, decoded.transferId)
    }

    @Test
    fun `empty payload round-trips correctly`() {
        val original = samplePacket(payload = ByteArray(0))
        val decoded = PacketCodec.decode(PacketCodec.encode(original))
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `decode rejects truncated packet`() {
        val encoded = PacketCodec.encode(samplePacket())
        val truncated = encoded.copyOf(encoded.size - 10)

        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decode(truncated)
        }
    }

    @Test
    fun `decode rejects corrupted bytes via CRC mismatch`() {
        val encoded = PacketCodec.encode(samplePacket())
        val corrupted = encoded.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()

        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decode(corrupted)
        }
    }

    @Test
    fun `decode rejects unknown packet type`() {
        val encoded = PacketCodec.encode(samplePacket()).copyOf()
        // Byte offset 1 is packetType; set to an invalid code and fix up nothing else - this
        // should be caught either as an unknown type or (more likely) a CRC mismatch, both of
        // which must result in a MalformedPacketException, never a crash or silent acceptance.
        corruptByteAndExpectRejection(encoded, index = 1)
    }

    @Test
    fun `decode rejects packet exceeding maximum size`() {
        val huge = ByteArray(PacketCodec.MAX_PACKET_SIZE + 1)
        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decode(huge)
        }
    }

    @Test
    fun `decode rejects empty byte array`() {
        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decode(ByteArray(0))
        }
    }

    @Test
    fun `encode rejects payload larger than MAX_PAYLOAD_BYTES`() {
        val oversized = samplePacket(payload = ByteArray(Packet.MAX_PAYLOAD_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) {
            PacketCodec.encode(oversized)
        }
    }

    @Test
    fun `different packet types round-trip their code correctly`() {
        PacketType.entries.forEach { type ->
            val packet = samplePacket().copy(type = type)
            val decoded = PacketCodec.decode(PacketCodec.encode(packet))
            assertEquals(type, decoded.type)
        }
    }

    private fun corruptByteAndExpectRejection(encoded: ByteArray, index: Int) {
        val corrupted = encoded.copyOf()
        corrupted[index] = (corrupted[index] + 1).toByte()
        assertThrows(MalformedPacketException::class.java) {
            PacketCodec.decode(corrupted)
        }
    }
}
