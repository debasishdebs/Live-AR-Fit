package com.debasish.livefit.services.music

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.service.media.MediaBrowserService
import android.util.Log
import android.view.KeyEvent
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow
import com.debasish.livefit.services.MusicService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** Controls the official YouTube Music app through its media session (spec §2.3, §5.5). */
class YtmMediaSessionService(
    context: Context,
    private val scope: CoroutineScope,
    /** The saved workout-music search (the setting the start-of-workout PlaySearch policy uses). */
    private val savedQuery: () -> String? = { null },
    /** Settings → YouTube Music → glasses queue size (N, see [QueueWindowing]); re-read on every publish (≤ 1 s). */
    private val queueSize: () -> Int = { QueueWindowing.DEFAULT_SIZE },
) : MusicService {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, MediaListener::class.java)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying
    private val _volume = MutableStateFlow(currentVolume())
    override val volume: StateFlow<Float> = _volume
    private val _queue = MutableStateFlow(QueueWindow())
    override val queue: StateFlow<QueueWindow> = _queue
    private val _connected = MutableStateFlow(false)
    /** Notification access granted and a YouTube Music session exists. */
    val connected: StateFlow<Boolean> = _connected

    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    /** B3: attach the moment YouTube Music's session appears, not on the next 1 s poll. Needs notification access. */
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { attach() }
    private var sessionsListening = false
    /** B2: an in-flight headless start, and the media browser connection it may hold. */
    private var headless: Job? = null
    private var browser: MediaBrowser? = null

    init {
        scope.launch { while (true) { listenForSessions(); attach(); _volume.value = currentVolume(); delay(1_000) } }
    }

    /** Retried every poll: it throws until notification access is granted. */
    private fun listenForSessions() {
        if (sessionsListening) return
        sessionsListening = runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, Handler(Looper.getMainLooper())) }.isSuccess
    }

    private fun attach() {
        val c = runCatching { sessions.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == YTM }
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
            Log.i(TAG, if (c != null) "YouTube Music session attached" else "YouTube Music session gone")
        }
        publish()
    }

    private fun publish() {
        val c = controller
        _connected.value = c != null
        if (c == null) { _nowPlaying.value = null; _queue.value = QueueWindow(); return }
        val md = c.metadata
        val st = c.playbackState
        // YouTube Music reports "Up next" (played + upcoming); activeQueueItemId marks the current entry. StateFlow drops equal windows.
        val items = runCatching { c.queue }.getOrNull().orEmpty().map {
            QueueItem(it.queueId, it.description.title?.toString() ?: "", it.description.subtitle?.toString() ?: "")
        }
        val active = st?.activeQueueItemId?.takeIf { it != MediaSession.QueueItem.UNKNOWN_ID.toLong() }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        // No queue reported but a song is loaded: the glasses list still shows it (B3).
        _queue.value = QueueWindowing.window(items, active, queueSize(), nowPlaying = QueueItem(QueueWindowing.NOW_PLAYING_ID, title, artist))
        _nowPlaying.value = NowPlaying(
            title = title,
            artist = artist,
            isPlaying = st?.state == PlaybackState.STATE_PLAYING,
            liked = st?.customActions?.any { it.action == LIKE && it.name?.toString()?.contains("Unlike", true) == true } ?: false,
            positionMs = st?.position ?: 0,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            volume = currentVolume(),
        )
    }

    override fun playPause() { if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) pause() else play() }
    override fun play() { controller?.transportControls?.play() ?: playSearch(resumeQuery(savedQuery())) }
    override fun pause() { controller?.transportControls?.pause() }
    override fun next() { controller?.transportControls?.skipToNext() }
    override fun previous() { controller?.transportControls?.skipToPrevious() }
    override fun playQueueItem(queueId: Long) {
        if (queueId == QueueWindowing.NOW_PLAYING_ID) return // the now-playing fallback row: already current
        controller?.transportControls?.skipToQueueItem(queueId)
    }

    override fun toggleLike() {
        val c = controller ?: return
        val like = c.playbackState?.customActions?.firstOrNull { it.action == LIKE || "${it.action} ${it.name}".contains("like", true) } ?: return
        c.transportControls.sendCustomAction(like, null)
    }

    override fun setVolume(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).roundToInt(), 0)
        _volume.value = currentVolume()
        publish()
    }

    /**
     * Plays a search — `null` = just open and resume. Uses the media session (no UI) when YouTube Music has one.
     * Without a session: the YouTube Music activity only while LiveFit is in front (then LiveFit returns to the front,
     * D5); in the background Android blocks that start, so it starts headless instead (B2).
     */
    fun playSearch(query: String?) {
        val c = controller
        val route = SearchRoute.of(hasSession = c != null, appInForeground())
        Log.i(TAG, "play search=${query != null} route=$route")
        when (route) {
            SearchRoute.Session -> c?.transportControls?.playFromSearch(query ?: "", Bundle())
            SearchRoute.ActivityThenReturn -> if (startSearchActivity(query)) scope.launch { delay(RETURN_DELAY_MS); bringAppToFront() }
            SearchRoute.Headless -> startHeadless(query?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * B2: no session and no UI allowed. Bind YouTube Music's media browser service (which brings up its session); if
     * that is refused, send it a media-button PLAY (media resumption). Once a session answers, apply [query] on it.
     */
    private fun startHeadless(query: String?) {
        headless?.cancel()
        headless = scope.launch {
            var c = connectBrowser()
            if (c != null) Log.i(TAG, "headless: connected to YouTube Music's media browser")
            else {
                Log.i(TAG, "headless: no media browser connection; sending media-button PLAY to YouTube Music")
                sendMediaButtonPlay()
                c = awaitSession()
            }
            if (c == null) { Log.w(TAG, "headless: YouTube Music session did not appear within $SESSION_WAIT_MS ms"); disconnectBrowser(); return@launch }
            val step = HeadlessStep.onSession(query, c.playbackState?.state == PlaybackState.STATE_PLAYING)
            Log.i(TAG, "headless: session up, $step")
            when (step) {
                is HeadlessStep.PlayFromSearch -> c.transportControls.playFromSearch(step.query, Bundle())
                HeadlessStep.Play -> c.transportControls.play()
                HeadlessStep.Done -> Unit
            }
            delay(PLAY_CHECK_MS)
            val state = c.playbackState?.state
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_BUFFERING && state != PlaybackState.STATE_CONNECTING) {
                Log.w(TAG, "headless: not playing after $PLAY_CHECK_MS ms (state=$state); sending media-button PLAY")
                sendMediaButtonPlay()
            }
            delay(BROWSER_HOLD_MS) // long enough for YouTube Music's own playback service to take over
            disconnectBrowser()
        }
    }

    /** Polls (on top of the sessions listener) until YouTube Music's session is active, for up to [SESSION_WAIT_MS]. */
    private suspend fun awaitSession(): MediaController? {
        repeat((SESSION_WAIT_MS / SESSION_POLL_MS).toInt()) {
            attach()
            controller?.let { return it }
            delay(SESSION_POLL_MS)
        }
        return null
    }

    /** Controller for YouTube Music's session via its exported media browser service, or null if absent or refused. */
    private suspend fun connectBrowser(): MediaController? {
        disconnectBrowser()
        val component = runCatching {
            app.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(YTM), 0)
                .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull() ?: run { Log.i(TAG, "headless: YouTube Music exports no media browser service"); return null }
        val result = CompletableDeferred<MediaController?>()
        lateinit var b: MediaBrowser
        b = MediaBrowser(app, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() { result.complete(runCatching { MediaController(app, b.sessionToken) }.getOrNull()) }
            override fun onConnectionFailed() { Log.i(TAG, "headless: media browser $component refused the connection"); result.complete(null) }
        }, null)
        browser = b
        runCatching { b.connect() }.onFailure { result.complete(null) }
        return withTimeoutOrNull(BROWSER_WAIT_MS) { result.await() }.also { if (it == null) disconnectBrowser() }
    }

    private fun disconnectBrowser() {
        browser?.let { runCatching { it.disconnect() } }
        browser = null
    }

    /** Media-button PLAY (down + up) addressed to YouTube Music's receiver: wakes its service and resumes playback. */
    private fun sendMediaButtonPlay() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(YTM).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            runCatching { app.sendBroadcast(intent) }.onFailure { Log.w(TAG, "headless: media-button broadcast failed", it) }
        }
    }

    private fun startSearchActivity(query: String?): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(YTM)
            .putExtra(SearchManager.QUERY, query ?: "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent) }.isSuccess
    }

    /** A visible LiveFit activity (not merely the hub's foreground service). */
    private fun appInForeground(): Boolean = runCatching {
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance ==
            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private fun bringAppToFront() {
        val moved = runCatching { app.getSystemService(ActivityManager::class.java).appTasks.firstOrNull()?.moveToFront() != null }.getOrDefault(false)
        if (!moved) app.packageManager.getLaunchIntentForPackage(app.packageName)?.let { runCatching { app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    companion object {
        const val YTM = "com.google.android.apps.youtube.music"
        const val LIKE = "thumbs_up_action"
        /** Time for YouTube Music's activity to take the search before LiveFit returns to the front. */
        const val RETURN_DELAY_MS = 1_500L
        private const val TAG = "LiveFitMusic"
        /** Headless start (B2): wait for the browser connection, then for a session after the media button. */
        private const val BROWSER_WAIT_MS = 3_000L
        private const val SESSION_WAIT_MS = 10_000L
        private const val SESSION_POLL_MS = 250L
        private const val PLAY_CHECK_MS = 3_000L
        private const val BROWSER_HOLD_MS = 30_000L
    }
}
