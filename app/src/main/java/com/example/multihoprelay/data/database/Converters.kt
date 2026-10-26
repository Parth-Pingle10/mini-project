package com.example.multihoprelay.data.database

import androidx.room.TypeConverter
import com.example.multihoprelay.model.RelayPacketState
import com.example.multihoprelay.model.TransferDirection
import com.example.multihoprelay.model.TransferState

class Converters {
    @TypeConverter
    fun fromTransferState(value: TransferState): String = value.name
    @TypeConverter
    fun toTransferState(value: String): TransferState =
        try {
            TransferState.valueOf(value)
        } catch (_: Exception) {
            TransferState.FAILED
        }

    @TypeConverter
    fun fromRelayPacketState(value: RelayPacketState): String = value.name
    @TypeConverter
    fun toRelayPacketState(value: String): RelayPacketState = RelayPacketState.valueOf(value)

    @TypeConverter
    fun fromTransferDirection(value: TransferDirection): String = value.name
    @TypeConverter
    fun toTransferDirection(value: String): TransferDirection = TransferDirection.valueOf(value)
}
