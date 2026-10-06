package com.debasish.livefit.watch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.watch.ui.WatchApp

class MainActivity : ComponentActivity() {
    private val perms = arrayOf(
        Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE",
        Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    /** A grant must clear the permission card (it shows a stale PermissionMissing error otherwise). */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WatchRuntime.controller.recheckPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRuntime.init(this) // also starts WatchClient's sync loop
        permissionRequest.launch(perms)
        setContent {
            val state by WatchClient.ui.collectAsStateWithLifecycle()
            WatchApp(state, onCommand = WatchClient::command, onVolume = WatchClient::setVolume, onGrantPermissions = { permissionRequest.launch(perms) })
        }
    }

    /** Also covers permissions granted in system Settings. */
    override fun onResume() {
        super.onResume()
        WatchRuntime.controller.recheckPermissions()
    }
}
