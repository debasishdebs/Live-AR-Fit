package com.debasish.livefit.phone.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.phone.HubLocationPolicy

/** Phone fallback fixes at 1 Hz (spec §2.1); started/stopped by the hub's fallback decision. Main thread only. */
class PhoneLocationProvider(context: Context, private val onFix: (LocationFix) -> Unit) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    val running: Boolean get() = listener != null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listener != null) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val provider = HubLocationPolicy.providerFor(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER))
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                onFix(
                    LocationFix(
                        loc.latitude, loc.longitude,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null, // unknown: never usable-live (review #8)
                        bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                        fixTimeMs = HubLocationPolicy.fixTimeMs(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), loc.elapsedRealtimeNanos),
                    ),
                )
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            lm.requestLocationUpdates(provider, 1_000L, 0f, l, Looper.getMainLooper())
            listener = l
            Log.i(TAG, "phone GPS on ($provider)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "phone GPS refused", e) // e.g. the hub has no location FGS type: fallback unavailable, nothing else breaks
            false
        }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { lm.removeUpdates(l) }
        listener = null
        Log.i(TAG, "phone GPS off")
    }

    companion object {
        const val TAG = "LiveFitPhoneGps"
    }
}
