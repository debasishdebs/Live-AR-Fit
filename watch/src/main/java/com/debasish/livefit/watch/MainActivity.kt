package com.debasish.livefit.watch

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.Text

/** Placeholder entry point; Task 18 rewrites it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE", Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS),
            1,
        )
        WatchRuntime.init(this)
        setContent { Text("LiveFit") }
    }
}
