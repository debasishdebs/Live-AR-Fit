package com.debasish.livefit.watch

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.debasish.livefit.watch.ui.WatchApp

/** Entry point; also the target of the phone's RemoteActivityHelper launch (livefit://workout/start). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE", Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS),
            1,
        )
        PhoneHub.init(this)
        setContent { WatchApp(WatchGraph.workout, WatchGraph.music, PhoneHub.hrHistory, PhoneHub.lastFrameAt) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        Log.i(PhoneLink.TAG, "activity launched uri=$uri")
        val force = uri.getQueryParameter("force") == "1"
        when (uri.path) {
            "/start" -> if (BuildConfig.USE_FAKE_SERVICES) Unit // the phone already started it; we just show it
                else startForegroundService(ExerciseService.intent(this, ExerciseService.ACTION_START, force))
            "/stop" -> if (BuildConfig.USE_FAKE_SERVICES) Unit
                else startService(ExerciseService.intent(this, ExerciseService.ACTION_STOP))
        }
    }
}
