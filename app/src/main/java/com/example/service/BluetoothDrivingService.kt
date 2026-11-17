package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.driving.DrivingModeManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothDrivingService internal constructor(
    private val context: Context,
    private val onDrivingModeChanged: suspend (Boolean) -> Unit,
    private val bluetoothAdapterProvider: () -> BluetoothAdapter?,
    private val permissionChecker: () -> Boolean
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        drivingModeManager: DrivingModeManager
    ) : this(
        context = context,
        onDrivingModeChanged = { enabled -> drivingModeManager.setDrivingMode(enabled) },
        bluetoothAdapterProvider = {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        },
        permissionChecker = { hasBluetoothPermission(context) }
    )

    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothAdapterProvider()

    @SuppressLint("MissingPermission")
    fun isCarConnected(): Boolean {
        if (!permissionChecker()) return false
        val adapter = bluetoothAdapter ?: return false
        val isEnabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!isEnabled) return false

        val bondedDevices = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        return bondedDevices.any { device -> isCarDevice(device) }
    }

    @SuppressLint("MissingPermission")
    internal fun isCarDevice(device: BluetoothDevice): Boolean {
        val name = runCatching { device.name }.getOrNull().orEmpty().lowercase()
        val matchesKeyword = CAR_KEYWORDS.any { keyword -> name.contains(keyword) }
        val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        val isAudioVideo = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        return matchesKeyword || isAudioVideo
    }

    suspend fun monitorConnection(
        pollIntervalMs: Long = POLL_INTERVAL_MS,
        maxIterations: Int = Int.MAX_VALUE
    ) {
        var lastConnectedState: Boolean? = null
        var iterations = 0
        while (currentCoroutineContext().isActive && iterations < maxIterations) {
            iterations++
            try {
                val connected = isCarConnected()
                if (lastConnectedState != connected) {
                    lastConnectedState = connected
                    onDrivingModeChanged(connected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep monitoring even if Bluetooth stack reports transient state errors
            }
            if (iterations < maxIterations) {
                delay(pollIntervalMs)
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 10_000L
        private val CAR_KEYWORDS = listOf(
            "car",
            "auto",
            "vehicle",
            "bmw",
            "toyota",
            "mercedes"
        )

        fun hasBluetoothPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Manifest.permission.BLUETOOTH_CONNECT
            } else {
                Manifest.permission.BLUETOOTH
            }
            return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
