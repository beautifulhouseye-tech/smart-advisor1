package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.entity.HeaderItem
import com.example.network.security.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretVaultTest {

    private fun createVault(): SecretVault {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("secret_vault_test_${System.nanoTime()}", Context.MODE_PRIVATE)
        return SecretVault(context, prefs)
    }

    @Test
    fun `put get and remove store and delete secret values`() {
        val vault = createVault()

        vault.put("api_key", "sk-live-987654321")
        assertEquals("sk-live-987654321", vault.get("api_key"))

        vault.remove("api_key")
        assertEquals("", vault.get("api_key"))
    }

    @Test
    fun `redact replaces all stored secrets with redacted marker`() {
        val vault = createVault()
        vault.put("bearer", "tok_secret_abc123")
        vault.put("short", "tok_secret")

        val rawLog = "Calling https://api.test with Authorization: tok_secret_abc123 and prefix tok_secret"
        val redacted = vault.redact(rawLog)

        assertFalse(redacted.contains("tok_secret_abc123"))
        assertFalse(redacted.contains("tok_secret"))
        assertEquals(
            "Calling https://api.test with Authorization: ${SecretVault.REDACTED_MARKER} and prefix ${SecretVault.REDACTED_MARKER}",
            redacted
        )
    }

    @Test
    fun `resolveHeaders resolves secret refs from vault and preserves non-secret fallback`() {
        val vault = createVault()
        vault.put("auth_ref", "Bearer my-secret")

        val resolved = vault.resolveHeaders(
            listOf(
                HeaderItem(key = "Authorization", valueRef = "auth_ref", isSecret = true),
                HeaderItem(key = "MissingSecret", valueRef = "missing_ref", isSecret = true),
                HeaderItem(key = "X-App-Id", valueRef = "automate-v1", isSecret = false)
            )
        )

        assertEquals("Bearer my-secret", resolved["Authorization"])
        assertFalse(resolved.containsKey("MissingSecret"))
        assertEquals("automate-v1", resolved["X-App-Id"])
    }
}
