package com.copymore.android

import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

data class BackgroundStatus(
    val batteryExempt: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val backgroundRestricted: Boolean = false,
    val vivo: Boolean = Build.MANUFACTURER.equals("vivo", true)
)

object BackgroundSupport {
    fun read(context: Context): BackgroundStatus {
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("connection")
        return BackgroundStatus(
            batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE,
            backgroundRestricted = Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        )
    }

    fun battery(activity: Activity) {
        // User initiated. LAN reception is the core function and cannot use cloud push.
        open(activity, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun notifications(activity: Activity) = open(activity,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))

    // Some vivo releases protect the direct high-power screen with a signature permission.
    // Use the public battery settings entry; never invoke private permission controllers.
    fun vendorBattery(activity: Activity) = open(activity,
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))

    fun appDetails(activity: Activity) = open(activity,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))

    private fun open(activity: Activity, intent: Intent, fallback: Intent? = null) {
        if (runCatching { activity.startActivity(intent) }.isSuccess) return
        if (fallback != null && runCatching { activity.startActivity(fallback) }.isSuccess) return
        Toast.makeText(activity, "请在系统设置中打开 CopyMore 的应用信息", Toast.LENGTH_LONG).show()
    }
}
