package com.example.multihoprelay.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.multihoprelay.data.database.dao.RelayQueueDao
import com.example.multihoprelay.data.database.dao.TransferDao
import com.example.multihoprelay.data.database.entity.RelayPacketEntity
import com.example.multihoprelay.data.database.entity.TransferChunkEntity
import com.example.multihoprelay.data.database.entity.TransferEntity

@Database(
    entities = [RelayPacketEntity::class, TransferEntity::class, TransferChunkEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun relayQueueDao(): RelayQueueDao
    abstract fun transferDao(): TransferDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "multihoprelay.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
