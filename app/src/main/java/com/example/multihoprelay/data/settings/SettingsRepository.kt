package com.example.multihoprelay.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class RelaySettings(
    val relayModeEnabled: Boolean = true,
    val maxRelayStorageBytes: Long = 500L * 1024 * 1024, // 500 MB
    val maxPacketAgeMillis: Long = 30L * 60 * 1000,      // 30 minutes
    val maxHops: Int = 8,
    val maxConcurrentRelays: Int = 3,
    val maxBandwidthBytesPerSecond: Long = 250L * 1024   // 250 KB/s, throttles forwarding
)

/**
 * Persists user-configurable relay limits and app preferences (backed by Jetpack DataStore,
 * the modern replacement for SharedPreferences). Read via [relaySettingsFlow] anywhere a
 * reactive view is needed (e.g. Compose UI, [com.example.multihoprelay.networking.RelayManager]).
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val RELAY_MODE = booleanPreferencesKey("relay_mode_enabled")
        val MAX_STORAGE = longPreferencesKey("max_relay_storage_bytes")
        val MAX_AGE = longPreferencesKey("max_packet_age_millis")
        val MAX_HOPS = intPreferencesKey("max_hops")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent_relays")
        val MAX_BANDWIDTH = longPreferencesKey("max_bandwidth_bps")
    }

    val relaySettingsFlow: Flow<RelaySettings> = context.dataStore.data.map { prefs ->
        RelaySettings(
            relayModeEnabled = prefs[Keys.RELAY_MODE] ?: true,
            maxRelayStorageBytes = prefs[Keys.MAX_STORAGE] ?: RelaySettings().maxRelayStorageBytes,
            maxPacketAgeMillis = prefs[Keys.MAX_AGE] ?: RelaySettings().maxPacketAgeMillis,
            maxHops = prefs[Keys.MAX_HOPS] ?: RelaySettings().maxHops,
            maxConcurrentRelays = prefs[Keys.MAX_CONCURRENT] ?: RelaySettings().maxConcurrentRelays,
            maxBandwidthBytesPerSecond = prefs[Keys.MAX_BANDWIDTH] ?: RelaySettings().maxBandwidthBytesPerSecond
        )
    }

    suspend fun setRelayModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.RELAY_MODE] = enabled }
    }

    suspend fun setMaxRelayStorageBytes(bytes: Long) {
        context.dataStore.edit { it[Keys.MAX_STORAGE] = bytes }
    }

    suspend fun setMaxPacketAgeMillis(millis: Long) {
        context.dataStore.edit { it[Keys.MAX_AGE] = millis }
    }

    suspend fun setMaxHops(hops: Int) {
        context.dataStore.edit { it[Keys.MAX_HOPS] = hops }
    }

    suspend fun setMaxConcurrentRelays(count: Int) {
        context.dataStore.edit { it[Keys.MAX_CONCURRENT] = count }
    }

    suspend fun setMaxBandwidthBytesPerSecond(bps: Long) {
        context.dataStore.edit { it[Keys.MAX_BANDWIDTH] = bps }
    }
}
