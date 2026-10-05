package com.debasish.livefit.watch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Spike path B: phone sends a Data Layer message instead of launching the activity.
 * Tests whether a background-woken listener may start the health foreground service.
 */
class PhoneCommandListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val body = String(event.data)
        if (event.path == com.debasish.livefit.model.Protocol.PATH_STATE) {
            PhoneHub.init(this)
            PhoneHub.onState(body)
            return
        }
        Log.i(PhoneLink.TAG, "message ${event.path} $body")
        val force = body.contains("\"force\":true")
        runCatching {
            when (event.path) {
                PhoneLink.PATH_START -> startForegroundService(ExerciseService.intent(this, ExerciseService.ACTION_START, force))
                PhoneLink.PATH_STOP -> startService(ExerciseService.intent(this, ExerciseService.ACTION_STOP))
                PhoneLink.PATH_BATTERY_REQUEST -> {
                    val pct = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    com.google.android.gms.wearable.Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, PhoneLink.PATH_BATTERY, pct.toString().toByteArray())
                }
            }
        }.onFailure { Log.e(PhoneLink.TAG, "background start of ExerciseService failed", it) }
    }
}
