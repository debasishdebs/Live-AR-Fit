package com.debasish.livefit.watch

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.debasish.livefit.model.DiscoverableRequest

/**
 * Pairing (D2): shows the system "make discoverable" prompt so the phone's companion picker can list this watch.
 * Started by the phone's Data Layer message and by a remote activity launch carrying the same request; one prompt is shown.
 */
class DiscoverableActivity : ComponentActivity() {
    private var seconds = DiscoverableRequest().seconds
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) prompt() else finish() }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // recreated while a prompt is open: its result still arrives
        seconds = requestedSeconds(intent) ?: return finish()
        if (!claim(System.currentTimeMillis())) return finish()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else prompt()
    }

    private fun prompt() {
        try {
            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds))
        } catch (e: Exception) {
            Log.w(WatchRuntime.TAG, "discoverable prompt failed", e)
            finish()
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "req"
        private const val DEDUPE_MS = 10_000L
        @Volatile private var lastClaimMs = 0L

        /** Both deliveries of one Pair tap arrive within a few seconds: only the first shows the prompt. */
        @Synchronized private fun claim(now: Long): Boolean {
            if (now - lastClaimMs < DEDUPE_MS) return false
            lastClaimMs = now
            return true
        }

        private fun requestedSeconds(intent: Intent): Int? =
            (intent.getStringExtra(EXTRA_REQUEST) ?: intent.data?.getQueryParameter(EXTRA_REQUEST))?.let(DiscoverableRequest::parse)

        /** From the Data Layer listener; [json] is the phone's (already version-checked) request. */
        fun start(context: Context, json: String) {
            runCatching {
                context.startActivity(Intent(context, DiscoverableActivity::class.java).putExtra(EXTRA_REQUEST, json).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(WatchRuntime.TAG, "could not start discoverable prompt", it) }
        }
    }
}
