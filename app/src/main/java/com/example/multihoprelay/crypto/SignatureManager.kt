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

    private fun pssSignature(): Signature {
        val sig = Signature.getInstance("RSASSA-PSS")
        sig.setParameter(
            PSSParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1
            )
        )
        return sig
    }

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
        val sig = pssSignature()
        sig.initSign(privateKey)
        sig.update(data)
        return sig.sign()
    }

    fun verify(publicKey: PublicKey, data: ByteArray, signature: ByteArray): Boolean {
        return try {
            val sig = pssSignature()
            sig.initVerify(publicKey)
            sig.update(data)
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
}
