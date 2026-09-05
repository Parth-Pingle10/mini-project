package com.example.multihoprelay.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferState

/**
 * Persisted state of a transfer this device originated or is receiving, so progress survives
 * process death and interrupted transfers can be resumed. Sensitive material stored here
 * ([wrappedFileKeyForSender] and [rawFileKeyForSender]) never leaves this device: it is not
 * part of any packet sent to relays or peers beyond what the protocol already transmits.
 */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val direction: TransferDirection,
    val peerNodeId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val chunkSizeBytes: Int,
    val totalChunks: Int,
    val chunksDone: Int = 0,
    val state: TransferState,
    val routeCsv: String, // comma-separated NodeId path, for display only
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val errorMessage: String? = null,
    val localFileUri: String? = null,       // sender: source content URI; receiver: staging/output path
    val outputFilePath: String? = null,      // receiver: final decrypted file path once COMPLETED
    val fileSha256: ByteArray? = null,
    // Sender-only: the raw AES-256 file key, kept only in this device's app-private database
    // so an interrupted send can resume without re-deriving/re-negotiating. Never transmitted.
    val rawFileKeyForSender: ByteArray? = null,
    // Receiver-only: the RSA-OAEP wrapped file key as received in TRANSFER_INIT, plus the
    // sender's public key and signature, kept so verification can be redone/audited.
    val wrappedFileKeyForReceiver: ByteArray? = null,
    val senderPublicKeyForReceiver: ByteArray? = null,
    val transferInitSignature: ByteArray? = null
)
