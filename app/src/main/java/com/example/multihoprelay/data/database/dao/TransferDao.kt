package com.example.multihoprelay.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.multihoprelay.data.database.entity.TransferChunkEntity
import com.example.multihoprelay.data.database.entity.TransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransferEntity)

    @Update
    suspend fun update(entity: TransferEntity)

    @Query("SELECT * FROM transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getById(transferId: String): TransferEntity?

    @Query("SELECT * FROM transfers ORDER BY updatedAtMillis DESC")
    suspend fun getAll(): List<TransferEntity>

    @Query("SELECT * FROM transfers ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE transferId = :transferId LIMIT 1")
    fun observeById(transferId: String): Flow<TransferEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: TransferChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<TransferChunkEntity>)

    @Query("SELECT * FROM transfer_chunks WHERE transferId = :transferId ORDER BY chunkIndex ASC")
    suspend fun getChunks(transferId: String): List<TransferChunkEntity>

    @Query("SELECT * FROM transfer_chunks WHERE transferId = :transferId AND chunkIndex = :index LIMIT 1")
    suspend fun getChunk(transferId: String, index: Int): TransferChunkEntity?

    @Query("SELECT COUNT(*) FROM transfer_chunks WHERE transferId = :transferId AND received = 1")
    suspend fun countReceivedChunks(transferId: String): Int

    @Query("SELECT chunkIndex FROM transfer_chunks WHERE transferId = :transferId AND received = 0 ORDER BY chunkIndex ASC")
    suspend fun getMissingChunkIndices(transferId: String): List<Int>

    @Query("DELETE FROM transfers WHERE transferId = :transferId")
    suspend fun deleteTransfer(transferId: String)

    @Query("DELETE FROM transfer_chunks WHERE transferId = :transferId")
    suspend fun deleteChunksForTransfer(transferId: String)
}
