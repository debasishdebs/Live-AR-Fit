package com.debasish.livefit.phone

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import android.provider.Settings

/** P3 spike: remote-control the official YouTube Music app through its media session. */
object YtmControl {
    const val YTM = "com.google.android.apps.youtube.music"

    fun openNotificationAccess(context: Context) =
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun controller(context: Context): MediaController? {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val sessions = runCatching { msm.getActiveSessions(ComponentName(context, MediaListener::class.java)) }
            .getOrElse { SpikeLog.e("no notification access", it); return null }
        return sessions.firstOrNull { it.packageName == YTM } ?: run {
            SpikeLog.i("YT Music session not active. Sessions: ${sessions.map { it.packageName }}"); null
        }
    }

    fun dump(context: Context) {
        val c = controller(context) ?: return
        val md = c.metadata
        SpikeLog.i("now: ${md?.getString(MediaMetadata.METADATA_KEY_TITLE)} - ${md?.getString(MediaMetadata.METADATA_KEY_ARTIST)} id=${md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)}")
        val st = c.playbackState
        SpikeLog.i("state=${st?.state} pos=${st?.position} speed=${st?.playbackSpeed} actions=0x${st?.actions?.toString(16)}")
        SpikeLog.i("supports speed: ${(st?.actions ?: 0) and PlaybackState.ACTION_SET_PLAYBACK_SPEED != 0L}")
        st?.customActions?.forEach { SpikeLog.i("customAction action='${it.action}' name='${it.name}' extras=${it.extras?.keySet()}") }
        SpikeLog.i("volume ${c.playbackInfo.currentVolume}/${c.playbackInfo.maxVolume}")
    }

    fun playPause(context: Context) {
        val c = controller(context) ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next(context: Context) = controller(context)?.transportControls?.skipToNext()
    fun previous(context: Context) = controller(context)?.transportControls?.skipToPrevious()

    fun like(context: Context) {
        val c = controller(context) ?: return
        val action = c.playbackState?.customActions?.firstOrNull {
            val s = "${it.action} ${it.name}".lowercase()
            "like" in s || "thumb" in s
        }
        if (action == null) return SpikeLog.i("no like/thumb custom action exposed")
        c.transportControls.sendCustomAction(action, null)
        SpikeLog.i("sent custom action '${action.action}' (${action.name})")
    }

    fun playFromSearch(context: Context, query: String) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(YTM)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { SpikeLog.i("play-from-search '$query' sent") }
            .onFailure { SpikeLog.e("play-from-search failed", it) }
    }
}
