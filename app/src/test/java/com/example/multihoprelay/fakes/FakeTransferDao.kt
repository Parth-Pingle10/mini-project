package com.example.multihoprelay.fakes

import com.example.multihoprelay.data.database.dao.TransferDao
import com.example.multihoprelay.data.database.entity.TransferChunkEntity
import com.example.multihoprelay.data.database.entity.TransferEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory stand-in for the Room-generated [TransferDao], used to unit-test
 * [com.example.multihoprelay.networking.TransferManager] without Robolectric/SQLite.
 */
class FakeTransferDao : TransferDao {

    private val transfers = ConcurrentHashMap<String, TransferEntity>()
    // key: "transferId:chunkIndex"
    private val chunks = ConcurrentHashMap<String, TransferChunkEntity>()
    private val _all = MutableStateFlow<List<TransferEntity>>(emptyList())

    private fun publish() {
        _all.value = transfers.values.sortedByDescending { it.updatedAtMillis }
    }

    override suspend fun upsert(entity: TransferEntity) {
        transfers[entity.transferId] = entity
        publish()
    }

    override suspend fun update(entity: TransferEntity) {
        transfers[entity.transferId] = entity
        publish()
    }

    override suspend fun getById(transferId: String): TransferEntity? = transfers[transferId]

    override fun observeAll(): Flow<List<TransferEntity>> = _all

    override fun observeById(transferId: String): Flow<TransferEntity?> =
        MutableStateFlow(transfers[transferId])

    override suspend fun upsertChunk(chunk: TransferChunkEntity) {
        chunks["${chunk.transferId}:${chunk.chunkIndex}"] = chunk
    }

    override suspend fun upsertChunks(chunks: List<TransferChunkEntity>) {
        chunks.forEach { upsertChunk(it) }
    }

    override suspend fun getChunks(transferId: String): List<TransferChunkEntity> =
        chunks.values.filter { it.transferId == transferId }.sortedBy { it.chunkIndex }

    override suspend fun getChunk(transferId: String, index: Int): TransferChunkEntity? =
        chunks["$transferId:$index"]

    override suspend fun countReceivedChunks(transferId: String): Int =
        chunks.values.count { it.transferId == transferId && it.received }

    override suspend fun getMissingChunkIndices(transferId: String): List<Int> =
        chunks.values.filter { it.transferId == transferId && !it.received }.map { it.chunkIndex }.sorted()

    override suspend fun deleteTransfer(transferId: String) {
        transfers.remove(transferId)
        publish()
    }

    override suspend fun deleteChunksForTransfer(transferId: String) {
        chunks.keys.filter { it.startsWith("$transferId:") }.forEach { chunks.remove(it) }
    }
}
