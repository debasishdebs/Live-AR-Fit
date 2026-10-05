package com.debasish.livefit.phone

import android.os.Handler
import android.os.Looper
import android.util.Log

/** Tiny in-process log so every spike result shows on screen and in logcat (tag LiveFitPhone). */
object SpikeLog {
    const val TAG = "LiveFitPhone"
    private val main = Handler(Looper.getMainLooper())
    var sink: ((String) -> Unit)? = null

    fun i(msg: String) {
        Log.i(TAG, msg)
        main.post { sink?.invoke(msg) }
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        main.post { sink?.invoke("ERROR $msg ${t?.message ?: ""}") }
    }
}
