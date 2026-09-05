package com.example.multihoprelay.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.multihoprelay.model.RelayPacketState

/**
 * A packet this device is relaying on behalf of another node's transfer. The [payload] is
 * always the still-encrypted application payload exactly as it arrived - this device never
 * decrypts it (see [com.example.multihoprelay.networking.RelayManager]).
 */
@Entity(tableName = "relay_packets")
data class RelayPacketEntity(
    @PrimaryKey val packetId: String,
    val transferId: String?,
    val packetTypeCode: Int,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val nextHop: String,
    val hopCount: Int,
    val maxHopCount: Int,
    val sequenceNumber: Long,
    val payload: ByteArray,
    val sizeBytes: Int,
    val state: RelayPacketState,
    val receivedAtMillis: Long,
    val retryCount: Int = 0,
    val lastAttemptMillis: Long = 0L,
    val expiresAtMillis: Long
)
