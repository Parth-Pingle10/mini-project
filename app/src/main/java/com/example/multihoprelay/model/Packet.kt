package com.example.multihoprelay.model

import java.util.UUID

/**
 * Application-layer packet exchanged between nodes (over one BLE hop at a time).
 *
 * Wire format (see [com.example.multihoprelay.networking.PacketCodec] for the exact byte
 * layout and [com.example.multihoprelay.networking.FragmentAssembler] for how a Packet
 * larger than the negotiated BLE MTU is split into link-layer fragments before this
 * structure ever exists on the wire):
 *
 * ```
 * Offset  Field                   Size
 * 0       version                 1 byte
 * 1       packetType              1 byte
 * 2-17    packetId (UUID)         16 bytes
 * 18-33   transferId (UUID)       16 bytes  (all-zero if not part of a transfer)
 * 34-41   sourceNodeId            8 bytes (ASCII hex)
 * 42-49   destinationNodeId       8 bytes (ASCII hex)
 * 50      hopCount                1 byte
 * 51      maxHopCount             1 byte
 * 52-59   sequenceNumber          8 bytes (long, big-endian)
 * 60-63   payloadLength           4 bytes (int, big-endian)
 * 64..    payload                 [payloadLength] bytes
 * ..      crc32                   4 bytes (CRC32 over all preceding bytes)
 * ```
 *
 * The CRC32 is a link-layer corruption check only (BLE already provides its own link-layer
 * CRC, but application-level fragment reassembly can itself introduce bugs, so we check
 * again) - it is NOT a cryptographic authenticator and provides no protection against a
 * deliberately malicious relay. Real security comes from two independent, end-to-end
 * mechanisms that no relay can forge because relays never hold the sender's or receiver's
 * private key:
 *   1. AES-256-GCM authenticated encryption of the payload itself (confidentiality +
 *      tamper detection of the payload bytes) - see [com.example.multihoprelay.crypto.EncryptionManager].
 *   2. An RSA-PSS signature over TRANSFER_INIT metadata by the sender's identity key,
 *      verified by the receiver - see [com.example.multihoprelay.crypto.SignatureManager].
 *
 * The [payload] for control/data packets carrying end-to-end encrypted content is itself an
 * opaque encrypted blob from the relay's point of view; relays only ever parse the fixed
 * header fields above the payload boundary.
 */
data class Packet(
    val version: Int = PROTOCOL_VERSION,
    val type: PacketType,
    val packetId: UUID = UUID.randomUUID(),
    val transferId: UUID? = null,
    val sourceNodeId: NodeId,
    val destinationNodeId: NodeId,
    val hopCount: Int = 0,
    val maxHopCount: Int = DEFAULT_MAX_HOPS,
    val sequenceNumber: Long = 0L,
    val payload: ByteArray = ByteArray(0)
) {
    fun withIncrementedHop(): Packet = copy(hopCount = hopCount + 1)

    fun isExpiredByHopCount(): Boolean = hopCount >= maxHopCount

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Packet) return false
        return version == other.version && type == other.type && packetId == other.packetId &&
            transferId == other.transferId && sourceNodeId == other.sourceNodeId &&
            destinationNodeId == other.destinationNodeId && hopCount == other.hopCount &&
            maxHopCount == other.maxHopCount && sequenceNumber == other.sequenceNumber &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val DEFAULT_MAX_HOPS = 8
        const val MAX_PAYLOAD_BYTES = 512 * 1024 // sanity ceiling for a single logical packet
        val ZERO_TRANSFER_ID: UUID = UUID(0, 0)
    }
}
