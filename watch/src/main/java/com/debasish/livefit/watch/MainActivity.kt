package com.debasish.livefit.watch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.flow.MutableStateFlow
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

    /**
     * B1: stay on screen in AOD with a low-power workout view instead of handing over to the watch face. Null while
     * interactive; otherwise the time of the last ambient refresh (the system asks for one about once a minute).
     */
    private val ambientTick = MutableStateFlow<Long?>(null)
    private val ambient = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { ambientTick.value = System.currentTimeMillis() }
        override fun onUpdateAmbient() { ambientTick.value = System.currentTimeMillis() }
        override fun onExitAmbient() { ambientTick.value = null } // live state (already current) renders right away
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambient)
        WatchRuntime.init(this) // also starts WatchClient's sync loop
        permissionRequest.launch(perms)
        setContent {
            val live by WatchClient.ui.collectAsStateWithLifecycle()
            val tick by ambientTick.collectAsState()
            // In ambient the screen changes only on the system's refresh, not on every frame from the phone.
            val state = tick?.let { remember(it) { WatchClient.ui.value } } ?: live
            WatchApp(state, onCommand = WatchClient::command, onVolume = WatchClient::setVolume, onGrantPermissions = { permissionRequest.launch(perms) }, tiles = WatchRuntime.tiles, ambient = tick != null)
        }
    }

    /** Also covers permissions granted in system Settings. */
    override fun onResume() {
        super.onResume()
        WatchRuntime.ensureExerciseService()
        WatchFront.onVisible(this, true)
        WatchRuntime.controller.recheckPermissions()
    }

    override fun onPause() {
        WatchFront.onVisible(this, false)
        super.onPause()
    }
}
