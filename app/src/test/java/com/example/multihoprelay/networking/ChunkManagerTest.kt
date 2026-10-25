package com.example.multihoprelay.networking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.random.Random

class ChunkManagerTest {

    private fun tempFileWithRandomContent(sizeBytes: Int): java.io.File {
        val file = Files.createTempFile("chunktest", ".bin").toFile()
        file.writeBytes(Random(1234).nextBytes(sizeBytes))
        file.deleteOnExit()
        return file
    }

    @Test
    fun `totalChunks computes correct count for exact and remainder sizes`() {
        assertEquals(1, ChunkManager.totalChunks(0, chunkSize = 1024))
        assertEquals(1, ChunkManager.totalChunks(1024, chunkSize = 1024))
        assertEquals(2, ChunkManager.totalChunks(1025, chunkSize = 1024))
        assertEquals(5, ChunkManager.totalChunks(5000, chunkSize = 1024))
    }

    @Test
    fun `readChunk then writeChunk reconstructs the original file byte-for-byte`() {
        val chunkSize = 777
        val source = tempFileWithRandomContent(sizeBytes = 5000)
        val totalChunks = ChunkManager.totalChunks(source.length(), chunkSize)

        val destination = Files.createTempFile("chunktest_out", ".bin").toFile()
        ChunkManager.preallocate(destination, source.length())

        for (index in 0 until totalChunks) {
            val chunk = ChunkManager.readChunk(source, index, chunkSize)
            ChunkManager.writeChunk(destination, index, chunk, chunkSize)
        }

        assertArrayEquals(source.readBytes(), destination.readBytes())
    }

    @Test
    fun `reconstructs correctly even when chunks are written out of order`() {
        val chunkSize = 500
        val source = tempFileWithRandomContent(sizeBytes = 4321)
        val totalChunks = ChunkManager.totalChunks(source.length(), chunkSize)

        val destination = Files.createTempFile("chunktest_ooo", ".bin").toFile()
        ChunkManager.preallocate(destination, source.length())

        val order = (0 until totalChunks).shuffled(kotlin.random.Random(42))
        for (index in order) {
            val chunk = ChunkManager.readChunk(source, index, chunkSize)
            ChunkManager.writeChunk(destination, index, chunk, chunkSize)
        }

        assertArrayEquals(source.readBytes(), destination.readBytes())
    }

    @Test
    fun `last chunk is smaller when file size is not a multiple of chunk size`() {
        val chunkSize = 1000
        val source = tempFileWithRandomContent(sizeBytes = 2500)
        val totalChunks = ChunkManager.totalChunks(source.length(), chunkSize)

        val lastChunk = ChunkManager.readChunk(source, totalChunks - 1, chunkSize)
        assertEquals(500, lastChunk.size)
    }

    @Test
    fun `chunkHashes produces one sha256 digest per chunk matching direct hash computation`() {
        val chunkSize = 256
        val source = tempFileWithRandomContent(sizeBytes = 1000)
        val totalChunks = ChunkManager.totalChunks(source.length(), chunkSize)

        val hashes = ChunkManager.chunkHashes(source, totalChunks, chunkSize)
        assertEquals(totalChunks, hashes.size)

        for (index in 0 until totalChunks) {
            val chunkBytes = ChunkManager.readChunk(source, index, chunkSize)
            val expected = com.example.multihoprelay.crypto.HashManager.sha256(chunkBytes)
            assertArrayEquals(expected, hashes[index])
        }
    }

    @Test
    fun `fullFileSha256 matches HashManager sha256 of the whole file`() {
        val source = tempFileWithRandomContent(sizeBytes = 10_000)
        val fromChunkManager = ChunkManager.fullFileSha256(source)
        val direct = source.inputStream().use { com.example.multihoprelay.crypto.HashManager.sha256Stream(it) }
        assertArrayEquals(direct, fromChunkManager)
    }

    @Test
    fun `preallocate creates a file of exactly the requested size`() {
        val destination = Files.createTempFile("prealloc", ".bin").toFile()
        ChunkManager.preallocate(destination, 12345L)
        assertTrue(destination.exists())
        assertEquals(12345L, destination.length())
    }
}
