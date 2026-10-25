package com.example.multihoprelay.networking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class FragmentAssemblerTest {

    @Test
    fun `small message fits in a single fragment`() {
        val data = "short".toByteArray()
        val fragments = FragmentAssembler.fragment(data, mtuPayloadSize = 100, logicalMessageId = 1)
        assertEquals(1, fragments.size)

        val parsed = FragmentAssembler.parseFragment(fragments[0])
        assertEquals(true, parsed.isFirst)
        assertEquals(true, parsed.isLast)
        assertArrayEquals(data, parsed.payload)
    }

    @Test
    fun `large message splits into multiple fragments and reassembles correctly`() {
        val data = Random.nextBytes(5000)
        val fragments = FragmentAssembler.fragment(data, mtuPayloadSize = 100, logicalMessageId = 42)
        assertEquals(true, fragments.size > 1)

        val buffer = LinkReassemblyBuffer()
        var result: ByteArray? = null
        for (fragment in fragments) {
            result = buffer.onFragmentReceived(fragment)
        }
        assertNotNull("last fragment should complete reassembly", result)
        assertArrayEquals(data, result)
    }

    @Test
    fun `fragments arriving out of order still reassemble correctly`() {
        val data = Random.nextBytes(3000)
        val fragments = FragmentAssembler.fragment(data, mtuPayloadSize = 64, logicalMessageId = 7).shuffled(Random(99))

        val buffer = LinkReassemblyBuffer()
        var result: ByteArray? = null
        for (fragment in fragments) {
            val r = buffer.onFragmentReceived(fragment)
            if (r != null) result = r
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun `interleaved messages with different logical ids do not corrupt each other`() {
        val dataA = Random.nextBytes(2000)
        val dataB = Random.nextBytes(2500)
        val fragmentsA = FragmentAssembler.fragment(dataA, mtuPayloadSize = 80, logicalMessageId = 1)
        val fragmentsB = FragmentAssembler.fragment(dataB, mtuPayloadSize = 80, logicalMessageId = 2)

        val buffer = LinkReassemblyBuffer()
        var resultA: ByteArray? = null
        var resultB: ByteArray? = null

        // Interleave fragments from both messages.
        val maxLen = maxOf(fragmentsA.size, fragmentsB.size)
        for (i in 0 until maxLen) {
            if (i < fragmentsA.size) buffer.onFragmentReceived(fragmentsA[i])?.let { resultA = it }
            if (i < fragmentsB.size) buffer.onFragmentReceived(fragmentsB[i])?.let { resultB = it }
        }

        assertArrayEquals(dataA, resultA)
        assertArrayEquals(dataB, resultB)
    }

    @Test
    fun `parseFragment rejects too-short bytes`() {
        assertThrows(MalformedPacketException::class.java) {
            FragmentAssembler.parseFragment(ByteArray(3))
        }
    }

    @Test
    fun `incomplete fragment sequence does not emit a result`() {
        val data = Random.nextBytes(2000)
        val fragments = FragmentAssembler.fragment(data, mtuPayloadSize = 64, logicalMessageId = 5)

        val buffer = LinkReassemblyBuffer()
        // Feed all but the last fragment.
        var result: ByteArray? = null
        for (fragment in fragments.dropLast(1)) {
            result = buffer.onFragmentReceived(fragment)
        }
        assertNull(result)
    }
}
