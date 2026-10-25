package com.example.multihoprelay.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec

/**
 * Signs and verifies transfer metadata (e.g. the TRANSFER_INIT header: transfer ID, file
 * name, file size, chunk count, file-key hash) so the receiver can confirm the sender's
 * claimed identity actually matches the node holding the corresponding private key, and
 * that no relay altered the metadata in transit.
 *
 * Uses RSA-PSS (SHA-256, MGF1) - a randomized, modern padding scheme preferred over PKCS#1 v1.5.
 */
class SignatureManager {

    private val algorithms = listOf("SHA256withRSA/PSS", "RSASSA-PSS")
    private val saltLengths = listOf(32, 20)

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        var lastException: Exception? = null
        for (algo in algorithms) {
            try {
                val sig = Signature.getInstance(algo)
                try {
                    sig.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
                } catch (_: Exception) {}
                sig.initSign(privateKey)
                sig.update(data)
                return sig.sign()
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: IllegalStateException("Failed to sign data")
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        for (algo in algorithms) {
            for (saltLen in saltLengths) {
                try {
                    val sig = Signature.getInstance(algo)
                    try {
                        sig.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, saltLen, 1))
                    } catch (_: Exception) {}
                    sig.initVerify(publicKey)
                    sig.update(data)
                    if (sig.verify(signature)) return true
                } catch (_: Exception) {}
            }
            try {
                val sig = Signature.getInstance(algo)
                sig.initVerify(publicKey)
                sig.update(data)
                if (sig.verify(signature)) return true
            } catch (_: Exception) {}
        }
        return false
    }
}
