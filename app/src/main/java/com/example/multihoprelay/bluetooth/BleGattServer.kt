package com.example.multihoprelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import com.example.multihoprelay.util.MeshLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs this device's GATT server (peripheral role). Accepts incoming connections from other
 * nodes' GATT clients, receives fragment writes on the RX characteristic, and can push data
 * back to a specific connected central via notifications on the TX characteristic.
 */
class BleGattServer(
    private val context: Context,
    private val logger: MeshLogger,
    private val onFragmentReceived: (device: BluetoothDevice, bytes: ByteArray) -> Unit,
    private val onDeviceConnected: (device: BluetoothDevice) -> Unit,
    private val onDeviceDisconnected: (device: BluetoothDevice) -> Unit,
    private val onMtuChangedCallback: (device: BluetoothDevice, mtu: Int) -> Unit
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val notificationSubscribers = ConcurrentHashMap<String, Boolean>()
    private val notifyQueues = ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<ByteArray>>()
    private val notifyInFlight = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val notifyTimeoutRunnables = ConcurrentHashMap<String, Runnable>()

    internal fun createGattServerCallback(): BluetoothGattServerCallback {
        return object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    logger.debug("BLE", "GATT server: device connected ${device.address}")
                    connectedDevices[device.address] = device
                    onDeviceConnected(device)
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    logger.debug("BLE", "GATT server: device disconnected ${device.address}")
                    notifyTimeoutRunnables.remove(device.address)?.let { mainHandler.removeCallbacks(it) }
                    notificationSubscribers.remove(device.address)
                    notifyQueues.remove(device.address)
                    notifyInFlight.remove(device.address)
                    connectedDevices.remove(device.address)
                    onDeviceDisconnected(device)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (characteristic.uuid == GattProfile.CHARACTERISTIC_RX_UUID) {
                    onFragmentReceived(device, value)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (descriptor.uuid == GattProfile.CCCD_UUID) {
                    val enabled = value.isNotEmpty() && value[0] != 0.toByte()
                    notificationSubscribers[device.address] = enabled
                    logger.debug("BLE", "GATT server: notifications ${if (enabled) "enabled" else "disabled"} for ${device.address}")
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                logger.debug("BLE", "GATT server: MTU changed to $mtu for ${device.address}")
                onMtuChangedCallback(device, mtu)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                notifyTimeoutRunnables.remove(device.address)?.let { mainHandler.removeCallbacks(it) }
                notifyInFlight[device.address]?.set(false)
                drainNotifyQueue(device.address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rxCharacteristic = BluetoothGattCharacteristic(
            GattProfile.CHARACTERISTIC_RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val txCharacteristic = BluetoothGattCharacteristic(
            GattProfile.CHARACTERISTIC_TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            GattProfile.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txCharacteristic.addDescriptor(cccd)

        val service = BluetoothGattService(GattProfile.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(rxCharacteristic)
        service.addCharacteristic(txCharacteristic)

        val callback = createGattServerCallback()
        gattServer = bluetoothManager?.openGattServer(context, callback)
        val added = gattServer?.addService(service)
        if (added == null) {
            logger.error("BLE", "Failed to start GATT server - openGattServer returned null")
            return false
        }
        logger.info("BLE", "GATT server started")
        return true
    }

    /** Enqueues [data] to be sent as a notification to [device]; serialized per-device via onNotificationSent. */
    fun queueNotify(device: BluetoothDevice, data: ByteArray): Boolean {
        if (notificationSubscribers[device.address] != true) return false
        notifyQueues.getOrPut(device.address) { java.util.concurrent.ConcurrentLinkedDeque() }.add(data)
        drainNotifyQueue(device.address)
        return true
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun drainNotifyQueue(deviceAddress: String) {
        val server = gattServer ?: return
        val device = connectedDevices[deviceAddress] ?: return
        val queue = notifyQueues[deviceAddress] ?: return
        val flag = notifyInFlight.getOrPut(deviceAddress) { java.util.concurrent.atomic.AtomicBoolean(false) }
        if (!flag.compareAndSet(false, true)) return

        val next = queue.poll()
        if (next == null) {
            flag.set(false)
            return
        }
        val service = server.getService(GattProfile.SERVICE_UUID)
        val characteristic = service?.getCharacteristic(GattProfile.CHARACTERISTIC_TX_UUID)
        if (characteristic == null) {
            flag.set(false)
            return
        }
        characteristic.value = next
        val started = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, next) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else {
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
        } catch (e: Exception) {
            logger.warn("BLE", "notify failed for $deviceAddress: ${e.message}")
            false
        }
        if (!started) {
            queue.addFirst(next)
            flag.set(false)
            mainHandler.postDelayed({ drainNotifyQueue(deviceAddress) }, 50L)
        } else {
            notifyTimeoutRunnables[deviceAddress]?.let { mainHandler.removeCallbacks(it) }
            val timeoutRunnable = Runnable {
                if (flag.compareAndSet(true, false)) {
                    logger.warn("BLE", "Notification timeout (1500ms) for $deviceAddress; clearing notifyInFlight")
                    drainNotifyQueue(deviceAddress)
                }
            }
            notifyTimeoutRunnables[deviceAddress] = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, 1500L)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        notifyTimeoutRunnables.values.forEach { mainHandler.removeCallbacks(it) }
        notifyTimeoutRunnables.clear()
        try {
            gattServer?.close()
        } catch (e: Exception) {
            logger.debug("BLE", "GATT server close: ${e.message}")
        }
        gattServer = null
        notificationSubscribers.clear()
        notifyQueues.clear()
        notifyInFlight.clear()
        connectedDevices.clear()
    }
}
