package com.debasish.livefit.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * F1: brings the hub back after an APK update (MY_PACKAGE_REPLACED) or a reboot (BOOT_COMPLETED); nothing else would
 * until the user opened the app. Both broadcasts are exempt from the background foreground-service start restriction
 * (Android 12+), and Android 15's BOOT_COMPLETED limits don't cover connectedDevice; Android 14's connectedDevice
 * prerequisite (BLUETOOTH_CONNECT or a companion association) is checked by ensureRunning. Guarded: never crash a broadcast.
 */
class HubRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            LiveFitHubService.ensureRunning(context)
        } catch (e: Exception) {
            Log.w("LiveFitHub", "hub restart after ${intent.action} failed", e)
        }
    }
}
