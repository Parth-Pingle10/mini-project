package com.example.multihoprelay.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class SignatureManagerTest {

    private val signatureManager = SignatureManager()

    private fun generateKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `sign then verify succeeds with correct key`() {
        val keyPair = generateKeyPair()
        val data = "transfer metadata: file.pdf, 1024 bytes".toByteArray()

        val signature = signatureManager.sign(keyPair.private, data)

        assertTrue(signatureManager.verify(keyPair.public, data, signature))
    }

    @Test
    fun `verify fails with wrong public key`() {
        val signerKeyPair = generateKeyPair()
        val otherKeyPair = generateKeyPair()
        val data = "important metadata".toByteArray()

        val signature = signatureManager.sign(signerKeyPair.private, data)

        assertFalse(signatureManager.verify(otherKeyPair.public, data, signature))
    }

    @Test
    fun `verify fails when data is tampered after signing`() {
        val keyPair = generateKeyPair()
        val original = "file.pdf 1024 bytes".toByteArray()
        val tampered = "file.pdf 9999 bytes".toByteArray()

        val signature = signatureManager.sign(keyPair.private, original)

        assertFalse(signatureManager.verify(keyPair.public, tampered, signature))
    }

    @Test
    fun `verify fails on malformed signature bytes rather than throwing`() {
        val keyPair = generateKeyPair()
        val data = "data".toByteArray()
        val garbage = ByteArray(16) { 0x42 }

        // Must not throw - verify() should safely return false for any malformed input.
        assertFalse(signatureManager.verify(keyPair.public, data, garbage))
    }
}
