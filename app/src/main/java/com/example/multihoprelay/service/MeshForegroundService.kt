package com.example.multihoprelay.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.multihoprelay.MainActivity
import com.example.multihoprelay.MultiHopRelayApp
import com.example.multihoprelay.R

/**
 * A foreground service that keeps BLE scanning/advertising/GATT connections alive while the
 * app has an active transfer or Relay Mode enabled, in line with Android's background
 * execution limits (a plain background app process can be killed or have its BLE scanning
 * throttled at any time - a foreground service with a visible notification is the documented,
 * user-transparent way to avoid that for as long as the feature is genuinely in use).
 *
 * This service does NOT attempt to run BLE indefinitely in the background without the user's
 * awareness: the persistent notification is always visible whenever this service is running,
 * and the user can stop Relay Mode / cancel transfers from the app to stop it.
 */
class MeshForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MultiHopRelayApp
        app.container.startMesh()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as MultiHopRelayApp
        app.container.stopMesh()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MultiHopRelayApp.MESH_SERVICE_CHANNEL_ID)
            .setContentTitle("MultiHopRelay is active")
            .setContentText("Scanning for nearby nodes and handling transfers/relay traffic")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
    }
}
