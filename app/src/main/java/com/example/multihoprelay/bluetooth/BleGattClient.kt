package com.example.multihoprelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
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
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()
    private val writeInFlight = AtomicBoolean(false)
    private var negotiatedMtu = GattProfile.DEFAULT_ATT_PAYLOAD_SIZE + 3

    val mtuPayloadSize: Int get() = negotiatedMtu - 3

    @SuppressLint("MissingPermission")
    fun connect() {
        onStateChanged(ClientConnectionState.CONNECTING)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
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
        // See the note in BleGattServer re: deprecated setValue/writeCharacteristic pair -
        // kept for minSdk 26 compatibility; the callback-based flow is identical either way.
        characteristic.value = value
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = g.writeCharacteristic(characteristic)
        if (!started) {
            writeInFlight.set(false)
            logger.warn("BLE", "writeCharacteristic failed to start for ${device.address}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    logger.debug("BLE", "GATT client connected to ${device.address}, discovering services")
                    g.requestMtu(GattProfile.REQUESTED_MTU)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    logger.debug("BLE", "GATT client disconnected from ${device.address} (status=$status)")
                    onStateChanged(ClientConnectionState.DISCONNECTED)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                logger.debug("BLE", "MTU negotiated: $mtu with ${device.address}")
            }
            g.discoverServices()
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
            }
            onStateChanged(ClientConnectionState.CONNECTED)
            logger.info("BLE", "Connected and ready: ${device.address}")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeInFlight.set(false)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logger.warn("BLE", "Write failed to ${device.address}: status=$status")
            }
            drainQueueIfIdle()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == GattProfile.CHARACTERISTIC_TX_UUID) {
                onFragmentReceived(characteristic.value ?: return)
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(g: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(descriptor)
    }
}
