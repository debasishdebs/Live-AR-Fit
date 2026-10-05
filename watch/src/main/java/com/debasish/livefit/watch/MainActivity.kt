package com.debasish.livefit.watch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.watch.ui.WatchApp

class MainActivity : ComponentActivity() {
    private val perms = arrayOf(
        Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE",
        Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRuntime.init(this) // also starts WatchClient's sync loop
        requestPermissions(perms, 1)
        setContent {
            val state by WatchClient.ui.collectAsStateWithLifecycle()
            WatchApp(state, onCommand = WatchClient::command, onVolume = WatchClient::setVolume, onGrantPermissions = { requestPermissions(perms, 1) })
        }
    }
}
