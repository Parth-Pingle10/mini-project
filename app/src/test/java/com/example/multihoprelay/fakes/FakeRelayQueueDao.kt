package com.example.multihoprelay.fakes

import com.example.multihoprelay.data.database.dao.RelayQueueDao
import com.example.multihoprelay.data.database.entity.RelayPacketEntity
import com.example.multihoprelay.model.RelayPacketState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory, thread-safe stand-in for the Room-generated [RelayQueueDao] implementation,
 * used to unit-test [com.example.multihoprelay.networking.RelayManager] without requiring
 * Robolectric or a real SQLite database. Behavior mirrors the real DAO's query semantics
 * closely enough for the relay/store-and-forward logic under test.
 */
class FakeRelayQueueDao : RelayQueueDao {

    private val storage = ConcurrentHashMap<String, RelayPacketEntity>()
    private val _all = MutableStateFlow<List<RelayPacketEntity>>(emptyList())

    private fun publish() {
        _all.value = storage.values.sortedByDescending { it.receivedAtMillis }
    }

    override suspend fun insert(entity: RelayPacketEntity): Long {
        if (storage.containsKey(entity.packetId)) return -1
        storage[entity.packetId] = entity
        publish()
        return 1
    }

    override suspend fun update(entity: RelayPacketEntity) {
        storage[entity.packetId] = entity
        publish()
    }

    override suspend fun delete(entity: RelayPacketEntity) {
        storage.remove(entity.packetId)
        publish()
    }

    override suspend fun deleteById(packetId: String) {
        storage.remove(packetId)
        publish()
    }

    override suspend fun getById(packetId: String): RelayPacketEntity? = storage[packetId]

    override suspend fun exists(packetId: String): Boolean = storage.containsKey(packetId)

    override suspend fun getByState(state: RelayPacketState): List<RelayPacketEntity> =
        storage.values.filter { it.state == state }

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<RelayPacketEntity>> = _all

    override suspend fun getExpired(nowMillis: Long): List<RelayPacketEntity> =
        storage.values.filter { it.expiresAtMillis < nowMillis }

    override suspend fun deleteExpired(nowMillis: Long): Int {
        val expired = getExpired(nowMillis)
        expired.forEach { storage.remove(it.packetId) }
        publish()
        return expired.size
    }

    override suspend fun totalStorageBytes(): Long = storage.values.sumOf { it.sizeBytes.toLong() }

    override suspend fun countByState(state: RelayPacketState): Int = storage.values.count { it.state == state }

    override suspend fun clearAll() {
        storage.clear()
        publish()
    }

    /** Test-only helper: a plain snapshot of everything currently stored (not part of the DAO interface). */
    fun snapshotAll(): List<RelayPacketEntity> = storage.values.toList()
}
