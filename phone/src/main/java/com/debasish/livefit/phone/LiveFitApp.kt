package com.debasish.livefit.phone

import android.app.Application

class LiveFitApp : Application() {
    lateinit var services: ServiceGraph
        private set

    override fun onCreate() {
        super.onCreate()
        services = ServiceGraph.create(this, BuildConfig.USE_FAKE_SERVICES, BuildConfig.USE_LIVE_GLASSES, BuildConfig.USE_LIVE_WATCH_LINK)
    }
}
