package com.example.multihoprelay.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.multihoprelay.model.NodeId
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Owns this installation's cryptographic identity.
 *
 * Design:
 *  - A 2048-bit RSA key pair is generated inside the Android Keystore on first launch.
 *    StrongBox hardware backing is attempted when supported by the device (API 28+);
 *    if StrongBox is unavailable on the hardware, key generation automatically and
 *    explicitly falls back to regular Android Keystore security.
 *    The private key material never leaves the secure hardware/OS keystore and is never
 *    exported, logged, or transmitted.
 *  - The same key pair serves two purposes to keep the demo/protocol simple:
 *      1. Key transport: RSA-OAEP is used to wrap/unwrap the random per-transfer AES file key
 *         (hybrid encryption - see [EncryptionManager]).
 *      2. Authentication: RSA-PSS signatures over transfer metadata (see [SignatureManager]).
 *    A production system would typically use separate keys for signing vs. encryption; this
 *    is documented as a simplification in SECURITY.md.
 *  - The public key is stored (non-sensitive) so it can be displayed / exported for peers.
 *  - The NodeId is derived from SHA-256(publicKey.encoded), truncated to 4 bytes - it reveals
 *    no personal information and cannot be reversed to recover the public key.
 */
class KeyManager(private val context: Context) {

    private val androidKeyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "identity_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Returns true if an identity has already been generated on this device. */
    fun hasIdentity(): Boolean = androidKeyStore.containsAlias(IDENTITY_KEY_ALIAS)

    /**
     * Generates a fresh identity, destroying any previous one. Only call this on first
     * launch, or explicitly from Settings -> Reset Identity (which the user must confirm,
     * since it invalidates this device's ability to decrypt any transfers addressed to
     * the old identity and changes its NodeId as seen by peers).
     */
    fun generateIdentity(): NodeId {
        if (androidKeyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
            androidKeyStore.deleteEntry(IDENTITY_KEY_ALIAS)
        }

        val keyPair: KeyPair = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generateKeyPairWithSpec(useStrongBox = true)
            } catch (e: Exception) {
                Log.w(TAG, "[IDENTITY] StrongBox unavailable; falling back to regular Android Keystore", e)
                cleanupAlias()
                generateKeyPairWithSpec(useStrongBox = false)
            }
        } else {
            generateKeyPairWithSpec(useStrongBox = false)
        }

        val nodeId = NodeId.fromPublicKeyBytes(keyPair.public.encoded)
        prefs.edit().putString(PREF_NODE_ID, nodeId.value).apply()
        return nodeId
    }

    private fun generateKeyPairWithSpec(useStrongBox: Boolean): KeyPair {
        val spec = buildKeyGenParameterSpec(useStrongBox)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        kpg.initialize(spec)
        return kpg.generateKeyPair()
    }

    private fun buildKeyGenParameterSpec(useStrongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            IDENTITY_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT or
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_SHA1)
            .setBlockModes(KeyProperties.BLOCK_MODE_ECB)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setUserAuthenticationRequired(false)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun cleanupAlias() {
        try {
            if (androidKeyStore.containsAlias(IDENTITY_KEY_ALIAS)) {
                androidKeyStore.deleteEntry(IDENTITY_KEY_ALIAS)
            }
        } catch (_: Exception) {
            // Best effort cleanup
        }
    }

    fun getNodeId(): NodeId? = prefs.getString(PREF_NODE_ID, null)?.let { NodeId.fromString(it) }

    fun getPrivateKey(): PrivateKey {
        val entry = androidKeyStore.getEntry(IDENTITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("No identity key found - call generateIdentity() first")
        return entry.privateKey
    }

    fun getPublicKey(): PublicKey {
        val entry = androidKeyStore.getEntry(IDENTITY_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("No identity key found - call generateIdentity() first")
        return entry.certificate.publicKey
    }

    fun getPublicKeyBase64(): String = Base64.getEncoder().encodeToString(getPublicKey().encoded)

    /** Reconstructs a peer's RSA public key from bytes received over BLE (e.g. in HELLO/ROUTE_REPLY). */
    fun decodePublicKey(encoded: ByteArray): PublicKey {
        val spec = java.security.spec.X509EncodedKeySpec(encoded)
        val kf = java.security.KeyFactory.getInstance("RSA")
        return kf.generatePublic(spec) as RSAPublicKey
    }

    /**
     * Destroys the current identity key and all cached peer keys / trust state. Use only for
     * the explicit "Reset Identity" settings action after user confirmation. This is
     * irreversible: peers will see this device as a brand-new, unrelated NodeId afterwards,
     * and any in-flight transfers keyed to the old identity can no longer be decrypted.
     */
    fun resetIdentity(): NodeId {
        prefs.edit().clear().apply()
        return generateIdentity()
    }

    companion object {
        private const val TAG = "KeyManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val IDENTITY_KEY_ALIAS = "multihoprelay_identity_key"
        private const val PREF_NODE_ID = "node_id"
    }
}

