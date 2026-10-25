package com.example.multihoprelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.util.MeshLogger

sealed class AdvertiseResult {
    object Started : AdvertiseResult()
    data class Failed(val reason: String) : AdvertiseResult()
}

/**
 * Advertises this device's presence to nearby scanners. The advertisement intentionally
 * carries only the minimum needed for discovery:
 *  - Primary PDU: Application [GattProfile.SERVICE_UUID] (18 bytes total, fits within 31 bytes).
 *  - Scan Response PDU: Service Data carrying protocol version and [NodeId] (27 bytes total, fits within 31 bytes).
 */
class BleAdvertiser(private val context: Context, private val logger: MeshLogger) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun startAdvertising(nodeId: NodeId, onResult: (AdvertiseResult) -> Unit) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        logger.info("BLE", "[BLE][ADVERTISER] Starting advertising")
        logger.info("BLE", "[BLE][ADVERTISER] Service UUID = ${GattProfile.SERVICE_UUID}")
        logger.info("BLE", "[BLE][ADVERTISER] Node ID = ${nodeId.value}")

        if (advertiser == null) {
            val isEnabled = adapter?.isEnabled == true
            val isMultipleAdv = adapter?.isMultipleAdvertisementSupported == true
            val errorMsg = "BLE advertising not supported (Bluetooth enabled=$isEnabled, multipleAdvSupported=$isMultipleAdv)"
            logger.error("BLE", "[BLE][ADVERTISER] Advertising failed: $errorMsg")
            onResult(AdvertiseResult.Failed(errorMsg))
            return
        }
        stopAdvertising()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val serviceData = byteArrayOf(GattProfile.PROTOCOL_VERSION.toByte()) + nodeId.value.toByteArray(Charsets.US_ASCII)

        // Primary advertising data: 128-bit Service UUID (18 bytes <= 31 byte limit)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(GattProfile.SERVICE_UUID))
            .build()

        // Scan response: 128-bit Service Data with Version and Node ID (27 bytes <= 31 byte limit)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(GattProfile.SERVICE_UUID), serviceData)
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                logger.info("BLE", "[BLE][ADVERTISER] Advertising started successfully")
                onResult(AdvertiseResult.Started)
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "advertise data too large"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported on this device"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "internal adapter error"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many concurrent advertisers"
                    else -> "unknown error $errorCode"
                }
                logger.error("BLE", "[BLE][ADVERTISER] Advertising failed: $reason")
                onResult(AdvertiseResult.Failed(reason))
            }
        }
        callback = cb
        try {
            advertiser.startAdvertising(settings, advertiseData, scanResponse, cb)
        } catch (e: Exception) {
            logger.error("BLE", "[BLE][ADVERTISER] Advertising failed: ${e.message}")
            onResult(AdvertiseResult.Failed(e.message ?: "startAdvertising exception"))
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        callback?.let {
            try {
                advertiser.stopAdvertising(it)
                logger.info("BLE", "[BLE][ADVERTISER] Advertising stopped")
            } catch (e: Exception) {
                logger.debug("BLE", "[BLE][ADVERTISER] stopAdvertising: ${e.message}")
            }
        }
        callback = null
    }
}

