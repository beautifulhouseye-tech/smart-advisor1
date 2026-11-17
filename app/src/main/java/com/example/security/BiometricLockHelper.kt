package com.example.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class BiometricLockHelper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var currentActivityRef: WeakReference<FragmentActivity>? = null

    init {
        val app = context.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is FragmentActivity) {
                    currentActivityRef = WeakReference(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    currentActivityRef = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is FragmentActivity) {
                    currentActivityRef = WeakReference(activity)
                }
            }

            override fun onActivityStarted(activity: Activity) {
                if (activity is FragmentActivity) {
                    currentActivityRef = WeakReference(activity)
                }
            }

            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun isBiometricAvailable(): Boolean {
        return runCatching {
            val manager = BiometricManager.from(context)
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    suspend fun authenticate(
        title: String,
        subtitle: String,
        negativeButtonText: String = "إلغاء"
    ): Boolean {
        val activity = currentActivityRef?.get() ?: return false
        return authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            negativeButtonText = negativeButtonText
        )
    }

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButtonText: String = "إلغاء"
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (!isBiometricAvailable() || activity.isFinishing || activity.isDestroyed) {
            return@withContext false
        }

        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }

                override fun onAuthenticationFailed() {
                    // User can retry until prompt dismisses or errors out
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButtonText)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()

            continuation.invokeOnCancellation {
                runCatching { prompt.cancelAuthentication() }
            }

            runCatching {
                prompt.authenticate(promptInfo)
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }
}
