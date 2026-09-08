package com.example.multihoprelay.bluetooth

import java.util.UUID

/**
 * Custom GATT profile for the MultiHopRelay mesh protocol. Every installation of this app
 * advertises [SERVICE_UUID] so peers can recognize it as a protocol-compatible node during
 * BLE scanning, without exposing anything beyond a version number and a non-sensitive
 * discovery identifier in the advertisement itself (see [BleAdvertiser]).
 *
 * Communication pattern: each device runs BOTH a GATT server (peripheral role, via
 * [BleGattServer]) and, when it discovers a peer, a GATT client (central role, via
 * [BleGattClient]) - this is standard for symmetric BLE mesh apps, since BLE connections are
 * inherently client/server but we want any two nodes to be able to talk regardless of which
 * one happened to be scanning vs. advertising at the time. Once connected:
 *   - The GATT client WRITEs fragments to [CHARACTERISTIC_RX_UUID] on the server.
 *   - The GATT server NOTIFYs fragments to the client via [CHARACTERISTIC_TX_UUID] (after the
 *     client enables notifications through the standard Client Characteristic Configuration
 *     Descriptor, [CCCD_UUID]).
 * This gives a full-duplex byte stream over a single BLE connection, the same pattern used by
 * Nordic's UART service.
 */
object GattProfile {
    val SERVICE_UUID: UUID = UUID.fromString("6f6d6872-656c-6179-4d65-736831313131")
    val CHARACTERISTIC_RX_UUID: UUID = UUID.fromString("6f6d6872-656c-6179-4d65-736832323232")
    val CHARACTERISTIC_TX_UUID: UUID = UUID.fromString("6f6d6872-656c-6179-4d65-736833333333")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION = 1

    // Default (unnegotiated) ATT MTU is 23 bytes, of which 3 are ATT header, leaving 20 for
    // payload. We conservatively assume this until an MTU negotiation succeeds, then switch
    // to the negotiated (usually larger, up to 517) value for that specific connection.
    const val DEFAULT_ATT_PAYLOAD_SIZE = 20
    const val REQUESTED_MTU = 517
}
