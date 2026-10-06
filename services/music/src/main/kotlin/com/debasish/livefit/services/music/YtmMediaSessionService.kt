package com.debasish.livefit.services.music

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.MediaStore
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.services.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Controls the official YouTube Music app through its media session (spec §2.3, §5.5). */
class YtmMediaSessionService(
    context: Context,
    private val scope: CoroutineScope,
    /** The saved workout-music search (the setting the start-of-workout PlaySearch policy uses). */
    private val savedQuery: () -> String? = { null },
) : MusicService {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, MediaListener::class.java)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying
    private val _volume = MutableStateFlow(currentVolume())
    override val volume: StateFlow<Float> = _volume
    private val _connected = MutableStateFlow(false)
    /** Notification access granted and a YouTube Music session exists. */
    val connected: StateFlow<Boolean> = _connected

    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    init {
        scope.launch { while (true) { attach(); _volume.value = currentVolume(); delay(1_000) } }
    }

    private fun attach() {
        val c = runCatching { sessions.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == YTM }
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        _connected.value = c != null
        if (c == null) { _nowPlaying.value = null; return }
        val md = c.metadata
        val st = c.playbackState
        _nowPlaying.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
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
     * Plays a search — `null` = just open and resume. Uses the media session (no UI) when YouTube Music has one;
     * only without a session does it start the YouTube Music activity, and then it returns LiveFit to the front
     * only if LiveFit was in front when asked (D5).
     */
    fun playSearch(query: String?) {
        val c = controller
        when (SearchRoute.of(hasSession = c != null, appInForeground())) {
            SearchRoute.Session -> { c?.transportControls?.playFromSearch(query ?: "", Bundle()); return }
            SearchRoute.Activity -> startSearchActivity(query)
            SearchRoute.ActivityThenReturn -> if (startSearchActivity(query)) scope.launch { delay(RETURN_DELAY_MS); bringAppToFront() }
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
    }
}
