package com.debasish.livefit.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.NodeCandidate
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.GpsPreferences
import com.debasish.livefit.sync.WatchExerciseController
import com.debasish.livefit.sync.WatchRouteFile
import com.debasish.livefit.sync.WatchSessionRecorder
import com.debasish.livefit.watch.map.WatchTiles
import com.google.android.gms.wearable.CapabilityClient
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
    lateinit var app: Context; private set
    lateinit var recorder: WatchSessionRecorder; private set
    lateinit var controller: WatchExerciseController; private set
    lateinit var routes: WatchRouteFile; private set

    /** Map tiles for the watch Map page (created on first use). */
    val tiles: WatchTiles by lazy { WatchTiles(app, scope) }
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        routes = WatchRouteFile(File(app.filesDir, "routes")).also { it.sweep() }
        recorder = WatchSessionRecorder(
            File(app.filesDir, "lf-buffer"), Provenance.Live(WatchProvenance.SOURCE), scope,
            send = { d -> send(WatchPaths.DELTA, Wire.encode(d).toByteArray()) },
            sendClaim = { c -> send(WatchPaths.CLAIM, Wire.encode(c).toByteArray()) },
            onFinalAcked = { id -> routes.markAcked(id) },
        )
        controller = WatchExerciseController(
            scope, HealthServicesExercise(app), recorder, Clock { System.currentTimeMillis() },
            sendResult = { r ->
                ensureExerciseService() // before sending: the result may fail if the phone just went away
                send(WatchPaths.EXERCISE_RES, Wire.encode(r).toByteArray())
            },
            sendState = { s -> send(WatchPaths.EXERCISE_STATE, Wire.encode(s).toByteArray()) },
            gpsPrefs = GpsPreferences(File(app.filesDir, "gps.json")),
            routes = routes,
        )
        recorder.sessionId?.let(routes::open) // route + start marker survive process death (spec §2.6)
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
            val ids = phoneNodeIds()
            check(ids.isNotEmpty()) { "phone unreachable" }
            ids.forEach { Wearable.getMessageClient(app).sendMessage(it, path, bytes).await() }
        } ?: throw java.io.IOException("send to phone timed out")
    }

    /** `livefit_phone` nodes, nearby first; falls back to every connected node (phone build without the capability). */
    private suspend fun phoneNodeIds(): List<String> {
        val cap = runCatching {
            Wearable.getCapabilityClient(app).getCapability(PhoneNodes.CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
                .map { NodeCandidate(it.id, it.displayName, it.isNearby) }
        }.onFailure { if (it is kotlin.coroutines.cancellation.CancellationException) throw it }.getOrDefault(emptyList())
        if (cap.isNotEmpty()) return PhoneNodes.targets(cap, emptyList())
        return PhoneNodes.targets(emptyList(), Wearable.getNodeClient(app).connectedNodes.await().map { it.id })
    }

    private const val SEND_TIMEOUT_MS = 10_000L

    fun log(msg: String) = Log.i(TAG, msg)
}
