package com.debasish.livefit.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.GpsPreferences
import com.debasish.livefit.sync.WatchExerciseController
import com.debasish.livefit.sync.WatchSessionRecorder
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

object WatchRuntime {
    const val TAG = "LiveFitWatch"
    /** Main.immediate: the recorder and controller assume this single-threaded dispatcher, so everything must use it. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in WatchRuntime.scope", e) },
    )
    private lateinit var app: Context
    lateinit var recorder: WatchSessionRecorder; private set
    lateinit var controller: WatchExerciseController; private set
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        recorder = WatchSessionRecorder(
            File(app.filesDir, "lf-buffer"), Provenance.Live("galaxy-watch/health-services"), scope,
            send = { d -> send(WatchPaths.DELTA, Wire.encode(d).toByteArray()) },
            sendClaim = { c -> send(WatchPaths.CLAIM, Wire.encode(c).toByteArray()) },
        )
        controller = WatchExerciseController(
            scope, HealthServicesExercise(app), recorder, Clock { System.currentTimeMillis() },
            sendResult = { r ->
                ensureExerciseService() // before sending: the result may fail if the phone just went away
                send(WatchPaths.EXERCISE_RES, Wire.encode(r).toByteArray())
            },
            sendState = { s -> send(WatchPaths.EXERCISE_STATE, Wire.encode(s).toByteArray()) },
            gpsPrefs = GpsPreferences(File(app.filesDir, "gps.json")),
        )
        initialized = true
        // Every entry point (phone message, sticky service restart, activity) gets the same recovery and sync loop.
        ensureExerciseService() // before recover(), which may retry for a long time
        scope.launch { controller.recover(); ensureExerciseService() }
        WatchClient.start()
    }

    /** Health foreground service while a session is recording or still has unacked data. */
    fun ensureExerciseService() { if (recorder.holdsData) ExerciseService.start(app) }

    /**
     * Sends to the phone; throws if unreachable — or if Play Services doesn't answer within [SEND_TIMEOUT_MS] — so the
     * recorder keeps the delta buffered and a hung Task can't stall the send queue or the update collector.
     */
    suspend fun send(path: String, bytes: ByteArray) {
        withTimeoutOrNull(SEND_TIMEOUT_MS) {
            val nodes = Wearable.getNodeClient(app).connectedNodes.await()
            check(nodes.isNotEmpty()) { "phone unreachable" }
            nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, path, bytes).await() }
        } ?: throw java.io.IOException("send to phone timed out")
    }

    private const val SEND_TIMEOUT_MS = 10_000L

    fun log(msg: String) = Log.i(TAG, msg)
}
