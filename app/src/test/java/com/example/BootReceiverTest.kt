package com.example

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.preferences.SettingsDataStore
import com.example.receiver.BootReceiver
import com.example.service.AutoReplyKeepAliveService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BootReceiverTest {

    private lateinit var app: Application
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(app).clearStartedServices()
        settingsDataStore = EntryPointAccessors.fromApplication(
            app,
            BootReceiver.BootReceiverEntryPoint::class.java
        ).settingsDataStore()
    }

    @Test
    fun `onReceive does not start keep alive service when autoReplyEnabled is false`() {
        runBlocking { settingsDataStore.setAutoReplyEnabled(false) }

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        Thread.sleep(300)

        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun `onReceive starts keep alive service on BOOT_COMPLETED when autoReplyEnabled is true`() {
        runBlocking { settingsDataStore.setAutoReplyEnabled(true) }

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        var started: Intent? = null
        val deadline = System.currentTimeMillis() + 2_000L
        while (started == null && System.currentTimeMillis() < deadline) {
            started = Shadows.shadowOf(app).nextStartedService
            if (started == null) Thread.sleep(50)
        }
        assertEquals(AutoReplyKeepAliveService::class.java.name, started?.component?.className)
    }

    @Test
    fun `onReceive ignores unsupported actions even when autoReplyEnabled is true`() {
        runBlocking { settingsDataStore.setAutoReplyEnabled(true) }
        Thread.sleep(100)
        Shadows.shadowOf(app).clearStartedServices()

        BootReceiver().onReceive(app, Intent("com.example.UNSUPPORTED_ACTION"))
        Thread.sleep(300)

        assertNull(Shadows.shadowOf(app).nextStartedService)
    }
}
