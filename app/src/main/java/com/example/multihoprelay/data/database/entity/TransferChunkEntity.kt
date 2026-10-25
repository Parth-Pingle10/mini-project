package com.example.multihoprelay.data.database.entity

import androidx.room.Entity

/**
 * Tracks per-chunk status for a transfer, used for duplicate detection, missing-chunk
 * detection, and resuming interrupted transfers without re-sending/re-receiving everything.
 */
@Entity(tableName = "transfer_chunks", primaryKeys = ["transferId", "chunkIndex"])
data class TransferChunkEntity(
    val transferId: String,
    val chunkIndex: Int,
    val sha256: ByteArray,
    val received: Boolean = false,
    val acknowledged: Boolean = false
)
