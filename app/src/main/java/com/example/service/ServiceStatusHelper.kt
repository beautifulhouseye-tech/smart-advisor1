package com.example.service

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

data class SystemPermissionsState(
    val isNotificationListenerEnabled: Boolean = false,
    val isAccessibilityEnabled: Boolean = false,
    val isPostNotificationsGranted: Boolean = true,
    val isActivityRecognitionGranted: Boolean = false
)

object ServiceStatusHelper {

    fun inspectPermissions(context: Context): SystemPermissionsState {
        val listenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val expectedComponent = ComponentName(context, AutoReplyAccessibilityService::class.java)
        val accessibilityEnabled = am?.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )?.any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo
            serviceInfo != null &&
                serviceInfo.packageName == expectedComponent.packageName &&
                serviceInfo.name == expectedComponent.className
        } ?: false

        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val activityRecognitionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return SystemPermissionsState(
            isNotificationListenerEnabled = listenerEnabled,
            isAccessibilityEnabled = accessibilityEnabled,
            isPostNotificationsGranted = postNotifGranted,
            isActivityRecognitionGranted = activityRecognitionGranted
        )
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
