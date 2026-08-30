package com.example.multihoprelay.model

/**
 * A short, non-sensitive identifier for a node, derived from the SHA-256 hash of its
 * public identity key. This is NOT the public key itself (which is larger and exchanged
 * separately) - it is a compact label used for routing, UI display, and addressing.
 *
 * Example: "7F21A93C"
 */
@JvmInline
value class NodeId(val value: String) {
    init {
        require(value.length == LENGTH) { "NodeId must be $LENGTH hex chars, was '$value'" }
        require(value.all { it.isDigit() || it in 'A'..'F' }) { "NodeId must be uppercase hex" }
    }

    override fun toString(): String = value

    companion object {
        const val LENGTH = 8

        /** Derives a NodeId from a public key's encoded bytes via SHA-256, first 4 bytes as hex. */
        fun fromPublicKeyBytes(publicKeyBytes: ByteArray): NodeId {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            val shortHash = digest.copyOfRange(0, 4)
            val hex = shortHash.joinToString("") { "%02X".format(it) }
            return NodeId(hex)
        }

        fun fromString(s: String): NodeId = NodeId(s.uppercase())

        /** A reserved broadcast destination used for route-discovery packets. */
        val BROADCAST = NodeId("FFFFFFFF")
    }
}
