package com.example.multihoprelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.multihoprelay.util.MeshLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class ClientConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

/**
 * Manages one outgoing GATT client connection to a single remote peer (this device acting as
 * BLE central toward that peer's GATT server). GATT operations on Android must be serialized
 * one-at-a-time per connection (the stack only allows one outstanding request), so writes are
 * queued and drained as each previous operation's callback completes.
 */
class BleGattClient(
    private val context: Context,
    private val device: BluetoothDevice,
    private val logger: MeshLogger,
    private val onFragmentReceived: (bytes: ByteArray) -> Unit,
    private val onStateChanged: (ClientConnectionState) -> Unit
) {
    private var gatt: BluetoothGatt? = null
    private val writeQueue = java.util.concurrent.ConcurrentLinkedDeque<ByteArray>()
    private val writeInFlight = AtomicBoolean(false)
    private val servicesDiscoveryStarted = AtomicBoolean(false)
    private var negotiatedMtu = GattProfile.DEFAULT_ATT_PAYLOAD_SIZE + 3
    private val mainHandler = Handler(Looper.getMainLooper())
    private var writeTimeoutRunnable: Runnable? = null

    val mtuPayloadSize: Int get() = negotiatedMtu - 3

    @SuppressLint("MissingPermission")
    fun connect() {
        servicesDiscoveryStarted.set(false)
        onStateChanged(ClientConnectionState.CONNECTING)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        servicesDiscoveryStarted.set(false)
        writeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        writeTimeoutRunnable = null
        writeQueue.clear()
        writeInFlight.set(false)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: Exception) {
            logger.debug("BLE", "GATT client disconnect: ${e.message}")
        }
        gatt = null
    }

    /** Enqueues a fragment for writing to the peer's RX characteristic. Serialized internally. */
    fun sendFragment(bytes: ByteArray) {
        writeQueue.add(bytes)
        drainQueueIfIdle()
    }

    @SuppressLint("MissingPermission")
    private fun drainQueueIfIdle() {
        val g = gatt ?: return
        if (!writeInFlight.compareAndSet(false, true)) return
        val next = writeQueue.poll()
        if (next == null) {
            writeInFlight.set(false)
            return
        }
        val service = g.getService(GattProfile.SERVICE_UUID)
        val characteristic = service?.getCharacteristic(GattProfile.CHARACTERISTIC_RX_UUID)
        if (characteristic == null) {
            writeInFlight.set(false)
            logger.error("BLE", "RX characteristic not found on ${device.address}")
            return
        }
        writeCharacteristicCompat(g, characteristic, next)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val res = g.writeCharacteristic(characteristic, value, writeType)
            if (res != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                logger.warn("BLE", "writeCharacteristic (API 33+) failed with status $res for ${device.address}")
                false
            } else {
                true
            }
        } else {
            characteristic.value = value
            characteristic.writeType = writeType
            val ok = g.writeCharacteristic(characteristic)
            if (!ok) {
                logger.warn("BLE", "writeCharacteristic failed to start for ${device.address}")
            }
            ok
        }

        if (started) {
            writeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            val timeoutRunnable = Runnable {
                if (writeInFlight.compareAndSet(true, false)) {
                    logger.warn("BLE", "Write timeout (1500ms) on ${device.address}; clearing writeInFlight")
                    drainQueueIfIdle()
                }
            }
            writeTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 1500L)
        } else {
            writeQueue.addFirst(value)
            writeInFlight.set(false)
            mainHandler.postDelayed({ drainQueueIfIdle() }, 50L)
        }
    }

    private val gattCallback = createGattCallback()

    @SuppressLint("MissingPermission")
    private fun proceedToServiceDiscovery(g: BluetoothGatt) {
        if (servicesDiscoveryStarted.compareAndSet(false, true)) {
            g.discoverServices()
        }
    }

    internal fun createGattCallback(): BluetoothGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.debug("BLE", "GATT client connected to ${device.address}, requesting MTU")
                    servicesDiscoveryStarted.set(false)
                    val started = try {
                        g.requestMtu(GattProfile.REQUESTED_MTU)
                    } catch (e: Exception) {
                        logger.warn("BLE", "requestMtu exception for ${device.address}: ${e.message}")
                        false
                    }
                    if (!started) {
                        logger.warn("BLE", "requestMtu failed to start for ${device.address}, falling back to discoverServices")
                        proceedToServiceDiscovery(g)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.debug("BLE", "GATT client disconnected from ${device.address} (status=$status)")
                    servicesDiscoveryStarted.set(false)
                    onStateChanged(ClientConnectionState.DISCONNECTED)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                logger.debug("BLE", "MTU negotiated: $mtu with ${device.address}")
            } else {
                logger.warn("BLE", "MTU negotiation failed with status=$status on ${device.address}")
            }
            proceedToServiceDiscovery(g)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.error("BLE", "Service discovery failed for ${device.address}: status=$status")
                onStateChanged(ClientConnectionState.FAILED)
                return
            }
            val service = g.getService(GattProfile.SERVICE_UUID)
            val txCharacteristic = service?.getCharacteristic(GattProfile.CHARACTERISTIC_TX_UUID)
            if (service == null || txCharacteristic == null) {
                logger.error("BLE", "Mesh service/characteristics not found on ${device.address}")
                onStateChanged(ClientConnectionState.FAILED)
                return
            }
            g.setCharacteristicNotification(txCharacteristic, true)
            val cccd = txCharacteristic.getDescriptor(GattProfile.CCCD_UUID)
            if (cccd != null) {
                writeDescriptorCompat(g, cccd)
            } else {
                onStateChanged(ClientConnectionState.CONNECTED)
                logger.info("BLE", "Connected and ready (no CCCD): ${device.address}")
            }
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == GattProfile.CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    logger.info("BLE", "CCCD notifications registered for ${device.address}")
                } else {
                    logger.warn("BLE", "CCCD write failed with status=$status on ${device.address}")
                }
                onStateChanged(ClientConnectionState.CONNECTED)
                logger.info("BLE", "Connected and ready: ${device.address}")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            writeTimeoutRunnable = null
            writeInFlight.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.warn("BLE", "Write failed to ${device.address}: status=$status")
            }
            drainQueueIfIdle()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == GattProfile.CHARACTERISTIC_TX_UUID) {
                val bytes = characteristic.value
                if (bytes != null && bytes.isNotEmpty()) {
                    onFragmentReceived(bytes)
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == GattProfile.CHARACTERISTIC_TX_UUID) {
                if (value.isNotEmpty()) {
                    onFragmentReceived(value)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(g: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val res = g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (res != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
                logger.warn("BLE", "writeDescriptor (API 33+) failed with status $res for ${device.address}")
            }
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val ok = g.writeDescriptor(descriptor)
            if (!ok) {
                logger.warn("BLE", "writeDescriptor failed to start for ${device.address}")
            }
        }
    }
}
