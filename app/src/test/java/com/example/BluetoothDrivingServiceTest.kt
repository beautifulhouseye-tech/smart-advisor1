package com.example

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.BluetoothDrivingService
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBluetoothDevice

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BluetoothDrivingServiceTest {

    private fun createAdapter(): BluetoothAdapter {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    @Test
    fun `isCarConnected returns true for car keyword device name`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = createAdapter()
        val shadowAdapter = Shadows.shadowOf(adapter)
        shadowAdapter.setState(BluetoothAdapter.STATE_ON)

        val device = ShadowBluetoothDevice.newInstance("00:11:22:33:44:55")
        Shadows.shadowOf(device).setName("BMW iDrive Audio")
        shadowAdapter.setBondedDevices(setOf(device))

        val service = BluetoothDrivingService(
            context = context,
            onDrivingModeChanged = {},
            bluetoothAdapterProvider = { adapter },
            permissionChecker = { true }
        )

        assertTrue(service.isCarConnected())
    }

    @Test
    fun `isCarConnected returns true for AUDIO_VIDEO major device class`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = createAdapter()
        val shadowAdapter = Shadows.shadowOf(adapter)
        shadowAdapter.setState(BluetoothAdapter.STATE_ON)

        val device = ShadowBluetoothDevice.newInstance("00:11:22:33:44:66")
        Shadows.shadowOf(device).setName("Head Unit")
        val parcel = android.os.Parcel.obtain()
        parcel.writeInt(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
        parcel.setDataPosition(0)
        val btClass = BluetoothClass.CREATOR.createFromParcel(parcel)
        parcel.recycle()
        Shadows.shadowOf(device).setBluetoothClass(btClass)
        shadowAdapter.setBondedDevices(setOf(device))

        val service = BluetoothDrivingService(
            context = context,
            onDrivingModeChanged = {},
            bluetoothAdapterProvider = { adapter },
            permissionChecker = { true }
        )

        assertTrue(service.isCarConnected())
    }

    @Test
    fun `isCarConnected returns false when permission missing or non-car device`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = createAdapter()
        val shadowAdapter = Shadows.shadowOf(adapter)
        shadowAdapter.setState(BluetoothAdapter.STATE_ON)

        val device = ShadowBluetoothDevice.newInstance("00:11:22:33:44:77")
        Shadows.shadowOf(device).setName("Office Printer")
        shadowAdapter.setBondedDevices(setOf(device))

        val permittedService = BluetoothDrivingService(
            context = context,
            onDrivingModeChanged = {},
            bluetoothAdapterProvider = { adapter },
            permissionChecker = { true }
        )
        assertFalse(permittedService.isCarConnected())

        val deniedService = BluetoothDrivingService(
            context = context,
            onDrivingModeChanged = {},
            bluetoothAdapterProvider = { adapter },
            permissionChecker = { false }
        )
        assertFalse(deniedService.isCarConnected())
    }

    @Test
    fun `monitorConnection invokes callback only on state change`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val adapter = createAdapter()
        val shadowAdapter = Shadows.shadowOf(adapter)
        shadowAdapter.setState(BluetoothAdapter.STATE_ON)

        val carDevice = ShadowBluetoothDevice.newInstance("AA:BB:CC:DD:EE:FF")
        Shadows.shadowOf(carDevice).setName("Toyota Car Multimedia")
        shadowAdapter.setBondedDevices(setOf(carDevice))

        val events = mutableListOf<Boolean>()
        val service = BluetoothDrivingService(
            context = context,
            onDrivingModeChanged = { events.add(it) },
            bluetoothAdapterProvider = { adapter },
            permissionChecker = { true }
        )

        service.monitorConnection(pollIntervalMs = 1L, maxIterations = 3)
        assertEquals(listOf(true), events)
    }
}
