package com.example.multihoprelay.model

import java.util.UUID

/** In-memory / UI-facing view of a file transfer this node originated or is receiving. */
data class TransferInfo(
    val transferId: UUID,
    val direction: TransferDirection,
    val peerNodeId: NodeId,
    val fileName: String,
    val fileSizeBytes: Long,
    val totalChunks: Int,
    val chunksDone: Int,
    val state: TransferState,
    val route: List<NodeId> = emptyList(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalChunks == 0) 0f else chunksDone.toFloat() / totalChunks.toFloat()
}

/** Metadata describing a chunk. Sent inside TRANSFER_INIT so the receiver can preallocate. */
data class ChunkMeta(
    val index: Int,
    val sha256: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is ChunkMeta && index == other.index && sha256.contentEquals(other.sha256)

    override fun hashCode(): Int = index
}
