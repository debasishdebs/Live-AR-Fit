package com.debasish.livefit.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rokid.cxr.session.AiInterceptMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Developer tools: one button per spike experiment, results in the log below. */
class SpikeActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var log: TextView
    private var pingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun section(title: String) = buttons.addView(TextView(this).apply { text = title; textSize = 15f; setPadding(0, 24, 0, 4) })
        fun button(label: String, onClick: () -> Unit) = buttons.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })

        section("Rokid (CXR-L via Hi Rokid)")
        button("Status") { RokidLink.status(this) }
        button("Authorize") { RokidLink.authorize(this) }
        button("Connect (BLOCK_AI)") { RokidLink.connect(this, AiInterceptMode.BLOCK_AI) }
        button("Connect (ALLOW_WITH_PAUSE)") { RokidLink.connect(this, AiInterceptMode.ALLOW_WITH_PAUSE) }
        button("Ping glasses 1 Hz (toggle)") { togglePing() }
        button("Mic start") { RokidLink.startMic(this) }
        button("Mic stop") { RokidLink.stopMic() }
        button("Close session") { RokidLink.close() }

        section("Watch")
        button("Start via remote activity") { scope.launch { WatchControl.launchActivity(this@SpikeActivity, "start", force = false) } }
        button("Start via remote activity (force)") { scope.launch { WatchControl.launchActivity(this@SpikeActivity, "start", force = true) } }
        button("Start via message") { scope.launch { WatchControl.message(this@SpikeActivity, WatchControl.PATH_START, force = false) } }
        button("Stop via message") { scope.launch { WatchControl.message(this@SpikeActivity, WatchControl.PATH_STOP, force = false) } }

        section("YouTube Music (P3)")
        button("Notification access") { YtmControl.openNotificationAccess(this) }
        button("Dump session") { YtmControl.dump(this) }
        button("Play/Pause") { YtmControl.playPause(this) }
        button("Next") { YtmControl.next(this) }
        button("Like") { YtmControl.like(this) }
        button("Play 'workout mix'") { YtmControl.playFromSearch(this, "workout mix") }

        buttons.addView(log)
        setContentView(ScrollView(this).apply { addView(buttons); setPadding(24, 48, 24, 24) })
        SpikeLog.sink = { line -> log.append(line + "\n") }
        RokidLink.status(this)
        // CXR-L keeps granted glasses permissions only in memory, so re-authorize every process start
        // (Hi Rokid returns immediately when already authorized).
        if (savedInstanceState == null) RokidLink.authorize(this)
    }

    private fun togglePing() {
        pingJob?.let { it.cancel(); pingJob = null; SpikeLog.i("ping stopped"); return }
        pingJob = scope.launch {
            var n = 0
            while (isActive) {
                n++
                val ok = RokidLink.send("rf_ping", """{"seq":$n,"t":${System.currentTimeMillis()}}""")
                if (n % 5 == 1) SpikeLog.i("ping #$n sent=$ok")
                delay(1000)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        RokidLink.onActivityResult(this, resultCode, data)
    }

    override fun onDestroy() {
        SpikeLog.sink = null
        scope.cancel()
        super.onDestroy()
    }
}
