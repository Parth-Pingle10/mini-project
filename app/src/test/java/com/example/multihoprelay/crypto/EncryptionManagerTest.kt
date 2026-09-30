package com.example.multihoprelay.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import javax.crypto.AEADBadTagException

class EncryptionManagerTest {

    private val encryptionManager = EncryptionManager()

    @Test
    fun `encrypt then decrypt returns original plaintext`() {
        val fileKey = encryptionManager.generateFileKey()
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()
        val aad = "transfer-123-chunk-0".toByteArray()

        val sealed = encryptionManager.encrypt(fileKey, plaintext, aad)
        val decrypted = encryptionManager.decrypt(fileKey, sealed, aad)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `each encryption uses a fresh random nonce`() {
        val fileKey = encryptionManager.generateFileKey()
        val plaintext = ByteArray(64) { it.toByte() }

        val sealed1 = encryptionManager.encrypt(fileKey, plaintext, ByteArray(0))
        val sealed2 = encryptionManager.encrypt(fileKey, plaintext, ByteArray(0))

        assertFalse("nonces must differ between encryptions", sealed1.nonce.contentEquals(sealed2.nonce))
        assertFalse(
            "ciphertexts must differ due to different nonces even for identical plaintext",
            sealed1.ciphertext.contentEquals(sealed2.ciphertext)
        )
    }

    @Test
    fun `decrypt with wrong key fails authentication`() {
        val correctKey = encryptionManager.generateFileKey()
        val wrongKey = encryptionManager.generateFileKey()
        val plaintext = "secret payload".toByteArray()
        val sealed = encryptionManager.encrypt(correctKey, plaintext, ByteArray(0))

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            encryptionManager.decrypt(wrongKey, sealed, ByteArray(0))
        }
    }

    @Test
    fun `decrypt with tampered ciphertext fails authentication`() {
        val fileKey = encryptionManager.generateFileKey()
        val plaintext = "do not tamper with me".toByteArray()
        val sealed = encryptionManager.encrypt(fileKey, plaintext, ByteArray(0))

        val tampered = sealed.ciphertext.copyOf()
        tampered[0] = (tampered[0] + 1).toByte()
        val tamperedSealed = sealed.copy(ciphertext = tampered)

        assertThrows(AEADBadTagException::class.java) {
            encryptionManager.decrypt(fileKey, tamperedSealed, ByteArray(0))
        }
    }

    @Test
    fun `decrypt with tampered associated data fails authentication`() {
        val fileKey = encryptionManager.generateFileKey()
        val plaintext = "context matters".toByteArray()
        val sealed = encryptionManager.encrypt(fileKey, plaintext, "chunk-0".toByteArray())

        assertThrows(AEADBadTagException::class.java) {
            encryptionManager.decrypt(fileKey, sealed, "chunk-1".toByteArray())
        }
    }

    @Test
    fun `decrypt with tampered nonce fails authentication or produces garbage never silently succeeds with wrong data`() {
        val fileKey = encryptionManager.generateFileKey()
        val plaintext = "nonce integrity".toByteArray()
        val sealed = encryptionManager.encrypt(fileKey, plaintext, ByteArray(0))
        val tamperedNonce = sealed.nonce.copyOf()
        tamperedNonce[0] = (tamperedNonce[0] + 1).toByte()

        assertThrows(AEADBadTagException::class.java) {
            encryptionManager.decrypt(fileKey, sealed.copy(nonce = tamperedNonce), ByteArray(0))
        }
    }

    @Test
    fun `generateFileKey produces distinct 256-bit keys`() {
        val key1 = encryptionManager.generateFileKey()
        val key2 = encryptionManager.generateFileKey()
        assertEquals(32, key1.encoded.size) // 256 bits
        assertNotEquals(key1.encoded.toList(), key2.encoded.toList())
    }

    @Test
    fun `wrapFileKey then unwrapFileKey recovers the same key`() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = kpg.generateKeyPair()

        val fileKey = encryptionManager.generateFileKey()
        val wrapped = encryptionManager.wrapFileKey(fileKey, keyPair.public)
        val unwrapped = encryptionManager.unwrapFileKey(wrapped, keyPair.private)

        assertArrayEquals(fileKey.encoded, unwrapped.encoded)
    }

    @Test
    fun `unwrapFileKey with wrong private key fails`() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val recipientKeyPair = kpg.generateKeyPair()
        val attackerKeyPair = kpg.generateKeyPair()

        val fileKey = encryptionManager.generateFileKey()
        val wrapped = encryptionManager.wrapFileKey(fileKey, recipientKeyPair.public)

        assertThrows(Exception::class.java) {
            encryptionManager.unwrapFileKey(wrapped, attackerKeyPair.private)
        }
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}
