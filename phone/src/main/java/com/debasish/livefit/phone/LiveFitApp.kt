package com.debasish.livefit.phone

import android.app.Application

class LiveFitApp : Application() {
    val services: ServiceGraph by lazy {
        ServiceGraph(this, Bindings(
            liveWatch = BuildConfig.LIVE_WATCH,
            liveGlasses = BuildConfig.LIVE_GLASSES,
            liveMusic = BuildConfig.LIVE_MUSIC,
            liveVoice = BuildConfig.LIVE_VOICE,
        )).also { it.start() }
    }

    /**
     * F1: the process may come back for the MediaListener, a watch message or companion presence (e.g. right after an
     * APK update) without anyone starting the hub. Start it here; ensureRunning checks the connectedDevice prerequisite and
     * swallows a refused background start (companion apps holding REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND are exempt).
     */
    override fun onCreate() {
        super.onCreate()
        try { LiveFitHubService.ensureRunning(this) } catch (e: Exception) { android.util.Log.w("LiveFitHub", "hub start at process start failed", e) }
    }
}
