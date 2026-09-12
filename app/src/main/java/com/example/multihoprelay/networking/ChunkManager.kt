package com.example.multihoprelay.networking

import com.example.multihoprelay.crypto.HashManager
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil

/**
 * Splits a file into fixed-size chunks for transfer, and reassembles received chunks back
 * into a file. Uses [RandomAccessFile] to read/write at arbitrary offsets so:
 *  - the sender can re-read (and re-encrypt, see [TransferManager]) any chunk on demand for
 *    retransmission without keeping the whole file in memory, and
 *  - the receiver can write chunks as they arrive in ANY order (BLE multi-hop delivery is
 *    not guaranteed to be in-order) directly to their correct offset, and detect exactly
 *    which chunks are still missing to resume an interrupted transfer.
 */
object ChunkManager {

    const val DEFAULT_CHUNK_SIZE = 16 * 1024 // 16 KB plaintext per chunk before AES-GCM overhead

    fun totalChunks(fileSizeBytes: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE): Int =
        ceil(fileSizeBytes.toDouble() / chunkSize.toDouble()).toInt().coerceAtLeast(1)

    /** Reads exactly the plaintext bytes belonging to [chunkIndex] from [file], without loading the rest. */
    fun readChunk(file: File, chunkIndex: Int, chunkSize: Int = DEFAULT_CHUNK_SIZE): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            val offset = chunkIndex.toLong() * chunkSize
            raf.seek(offset)
            val remaining = (raf.length() - offset).coerceAtMost(chunkSize.toLong()).toInt()
            require(remaining > 0) { "Chunk index $chunkIndex out of range for file of length ${raf.length()}" }
            val buffer = ByteArray(remaining)
            raf.readFully(buffer)
            return buffer
        }
    }

    /** Writes [data] (already-decrypted plaintext) for [chunkIndex] into [file] at the correct offset. */
    fun writeChunk(file: File, chunkIndex: Int, data: ByteArray, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(chunkIndex.toLong() * chunkSize)
            raf.write(data)
        }
    }

    /** Computes SHA-256 of every chunk (plaintext) for inclusion in TRANSFER_INIT metadata (integrity). */
    fun chunkHashes(file: File, totalChunks: Int, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<ByteArray> =
        (0 until totalChunks).map { index -> HashManager.sha256(readChunk(file, index, chunkSize)) }

    /** Ensures the destination file exists and is pre-sized so out-of-order random writes are valid. */
    fun preallocate(file: File, sizeBytes: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf -> raf.setLength(sizeBytes) }
    }

    fun fullFileSha256(file: File): ByteArray {
        file.inputStream().use { return HashManager.sha256Stream(it) }
    }
}
