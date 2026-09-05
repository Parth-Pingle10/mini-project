package com.example.multihoprelay.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.multihoprelay.data.database.entity.RelayPacketEntity
import com.example.multihoprelay.model.RelayPacketState
import kotlinx.coroutines.flow.Flow

@Dao
interface RelayQueueDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: RelayPacketEntity): Long

    @Update
    suspend fun update(entity: RelayPacketEntity)

    @Delete
    suspend fun delete(entity: RelayPacketEntity)

    @Query("DELETE FROM relay_packets WHERE packetId = :packetId")
    suspend fun deleteById(packetId: String)

    @Query("SELECT * FROM relay_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun getById(packetId: String): RelayPacketEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM relay_packets WHERE packetId = :packetId)")
    suspend fun exists(packetId: String): Boolean

    @Query("SELECT * FROM relay_packets WHERE state = :state ORDER BY receivedAtMillis ASC")
    suspend fun getByState(state: RelayPacketState): List<RelayPacketEntity>

    @Query("SELECT * FROM relay_packets ORDER BY receivedAtMillis DESC")
    fun observeAll(): Flow<List<RelayPacketEntity>>

    @Query("SELECT * FROM relay_packets WHERE expiresAtMillis < :nowMillis")
    suspend fun getExpired(nowMillis: Long): List<RelayPacketEntity>

    @Query("DELETE FROM relay_packets WHERE expiresAtMillis < :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM relay_packets")
    suspend fun totalStorageBytes(): Long

    @Query("SELECT COUNT(*) FROM relay_packets WHERE state = :state")
    suspend fun countByState(state: RelayPacketState): Int

    @Query("DELETE FROM relay_packets")
    suspend fun clearAll()
}
