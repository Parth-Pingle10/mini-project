package com.example.multihoprelay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.test.core.app.ApplicationProvider
import com.example.multihoprelay.util.MeshLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleGattServerTest {

    @Test
    fun onMtuChanged_delegatesToCallbackExactlyOnceWithoutRecursion() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice = adapter.getRemoteDevice("11:22:33:44:55:66")
        val logger = MeshLogger()

        val callCount = AtomicInteger(0)
        var receivedDevice: BluetoothDevice? = null
        var receivedMtu = 0

        val server = BleGattServer(
            context = ApplicationProvider.getApplicationContext(),
            logger = logger,
            onFragmentReceived = { _, _ -> },
            onDeviceConnected = {},
            onDeviceDisconnected = {},
            onMtuChangedCallback = { dev, mtu ->
                callCount.incrementAndGet()
                receivedDevice = dev
                receivedMtu = mtu
            }
        )

        val callback = server.createGattServerCallback()

        // Prior to the fix, this call triggered infinite recursion and threw java.lang.StackOverflowError.
        callback.onMtuChanged(device, 517)

        assertEquals("onMtuChangedCallback must be invoked exactly once", 1, callCount.get())
        assertEquals(device, receivedDevice)
        assertEquals(517, receivedMtu)
    }
}
