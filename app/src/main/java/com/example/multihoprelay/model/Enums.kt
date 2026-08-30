package com.example.multihoprelay.model

/**
 * Every distinct application-layer packet type. Values are persisted in the wire format
 * as a single unsigned byte, so this enum must not exceed 255 entries and existing
 * ordinal values should not be reordered once devices are deployed.
 */
enum class PacketType(val code: Int) {
    // Routing / discovery
    HELLO(1),                  // periodic neighbor announcement (1-hop only, not forwarded)
    ROUTE_REQUEST(2),          // RREQ - flooded to discover a route to a destination
    ROUTE_REPLY(3),            // RREP - unicast back along the reverse path
    ROUTE_ERROR(4),            // RERR - notifies upstream nodes a route broke

    // Transfer control (end-to-end, relay only sees these as opaque-ish control frames
    // except for the routing header fields which are always in the clear)
    TRANSFER_INIT(10),         // sender -> receiver: transfer metadata (encrypted file key, file info)
    TRANSFER_ACCEPT(11),       // receiver -> sender: ready to receive
    TRANSFER_REJECT(12),       // receiver -> sender: declined
    CHUNK_DATA(13),            // sender -> receiver: one encrypted chunk
    CHUNK_ACK(14),             // receiver -> sender: chunk received + verified
    TRANSFER_COMPLETE(15),     // sender -> receiver: all chunks sent
    TRANSFER_CANCEL(16),       // either direction: abort transfer
    CHUNK_RESEND_REQUEST(17);  // receiver -> sender: request retransmission of missing chunks

    companion object {
        fun fromCode(code: Int): PacketType =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown packet type code: $code")
    }
}

enum class TransferDirection { OUTGOING, INCOMING }

/** High level transfer lifecycle state, shown in the UI and persisted in Room. */
enum class TransferState {
    DISCOVERING,
    ROUTE_FOUND,
    PREPARING,
    ENCRYPTING,
    TRANSFERRING,
    RELAYING,
    RECEIVING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    NO_ROUTE;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED

    val isFailure: Boolean
        get() = this == FAILED || this == CANCELLED || this == EXPIRED || this == NO_ROUTE
}

enum class RelayPacketState { QUEUED, FORWARDING, FORWARDED, EXPIRED, FAILED }

enum class LogLevel { DEBUG, INFO, WARN, ERROR }
