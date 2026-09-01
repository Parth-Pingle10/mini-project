package com.example.multihoprelay.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Result of AEAD-encrypting one chunk: a fresh random 12-byte nonce plus the ciphertext,
 * which has the 16-byte GCM authentication tag appended by the JCA provider.
 */
data class SealedBox(val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * Implements the hybrid end-to-end encryption scheme:
 *
 *   file -> random 256-bit AES file key -> AES-256-GCM per chunk (confidentiality + integrity)
 *   file key -> RSA-OAEP-SHA256, wrapped with the recipient's public key (key transport)
 *
 * The file is never encrypted directly with RSA (which cannot handle bulk data efficiently
 * or at all beyond its modulus size). Only the small symmetric key is asymmetrically wrapped.
 *
 * Relay nodes never see file keys or plaintext: they only ever handle the already-sealed
 * [SealedBox] ciphertext bytes and the RSA-wrapped key blob as opaque payload bytes inside
 * TRANSFER_INIT / CHUNK_DATA packets.
 */
class EncryptionManager {

    private val secureRandom = SecureRandom()

    /** Generates a fresh random AES-256 key, unique per transfer. Never reused across transfers. */
    fun generateFileKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, secureRandom)
        return keyGen.generateKey()
    }

    /** Wraps [fileKey] so only the holder of [recipientPublicKey]'s matching private key can read it. */
    fun wrapFileKey(fileKey: SecretKey, recipientPublicKey: PublicKey): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey, oaepSpec)
        return cipher.doFinal(fileKey.encoded)
    }

    /** Unwraps a file key using this device's Keystore-backed private identity key. */
    fun unwrapFileKey(wrapped: ByteArray, privateKey: PrivateKey): SecretKey {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val oaepSpec = OAEPParameterSpec(
            "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec)
        val rawKey = cipher.doFinal(wrapped)
        return SecretKeySpec(rawKey, "AES")
    }

    /**
     * Encrypts [plaintext] with AES-256-GCM under [fileKey]. [associatedData] should bind
     * context (transfer ID + chunk index) that must not be tampered with even though it is
     * not itself secret - GCM authenticates but does not encrypt the AAD.
     */
    fun encrypt(fileKey: SecretKey, plaintext: ByteArray, associatedData: ByteArray): SealedBox {
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, fileKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return SealedBox(nonce, ciphertext)
    }

    /**
     * Decrypts and verifies a [SealedBox]. Throws [javax.crypto.AEADBadTagException] (or a
     * generic [java.security.GeneralSecurityException]) if the ciphertext or [associatedData]
     * was tampered with, or the wrong key is used - callers MUST treat any exception here as
     * "reject this chunk", never fall back to treating partial output as valid.
     */
    fun decrypt(fileKey: SecretKey, box: SealedBox, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, fileKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, box.nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(box.ciphertext)
    }

    companion object {
        const val GCM_NONCE_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
