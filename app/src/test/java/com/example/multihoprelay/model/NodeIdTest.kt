package com.example.multihoprelay.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator

class NodeIdTest {

    @Test
    fun `fromPublicKeyBytes derives an 8-character uppercase hex id`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val id = NodeId.fromPublicKeyBytes(keyPair.public.encoded)

        assertEquals(8, id.value.length)
        assert(id.value.all { it.isDigit() || it in 'A'..'F' })
    }

    @Test
    fun `same public key always derives the same NodeId`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val id1 = NodeId.fromPublicKeyBytes(keyPair.public.encoded)
        val id2 = NodeId.fromPublicKeyBytes(keyPair.public.encoded)
        assertEquals(id1, id2)
    }

    @Test
    fun `different public keys derive different NodeIds (overwhelmingly likely)`() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val id1 = NodeId.fromPublicKeyBytes(kpg.generateKeyPair().public.encoded)
        val id2 = NodeId.fromPublicKeyBytes(kpg.generateKeyPair().public.encoded)
        assert(id1 != id2)
    }

    @Test
    fun `constructor rejects wrong length`() {
        assertThrows(IllegalArgumentException::class.java) { NodeId("ABC") }
        assertThrows(IllegalArgumentException::class.java) { NodeId("ABCDEFABC") }
    }

    @Test
    fun `constructor rejects lowercase or non-hex characters`() {
        assertThrows(IllegalArgumentException::class.java) { NodeId("abcdefab") }
        assertThrows(IllegalArgumentException::class.java) { NodeId("GHIJKLMN") }
    }

    @Test
    fun `fromString uppercases input`() {
        val id = NodeId.fromString("abcdef12")
        assertEquals("ABCDEF12", id.value)
    }
}
