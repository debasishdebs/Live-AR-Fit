package com.debasish.livefit.watch

/** Recorded on every new watch session. Old sessions carry `galaxy-watch/health-services`, which stays valid everywhere it is read. */
object WatchProvenance { const val SOURCE = "wear-os/health-services" }
