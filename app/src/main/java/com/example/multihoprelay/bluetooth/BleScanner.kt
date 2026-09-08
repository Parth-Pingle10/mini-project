package com.example.multihoprelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.example.multihoprelay.model.NodeId
import com.example.multihoprelay.util.MeshLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One BLE scan observation: a discovered peer plus its current signal strength and device handle. */
data class ScanObservation(
    val nodeId: NodeId,
    val device: BluetoothDevice,
    val rssi: Int,
    val protocolVersion: Int
)

/**
 * Scans for nearby devices advertising [GattProfile.SERVICE_UUID].
 * Uses empty hardware scan filters for universal compatibility across all Android OEMs/chipsets,
 * and performs precise software filtering and decoding in [onScanResult].
 */
class BleScanner(private val context: Context, private val logger: MeshLogger) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter

    @SuppressLint("MissingPermission")
    fun scanFlow(): Flow<ScanObservation> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            logger.error("BLE", "[BLE][SCANNER] Cannot start scan - BLE scanner unavailable (Bluetooth off or unsupported)")
            close()
            return@callbackFlow
        }

        // Use empty scan filters to avoid hardware 128-bit filter drop bugs on various Android chipsets
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        val targetUuid = ParcelUuid(GattProfile.SERVICE_UUID)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val serviceUuids = record?.serviceUuids ?: emptyList()
                val serviceDataMap = record?.serviceData ?: emptyMap()

                // Check if this advertisement matches MultiHopRelay Service UUID
                val hasMatchingServiceUuid = serviceUuids.any { it == targetUuid }
                val matchingServiceData = record?.getServiceData(targetUuid)
                    ?: serviceDataMap.entries.firstOrNull { it.key == targetUuid }?.value

                if (!hasMatchingServiceUuid && matchingServiceData == null) {
                    logger.debug(
                        "BLE",
                        "[BLE][SCANNER] Device ignored: address=${result.device.address} reason=service UUID mismatch"
                    )
                    return
                }

                val deviceName = result.device.name ?: record?.deviceName ?: "MultiHopRelay"
                logger.info(
                    "BLE",
                    "[BLE][SCANNER] Device discovered: name=$deviceName address=${result.device.address} rssi=${result.rssi}"
                )

                if (matchingServiceData == null || matchingServiceData.size < 1 + NodeId.LENGTH) {
                    logger.debug(
                        "BLE",
                        "[BLE][SCANNER] MultiHopRelay device seen at ${result.device.address}, awaiting service data"
                    )
                    return
                }

                val version = matchingServiceData[0].toInt() and 0xFF
                val idBytes = matchingServiceData.copyOfRange(1, 1 + NodeId.LENGTH)
                val nodeId = try {
                    NodeId(String(idBytes, Charsets.US_ASCII))
                } catch (e: IllegalArgumentException) {
                    logger.warn("BLE", "[BLE][SCANNER] Malformed Node ID from ${result.device.address}")
                    return
                }

                logger.info(
                    "BLE",
                    "[BLE][SCANNER] MultiHopRelay Node ID parsed: ${nodeId.value} version=$version rssi=${result.rssi}"
                )
                trySend(ScanObservation(nodeId, result.device, result.rssi, version))
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "scan already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                    SCAN_FAILED_INTERNAL_ERROR -> "internal error"
                    else -> "unknown error $errorCode"
                }
                logger.error("BLE", "[BLE][SCANNER] Scan failed: $reason")
                close(RuntimeException("BLE scan failed: $reason"))
            }
        }

        try {
            scanner.startScan(emptyList(), settings, callback)
            logger.info("BLE", "[BLE][SCANNER] Scan started")
        } catch (e: Exception) {
            logger.error("BLE", "[BLE][SCANNER] Failed to start scan: ${e.message}")
            close(e)
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
                logger.info("BLE", "[BLE][SCANNER] Scan stopped")
            } catch (e: Exception) {
                logger.debug("BLE", "[BLE][SCANNER] stopScan: ${e.message}")
            }
        }
    }
}

