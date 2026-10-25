package com.example.multihoprelay.simulation

import com.example.multihoprelay.crypto.IdentityProvider
import com.example.multihoprelay.model.NodeId
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * A pure-JVM [IdentityProvider] backed by an ordinary (non-Keystore) `java.security` RSA key
 * pair. Used only by the Test/Simulation Mode screen and by unit tests, where there is no
 * Android Keystore available (plain JVM / Robolectric) and no real device to protect keys on
 * anyway. Never used for real transfers over BLE - see [com.example.multihoprelay.crypto.KeyManagerIdentityProvider]
 * for that.
 */
class SimulatedIdentityProvider(name: String) : IdentityProvider {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val id = NodeId.fromPublicKeyBytes(keyPair.public.encoded)

    val label: String = name

    override fun nodeId(): NodeId = id
    override fun publicKeyEncoded(): ByteArray = keyPair.public.encoded
    override fun privateKey(): PrivateKey = keyPair.private
    override fun decodePublicKey(bytes: ByteArray): PublicKey =
        java.security.KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(bytes))
}
