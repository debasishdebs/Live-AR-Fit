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
}
