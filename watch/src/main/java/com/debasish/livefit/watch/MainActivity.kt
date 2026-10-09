package com.debasish.livefit.watch

import android.content.pm.PackageManager
import android.os.Build
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
import com.debasish.livefit.watch.ui.AmbientStyle
import com.debasish.livefit.watch.ui.WatchApp

class MainActivity : ComponentActivity() {
    /** Health + notifications first (spec §4: the heart-rate permission depends on the OS). */
    private val healthPerms = HealthPermissions.runtimeRequest(Build.VERSION.SDK_INT).toTypedArray()
    /** True while the location disclosure should show (spec §4: disclosure before the runtime prompt). */
    private val locationAsk = MutableStateFlow(false)
    /** A grant must clear the permission card (it shows a stale PermissionMissing error otherwise). */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WatchRuntime.controller.recheckPermissions()
        locationAsk.value = checkSelfPermission(HealthPermissions.FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && !locationAnswered
    }
    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationAsk.value = false }
    /** Asked once per process: "Not now" isn't repeated on every resume. */
    private var locationAnswered = false

    /**
     * B1: stay on screen in AOD with a low-power workout view instead of handing over to the watch face. Null while
     * interactive; otherwise the time of the last ambient refresh (the system asks for one about once a minute).
     */
    private val ambientTick = MutableStateFlow<Long?>(null)
    /** Burn-in protection / low-bit flags from the system's ambient details (Wear OS 3+ watches differ). */
    private val ambientStyle = MutableStateFlow(AmbientStyle.Default)
    private val ambient = AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambientStyle.value = AmbientStyle.of(ambientDetails.burnInProtectionRequired, ambientDetails.deviceHasLowBitAmbient)
            ambientTick.value = System.currentTimeMillis()
        }
        override fun onUpdateAmbient() { ambientTick.value = System.currentTimeMillis() }
        override fun onExitAmbient() { ambientTick.value = null } // live state (already current) renders right away
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambient)
        WatchRuntime.init(this) // also starts WatchClient's sync loop
        permissionRequest.launch(healthPerms)
        setContent {
            val askLocation by locationAsk.collectAsState()
            val live by WatchClient.ui.collectAsStateWithLifecycle()
            val tick by ambientTick.collectAsState()
            val aStyle by ambientStyle.collectAsState()
            // In ambient the screen changes only on the system's refresh, not on every frame from the phone.
            val state = tick?.let { remember(it) { WatchClient.ui.value } } ?: live
            WatchApp(state, onCommand = WatchClient::command, onVolume = WatchClient::setVolume, onGrantPermissions = { permissionRequest.launch(healthPerms) }, tiles = WatchRuntime.tiles, ambient = tick != null, ambientStyle = aStyle, ambientTick = (tick ?: 0L) / 60_000,
                locationDisclosure = askLocation,
                onLocationDisclosure = { ok ->
                    locationAnswered = true
                    if (ok) locationRequest.launch(HealthPermissions.LOCATION.toTypedArray()) else locationAsk.value = false
                },
            )
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
