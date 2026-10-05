package com.debasish.livefit.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.rokid.cxr.Caps
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.GlassesInfo
import com.rokid.cxr.session.IAudioCallback
import com.rokid.cxr.session.ICustomCmdSessionCallback
import com.rokid.cxr.session.IGlassesEventListener
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionTimeouts
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import java.io.File
import java.io.FileOutputStream

/**
 * Spike wrapper around CXR-L (phone side, talks to glasses through the Hi Rokid app).
 * Proves: auth without a client secret, launching our glasses app, 1 Hz custom cmds,
 * and whether "Hi Rokid" wake events + mic audio reach us.
 */
object RokidLink {
    const val GLASSES_PKG = "com.debasish.livefit.glasses"
    const val GLASSES_ACTIVITY = "com.debasish.livefit.glasses.MainActivity"
    private const val PREFS = "rokid"

    private var session: CxrSession? = null
    private var audioOut: FileOutputStream? = null
    private var audioBytes = 0L
    var autoListenOnWake = true

    fun manager(context: Context) = CxrSessionManager.getInstance(context.applicationContext)

    fun status(context: Context) {
        val m = manager(context)
        SpikeLog.i("Hi Rokid installed=${m.isRokidAppInstalled(context)} compat=${m.checkRokidAppCompatibility(context)} glassesBt=${m.isGlassesBtConnected()}")
    }

    fun authorize(activity: Activity) {
        val perms = listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)
        manager(activity).requestAuthorization(activity, perms) { result ->
            SpikeLog.i("auth callback ok=${result.isSuccess} code=${result.errorCode} msg=${result.message} tokenLen=${result.token?.length}")
            result.token?.takeIf { result.isSuccess }?.let { saveToken(activity, it) }
        }
    }

    /** Some SDK builds deliver the auth result through onActivityResult instead of the callback. */
    fun onActivityResult(context: Context, resultCode: Int, data: Intent?) {
        val result = runCatching { manager(context).parseAuthorizationResult(resultCode, data) }.getOrNull() ?: return
        SpikeLog.i("auth activityResult ok=${result.isSuccess} code=${result.errorCode} tokenLen=${result.token?.length}")
        result.token?.takeIf { result.isSuccess }?.let { saveToken(context, it) }
    }

    fun connect(context: Context, mode: AiInterceptMode) {
        val token = context.getSharedPreferences(PREFS, 0).getString("token", null)
        if (token == null) {
            SpikeLog.i("no token yet - tap Authorize first")
            return
        }
        session?.close()
        preferGlobalHiRokid(context)
        val config = SessionConfig(
            sessionType = SessionType.CUSTOM_APP,
            glassesPackageName = GLASSES_PKG,
            aiInterceptMode = mode,
            terminatingGracePeriodMs = 5_000L,
            timeouts = SessionTimeouts(),
            viewData = "",
            viewIconData = "",
            glassesActivityName = GLASSES_ACTIVITY,
            glassesApkPath = "",
        )
        val s = manager(context).create(config)
        s.addLifecycleCallback(lifecycle)
        s.addGlassesEventListener(glassesEvents(context))
        s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
            override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) {
                SpikeLog.i("glasses -> phone cmd=$cmd payload=${bytes?.let { decode(it) }}")
                // Push-to-talk from the glasses touchpad: capture a short utterance.
                if (cmd == "rf_listen" && audioOut == null) {
                    startMic(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ stopMic() }, 5_000)
                }
            }
        })
        s.addAudioCallback(audio(context))
        session = s
        SpikeLog.i("connect(mode=$mode)")
        s.connect(token)
    }

    fun send(channel: String, json: String): Boolean {
        val s = session ?: return false
        val r = s.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))
        if (!r.isSuccess) SpikeLog.i("sendCustomCmd($channel) -> ${r.code} ${r.message}")
        return r.isSuccess
    }

    fun startMic(context: Context) {
        val s = session ?: return SpikeLog.i("no session")
        val file = File(context.getExternalFilesDir(null), "mic-${System.currentTimeMillis()}.raw")
        audioOut = FileOutputStream(file)
        audioBytes = 0
        val r = s.startAudioStream()
        SpikeLog.i("startAudioStream -> ${r.code} ${r.message}; writing ${file.name}")
    }

    fun stopMic() {
        val r = session?.stopAudioStream()
        audioOut?.close()
        audioOut = null
        SpikeLog.i("stopAudioStream -> ${r?.code}; captured $audioBytes bytes")
    }

    fun close() {
        session?.close()
        session = null
    }

    /**
     * CXR-L 1.1.2 only remembers "use the global Hi Rokid app" in a static flag set by
     * requestAuthorization(). After a process restart, connect() would bind the China package
     * (com.rokid.sprite.aiapp) and fail. Set the flag ourselves when only the global app exists.
     */
    private fun preferGlobalHiRokid(context: Context) {
        val globalInstalled = runCatching { context.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!globalInstalled) return
        runCatching {
            val helper = Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
            helper.getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        }.onFailure { SpikeLog.e("could not force global Hi Rokid", it) }
    }

    private fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("token", token).apply()
        SpikeLog.i("token saved")
    }

    private fun decode(bytes: ByteArray): String =
        runCatching { Caps.fromBytes(bytes).at(0).string }.getOrElse { String(bytes) }

    private val lifecycle = object : ISessionLifecycleCbk {
        override fun onSessionStarted() = SpikeLog.i("session STARTED")
        override fun onSessionPaused(reason: PausedReason) = SpikeLog.i("session PAUSED $reason")
        override fun onSessionResumed() = SpikeLog.i("session RESUMED")
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = SpikeLog.i("session TERMINATING $reason ${graceMs}ms")
        override fun onSessionClosed(reason: CloseReason) = SpikeLog.i("session CLOSED $reason")
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) = SpikeLog.i("connectResult ok=$ok code=$code")
    }

    private fun glassesEvents(context: Context) = object : IGlassesEventListener {
        override fun onGlassesAppResumed() = SpikeLog.i("glasses app resumed")
        override fun onGlassesAppPaused() = SpikeLog.i("glasses app paused")
        override fun onWearingStatusChanged(worn: Boolean) = SpikeLog.i("wearing=$worn")
        override fun onDeviceInfoChanged(info: GlassesInfo) {}
        override fun onScreenOff() = SpikeLog.i("glasses screen off")
        override fun onScreenOn() = SpikeLog.i("glasses screen on")
        override fun onLauncherResumed() = SpikeLog.i("glasses launcher resumed")
        override fun onAiWake() {
            SpikeLog.i("*** onAiWake (Hi Rokid) ***")
            if (autoListenOnWake && audioOut == null) startMic(context)
        }
        override fun onAiInterruptChanged(interrupted: Boolean) = SpikeLog.i("aiInterrupt=$interrupted")
    }

    private fun audio(context: Context) = object : IAudioCallback {
        override fun onAudioReceived(bytes: ByteArray) {
            audioOut?.write(bytes)
            audioBytes += bytes.size
            if (audioBytes < 5_000 || audioBytes % 64_000 < bytes.size) SpikeLog.i("audio +${bytes.size} total=$audioBytes")
        }
        override fun onAudioError(code: Int, msg: String?) = SpikeLog.i("audio error $code $msg")
        override fun onAudioStreamStateChanged(on: Boolean) = SpikeLog.i("audio stream on=$on")
    }
}
