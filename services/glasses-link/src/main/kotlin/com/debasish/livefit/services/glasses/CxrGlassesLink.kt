package com.debasish.livefit.services.glasses

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.GlassesEvent
import com.debasish.livefit.services.GlassesLinkService
import com.rokid.cxr.Caps
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.ICustomCmdSessionCallback
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionTimeouts
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Live glasses link over Rokid CXR-L via the Hi Rokid app (spec §2.1, §5.3).
 * Lifecycle decisions come from [GlassesSessionPolicy]; this class only performs actions.
 */
class CxrGlassesLink(context: Context, private val scope: CoroutineScope) : GlassesLinkService {
    private val app = context.applicationContext
    private val manager = CxrSessionManager.getInstance(app)
    private val policy = GlassesSessionPolicy()
    private var session: CxrSession? = null
    private var authorizedThisProcess = false
    @Volatile private var lastSettings: HudSettingsFrame? = null

    private val _status = MutableStateFlow(DeviceStatus("Rokid Glasses", LinkState.Disconnected))
    override val status: StateFlow<DeviceStatus> = _status
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 256) // audio arrives as ~10 chunks/s
    override val events: SharedFlow<GlassesEvent> = _events

    init { instance = this }

    /** Silent when already authorized in Hi Rokid; sets CXR-L's in-memory flags (quirk 2.1.1). */
    fun authorize(activity: Activity, onDone: (Boolean) -> Unit) {
        try {
            manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
                Log.i(TAG, "authorize ok=${r.isSuccess} code=${r.errorCode}")
                r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences(PREFS, 0).edit().putString(KEY_TOKEN, it).apply() }
                authorizedThisProcess = r.isSuccess
                onDone(r.isSuccess)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed", e)
            onDone(false)
        }
    }

    fun onDevicePresence(present: Boolean) = act(policy.onEvent(if (present) LinkEvent.DevicePresent else LinkEvent.DeviceGone))

    override fun connect() = act(policy.manualConnect())

    private fun act(actions: List<LinkAction>) {
        for (a in actions) when (a) {
            LinkAction.Connect -> openSession()
            LinkAction.SendSettings -> lastSettings?.let { scope.launch { pushSettings(it) } }
            is LinkAction.ScheduleRetry -> scope.launch { delay(a.delayMs); act(policy.onEvent(LinkEvent.RetryTimer)) }
            LinkAction.MarkConnected -> _status.update { it.copy(link = LinkState.Connected, detail = null) }
            LinkAction.MarkConnecting -> _status.update { it.copy(link = LinkState.Connecting) }
            LinkAction.MarkDisconnected -> _status.update { it.copy(link = LinkState.Disconnected) }
        }
    }

    private fun openSession() {
        val token = app.getSharedPreferences(PREFS, 0).getString(KEY_TOKEN, null)
        if (token == null || !authorizedThisProcess) {
            // Background authorization: companion apps may start activities (spec §5.3).
            // CXR-L only works after an in-process requestAuthorization, so connect waits for AuthActivity.
            AuthActivity.launch(app)
            _status.update { it.copy(detail = "Authorizing with Hi Rokid") }
            act(policy.onEvent(LinkEvent.ConnectFailed))
            return
        }
        try {
            preferGlobalHiRokid()
            // Detach the old session first so its late close callback cannot clobber the new one.
            val old = session
            session = null
            old?.close()
            val s = manager.create(
                SessionConfig(
                    sessionType = SessionType.CUSTOM_APP,
                    glassesPackageName = GLASSES_PKG,
                    aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE,
                    terminatingGracePeriodMs = 5_000L,
                    timeouts = SessionTimeouts(),
                    viewData = "",
                    viewIconData = "",
                    glassesActivityName = "$GLASSES_PKG.MainActivity",
                    glassesApkPath = "",
                ),
            )
            s.addLifecycleCallback(lifecycleFor(s))
            s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
                override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) = onGlassesMessage(cmd, bytes)
            })
            session = s
            s.connect(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "openSession failed", e)
            session = null
            act(policy.onEvent(LinkEvent.ConnectFailed))
        }
    }

    private fun onGlassesMessage(cmd: String, bytes: ByteArray?) {
        val text = try {
            bytes?.let { Caps.fromBytes(it).at(0).string }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bad payload on $cmd", e); null
        } ?: ""
        when (cmd) {
            GlassesChannels.LISTEN -> _events.tryEmit(GlassesEvent.Listen)
            // Audio is Base64 text on lf_audio (spike-verified; deliberate deviation from a raw-bytes argument).
            GlassesChannels.AUDIO -> runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()?.let { _events.tryEmit(GlassesEvent.Audio(it)) }
            GlassesChannels.LISTEN_END -> _events.tryEmit(GlassesEvent.ListenEnd)
            GlassesChannels.COMMAND -> {
                val v = Wire.versionOf(text)
                if (v != PROTOCOL_VERSION) _events.tryEmit(GlassesEvent.Outdated(v))
                else runCatching { Wire.decode<CommandEnvelope>(text) }.getOrNull()?.let { _events.tryEmit(GlassesEvent.Issue(it)) }
            }
        }
    }

    override suspend fun push(frame: StateFrame) = send(GlassesChannels.STATE, Wire.encode(frame))

    override suspend fun pushSettings(frame: HudSettingsFrame) {
        lastSettings = frame
        send(GlassesChannels.SETTINGS, Wire.encode(frame))
    }

    private fun send(channel: String, json: String) {
        if (_status.value.link != LinkState.Connected) return
        val s = session ?: return
        try {
            val r = s.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))
            if (!r.isSuccess) Log.w(TAG, "send $channel failed ${r.code}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send $channel threw", e)
        }
    }

    private fun lifecycleFor(s: CxrSession) = object : ISessionLifecycleCbk {
        private fun current() = s === session
        override fun onSessionStarted() { if (current()) act(policy.onEvent(LinkEvent.Started)) }
        override fun onSessionPaused(reason: PausedReason) { if (current()) act(policy.onEvent(LinkEvent.Paused)) }
        override fun onSessionResumed() { if (current()) act(policy.onEvent(LinkEvent.Resumed)) }
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = Unit
        override fun onSessionClosed(reason: CloseReason) {
            if (!current()) return
            session = null
            act(policy.onEvent(LinkEvent.Closed))
        }
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) {
            if (!ok && current()) { session = null; Log.w(TAG, "connect failed $code"); act(policy.onEvent(LinkEvent.ConnectFailed)) }
        }
    }

    /** Quirk 2.1.1: after a restart CXR-L would bind the China package. */
    private fun preferGlobalHiRokid() {
        val global = runCatching { app.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!global) return
        try {
            Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
                .getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        } catch (e: Exception) {
            Log.w(TAG, "could not force global Hi Rokid", e)
        }
    }

    internal fun markAuthorized() { authorizedThisProcess = true; act(policy.manualConnect()) }

    companion object {
        const val TAG = "LiveFitGlassesLink"
        const val GLASSES_PKG = "com.debasish.livefit.glasses"
        internal const val PREFS = "rokid"
        internal const val KEY_TOKEN = "token"
        @Volatile var instance: CxrGlassesLink? = null
    }
}
