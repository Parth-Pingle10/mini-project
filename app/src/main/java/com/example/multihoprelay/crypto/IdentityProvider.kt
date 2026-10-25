package com.example.multihoprelay.crypto

import com.example.multihoprelay.model.NodeId
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Minimal surface [RoutingManager]/[TransferManager]/[RelayManager] need from a node's
 * cryptographic identity. Abstracting this out (rather than depending on [KeyManager]
 * directly, which requires the Android Keystore and thus a real device/emulator) is what
 * lets the routing/transfer/relay logic run identically under:
 *  - real BLE on a device ([KeyManager], Keystore-backed),
 *  - the in-app Test/Simulation Mode and plain JVM unit tests (a software-only
 *    implementation using `java.security.KeyPairGenerator`, see `simulation` package).
 */
interface IdentityProvider {
    fun nodeId(): NodeId
    fun publicKeyEncoded(): ByteArray
    fun privateKey(): PrivateKey
    fun decodePublicKey(bytes: ByteArray): PublicKey
}

/** Adapts the real, Keystore-backed [KeyManager] to the [IdentityProvider] contract. */
class KeyManagerIdentityProvider(private val keyManager: KeyManager) : IdentityProvider {
    override fun nodeId(): NodeId =
        keyManager.getNodeId() ?: throw IllegalStateException("Identity not yet generated")

    override fun publicKeyEncoded(): ByteArray = keyManager.getPublicKey().encoded
    override fun privateKey(): PrivateKey = keyManager.getPrivateKey()
    override fun decodePublicKey(bytes: ByteArray): PublicKey = keyManager.decodePublicKey(bytes)
}
