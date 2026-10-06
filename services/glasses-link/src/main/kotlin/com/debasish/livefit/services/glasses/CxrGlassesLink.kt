package com.debasish.livefit.services.glasses

import android.app.Activity
import android.content.Context
import android.util.Log
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.LinkState
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
import kotlinx.coroutines.Job
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

    // Everything below is touched only on [scope] (Main): CXR callbacks are posted there first.
    private var session: CxrSession? = null
    private val auth = AuthGate()
    private var authJob: Job? = null
    private var authDeclined = false
    private var retryJob: Job? = null
    private var connectJob: Job? = null
    private var lastSettings: HudSettingsFrame? = null
    private val inbound = GlassesInbound() // thread-safe: fed from CXR callback threads

    private val _status = MutableStateFlow(DeviceStatus("Rokid Glasses", LinkState.Disconnected))
    override val status: StateFlow<DeviceStatus> = _status
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 256) // audio arrives as ~10 chunks/s
    override val events: SharedFlow<GlassesEvent> = _events

    init { instance = this }

    private fun post(block: () -> Unit) { scope.launch { block() } }

    /** Silent when already authorized in Hi Rokid; sets CXR-L's in-memory flags (quirk 2.1.1). */
    fun authorize(activity: Activity, onDone: (Boolean) -> Unit) {
        try {
            manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
                Log.i(TAG, "authorize ok=${r.isSuccess} code=${r.errorCode}")
                r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences(PREFS, 0).edit().putString(KEY_TOKEN, it).apply() }
                // Success without any usable token would loop AuthActivity: treat it as a failure.
                val ok = r.isSuccess && (r.token != null || app.getSharedPreferences(PREFS, 0).getString(KEY_TOKEN, null) != null)
                post { onAuthResult(ok) }
                onDone(ok)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed", e)
            post { onAuthResult(false) }
            onDone(false)
        }
    }

    /** Result of an in-process authorization (also fed by [AuthActivity.onActivityResult]). */
    internal fun onAuthResult(ok: Boolean) {
        if (!auth.result(ok)) return // late or duplicate report for an attempt already resolved
        authJob?.cancel(); authJob = null
        if (ok) {
            authDeclined = false
            act(policy.manualConnect())
        } else {
            authDeclined = true // no auto-relaunch; a manual connect or the next presence event retries
            act(policy.onEvent(LinkEvent.AuthFailed))
        }
    }

    fun onDevicePresence(present: Boolean) = post {
        if (present) authDeclined = false
        act(policy.onEvent(if (present) LinkEvent.DevicePresent else LinkEvent.DeviceGone))
    }

    override fun connect() = post {
        auth.clearStale(System.currentTimeMillis())
        authDeclined = false
        act(policy.manualConnect())
    }

    private fun act(actions: List<LinkAction>) {
        if (actions.isNotEmpty()) { retryJob?.cancel(); retryJob = null; connectJob?.cancel(); connectJob = null }
        for (a in actions) when (a) {
            LinkAction.Connect -> openSession()
            LinkAction.SendSettings -> scope.launch { lastSettings?.let { pushSettings(it) } }
            is LinkAction.ScheduleRetry -> retryJob = scope.launch { delay(a.delayMs); act(policy.onEvent(LinkEvent.RetryTimer)) }
            LinkAction.MarkConnected -> _status.update { it.copy(link = LinkState.Connected, detail = null) }
            LinkAction.MarkConnecting -> _status.update { it.copy(link = LinkState.Connecting, detail = null) }
            LinkAction.MarkDisconnected -> _status.update { it.copy(link = LinkState.Disconnected, detail = null) }
            LinkAction.MarkClosedOnGlasses -> _status.update { it.copy(link = LinkState.Disconnected, detail = "LiveFit closed on glasses") }
            LinkAction.MarkAuthNeeded -> _status.update { it.copy(link = LinkState.Disconnected, detail = "Authorize in Hi Rokid") }
        }
    }

    private fun openSession() {
        val token = app.getSharedPreferences(PREFS, 0).getString(KEY_TOKEN, null)
        if (token == null || !auth.authorized) {
            // Background authorization: companion apps may start activities (spec §5.3).
            // CXR-L only works after an in-process requestAuthorization, so connect waits for AuthActivity.
            if (authDeclined) { act(policy.onEvent(LinkEvent.AuthFailed)); return }
            if (!auth.begin(System.currentTimeMillis())) return
            _status.update { it.copy(detail = "Authorizing with Hi Rokid") }
            // A blocked background start never reports back: give up after the timeout.
            authJob = scope.launch {
                delay(AuthGate.AUTH_TIMEOUT_MS)
                auth.expire()
                authDeclined = true
                act(policy.onEvent(LinkEvent.AuthFailed))
            }
            if (AuthActivity.launch(app).isFailure) onAuthResult(false)
            return
        }
        connectJob = scope.launch { delay(CONNECT_TIMEOUT_MS); act(policy.onEvent(LinkEvent.ConnectTimeout)) }
        try {
            preferGlobalHiRokid()
            // Detach the old session first so its late close callback cannot clobber the new one.
            val old = session
            session = null
            old?.close()
            inbound.reset()
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

    /**
     * Forget the rejected token and the in-process authorization (review #11). The retry then goes through the
     * normal bounded authorization path in [openSession]; a fresh token rejected again stops at "Authorize in Hi Rokid".
     */
    private fun onTokenRejected() {
        app.getSharedPreferences(PREFS, 0).edit().remove(KEY_TOKEN).apply()
        if (auth.tokenRejected()) act(policy.onEvent(LinkEvent.ConnectFailed))
        else { authDeclined = true; act(policy.onEvent(LinkEvent.AuthFailed)) }
    }

    private fun onGlassesMessage(cmd: String, bytes: ByteArray?) {
        val text = try {
            bytes?.let { Caps.fromBytes(it).at(0).string }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "bad payload on $cmd", e); null
        }
        if (text.isNullOrEmpty()) return
        inbound.onMessage(cmd, text).forEach { _events.tryEmit(it) }
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

    /** CXR callbacks arrive on binder threads: hop onto [scope] before touching the policy or session. */
    private fun lifecycleFor(s: CxrSession) = object : ISessionLifecycleCbk {
        private fun ifCurrent(event: LinkEvent) = post { if (s === session) act(policy.onEvent(event)) }
        override fun onSessionStarted() = post { if (s === session) { auth.sessionStarted(); act(policy.onEvent(LinkEvent.Started)) } }
        override fun onSessionPaused(reason: PausedReason) = ifCurrent(LinkEvent.Paused)
        override fun onSessionResumed() = ifCurrent(LinkEvent.Resumed)
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = Unit
        override fun onSessionClosed(reason: CloseReason) = post {
            if (s !== session) return@post
            session = null
            val glassesLeft = reason == CloseReason.GLASSES_EXIT || reason == CloseReason.USER_CLOSED
            act(policy.onEvent(if (glassesLeft) LinkEvent.GlassesExited else LinkEvent.Closed))
        }
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) = post {
            if (ok || s !== session) return@post
            session = null
            Log.w(TAG, "connect failed $code")
            if (code == SessionErrorCode.TOKEN_EXPIRED || code == SessionErrorCode.NOT_AUTHENTICATED) onTokenRejected()
            else act(policy.onEvent(LinkEvent.ConnectFailed))
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

    companion object {
        const val TAG = "LiveFitGlassesLink"
        const val GLASSES_PKG = "com.debasish.livefit.glasses"
        const val CONNECT_TIMEOUT_MS = 15_000L
        internal const val PREFS = "rokid"
        internal const val KEY_TOKEN = "token"
        @Volatile var instance: CxrGlassesLink? = null
    }
}
