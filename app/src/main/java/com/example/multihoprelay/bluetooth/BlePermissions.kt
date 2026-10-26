package com.example.multihoprelay.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralizes the runtime permission logic that changed significantly between Android
 * versions: pre-12 (API < 31) uses BLUETOOTH/BLUETOOTH_ADMIN (normal, install-time) plus
 * ACCESS_FINE_LOCATION (runtime) for scan results; API 31+ uses the new granular
 * BLUETOOTH_SCAN/BLUETOOTH_ADVERTISE/BLUETOOTH_CONNECT runtime permissions and, since we
 * declare `neverForLocation` on BLUETOOTH_SCAN, no location permission is required there.
 */
object BlePermissions {

    fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
            list += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return list.toTypedArray()
    }

    fun hasAllPermissions(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun isBluetoothSupported(context: Context): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val hasBle = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        return adapter != null && hasBle
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return adapter?.isEnabled == true
    }
}
