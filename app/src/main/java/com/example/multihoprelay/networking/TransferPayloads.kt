package com.example.multihoprelay.networking

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Payload of a TRANSFER_INIT packet. This is what the sender puts inside `Packet.payload`
 * for a [com.example.multihoprelay.model.PacketType.TRANSFER_INIT] packet.
 *
 * Everything here is visible to relays as opaque bytes (the packet header's transferId,
 * source/destination NodeId are the only fields relays actually parse). The fields
 * [wrappedFileKey] and [signature] are exactly why the relay can never derive the plaintext:
 * the wrapped key is RSA-OAEP-encrypted for the receiver's public key alone, and the
 * signature only proves sender authenticity, it grants no decryption capability.
 */
data class TransferInitPayload(
    val fileName: String,
    val fileSizeBytes: Long,
    val chunkSizeBytes: Int,
    val totalChunks: Int,
    val wrappedFileKey: ByteArray,       // AES file key, RSA-OAEP wrapped for the receiver
    val senderPublicKey: ByteArray,      // sender's RSA public key (X.509 encoded), so receiver can verify signature
    val signature: ByteArray,            // RSA-PSS signature by sender over the fields above
    val fileSha256: ByteArray            // hash of the *plaintext* file, checked after full decryption
) {
    fun encode(): ByteArray {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val size = 4 + nameBytes.size + 8 + 4 + 4 +
            4 + wrappedFileKey.size +
            4 + senderPublicKey.size +
            4 + signature.size +
            4 + fileSha256.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(nameBytes.size); buffer.put(nameBytes)
        buffer.putLong(fileSizeBytes)
        buffer.putInt(chunkSizeBytes)
        buffer.putInt(totalChunks)
        buffer.putInt(wrappedFileKey.size); buffer.put(wrappedFileKey)
        buffer.putInt(senderPublicKey.size); buffer.put(senderPublicKey)
        buffer.putInt(signature.size); buffer.put(signature)
        buffer.putInt(fileSha256.size); buffer.put(fileSha256)
        return buffer.array()
    }

    /** Fields that were actually covered by [signature] (i.e. everything except the signature itself). */
    fun signedBytes(): ByteArray {
        val nameBytes = fileName.toByteArray(Charsets.UTF_8)
        val size = 4 + nameBytes.size + 8 + 4 + 4 + 4 + wrappedFileKey.size + 4 + senderPublicKey.size + 4 + fileSha256.size
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(nameBytes.size); buffer.put(nameBytes)
        buffer.putLong(fileSizeBytes)
        buffer.putInt(chunkSizeBytes)
        buffer.putInt(totalChunks)
        buffer.putInt(wrappedFileKey.size); buffer.put(wrappedFileKey)
        buffer.putInt(senderPublicKey.size); buffer.put(senderPublicKey)
        buffer.putInt(fileSha256.size); buffer.put(fileSha256)
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): TransferInitPayload {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            fun readBlob(): ByteArray {
                val len = buffer.int
                if (len < 0 || len > bytes.size) throw MalformedPacketException("Bad length field")
                return ByteArray(len).also { buffer.get(it) }
            }
            val fileName = String(readBlob(), Charsets.UTF_8)
            val fileSizeBytes = buffer.long
            val chunkSizeBytes = buffer.int
            val totalChunks = buffer.int
            val wrappedFileKey = readBlob()
            val senderPublicKey = readBlob()
            val signature = readBlob()
            val fileSha256 = readBlob()
            return TransferInitPayload(
                fileName, fileSizeBytes, chunkSizeBytes, totalChunks,
                wrappedFileKey, senderPublicKey, signature, fileSha256
            )
        }
    }
}

/**
 * Payload of a CHUNK_DATA packet: one AES-GCM sealed chunk of the file, plus enough metadata
 * to place it correctly and detect duplicates/missing chunks. The relay can see [chunkIndex]
 * and the ciphertext length but not the plaintext content or even the plaintext chunk size
 * (padding is not applied in this implementation - documented as a known limitation, since
 * ciphertext length reveals plaintext length for GCM).
 */
data class ChunkDataPayload(
    val chunkIndex: Int,
    val totalChunks: Int,
    val nonce: ByteArray,
    val ciphertext: ByteArray
) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 4 + 4 + nonce.size + 4 + ciphertext.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(chunkIndex)
        buffer.putInt(totalChunks)
        buffer.putInt(nonce.size); buffer.put(nonce)
        buffer.putInt(ciphertext.size); buffer.put(ciphertext)
        return buffer.array()
    }

    companion object {
        fun decode(bytes: ByteArray): ChunkDataPayload {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val chunkIndex = buffer.int
            val totalChunks = buffer.int
            val nonceLen = buffer.int
            if (nonceLen !in 1..64) throw MalformedPacketException("Bad nonce length")
            val nonce = ByteArray(nonceLen).also { buffer.get(it) }
            val ctLen = buffer.int
            if (ctLen < 0 || ctLen > bytes.size) throw MalformedPacketException("Bad ciphertext length")
            val ciphertext = ByteArray(ctLen).also { buffer.get(it) }
            return ChunkDataPayload(chunkIndex, totalChunks, nonce, ciphertext)
        }
    }
}

/** Payload of a CHUNK_ACK packet - just the chunk index being acknowledged. */
data class ChunkAckPayload(val chunkIndex: Int) {
    fun encode(): ByteArray = ByteBuffer.allocate(4).putInt(chunkIndex).array()
    companion object {
        fun decode(bytes: ByteArray): ChunkAckPayload {
            if (bytes.size < 4) throw MalformedPacketException("Truncated ACK")
            return ChunkAckPayload(ByteBuffer.wrap(bytes).int)
        }
    }
}

/** Payload of a CHUNK_RESEND_REQUEST packet - list of missing chunk indices. */
data class ChunkResendRequestPayload(val missingIndices: List<Int>) {
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + missingIndices.size * 4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(missingIndices.size)
        missingIndices.forEach { buffer.putInt(it) }
        return buffer.array()
    }
    companion object {
        fun decode(bytes: ByteArray): ChunkResendRequestPayload {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val count = buffer.int
            if (count < 0 || count > 100_000) throw MalformedPacketException("Bad resend count")
            val list = (0 until count).map { buffer.int }
            return ChunkResendRequestPayload(list)
        }
    }
}
