package com.example.multihoprelay

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MultiHopRelayApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.getInstance(this)
        container.ensureIdentity()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MESH_SERVICE_CHANNEL_ID,
                "Mesh networking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when MultiHopRelay is actively scanning, advertising, or relaying"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val MESH_SERVICE_CHANNEL_ID = "mesh_service_channel"
    }
}
