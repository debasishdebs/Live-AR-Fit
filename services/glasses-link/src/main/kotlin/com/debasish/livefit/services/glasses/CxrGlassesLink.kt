package com.debasish.livefit.services.glasses

import android.app.Activity
import android.content.Context
import android.util.Log
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.LinkState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Live glasses link over Rokid CXR-L (via the Hi Rokid app). Launches the HUD APK on the glasses
 * and streams [StateFrame]s on [GlassesChannels.STATE]; glasses requests arrive on LISTEN / COMMAND.
 */
class CxrGlassesLink(context: Context) : GlassesLinkService {
    private val app = context.applicationContext
    private val manager = CxrSessionManager.getInstance(app)
    private var session: CxrSession? = null

    private val _status = MutableStateFlow(DeviceStatus("Rokid Glasses", LinkState.Disconnected))
    override val status: StateFlow<DeviceStatus> = _status
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 8)
    override val events: Flow<GlassesEvent> = _events

    /**
     * CXR-L 1.1.2 keeps "use the global Hi Rokid app" and granted permissions only in memory,
     * set by requestAuthorization(); call this from an Activity once per process before [connect].
     * Hi Rokid returns immediately when the app is already authorised.
     */
    fun authorize(activity: Activity, onDone: (Boolean) -> Unit = {}) {
        manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
            Log.i(TAG, "authorize ok=${r.isSuccess} code=${r.errorCode}")
            r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences(PREFS, 0).edit().putString("token", it).apply() }
            onDone(r.isSuccess)
        }
    }

    override fun connect() {
        val token = app.getSharedPreferences(PREFS, 0).getString("token", null)
        if (token == null) { _status.update { it.copy(link = LinkState.Disconnected, detail = "Authorise in Hi Rokid") }; return }
        if (session != null && _status.value.link == LinkState.Connected) return // replaces a session still Connecting
        preferGlobalHiRokid()
        session?.close()
        _status.update { it.copy(link = LinkState.Connecting, detail = null) }
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
        s.addLifecycleCallback(lifecycle)
        s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
            override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) {
                when (cmd) {
                    GlassesChannels.LISTEN -> _events.tryEmit(GlassesEvent.Listen)
                    GlassesChannels.COMMAND -> bytes?.let { b ->
                        runCatching { Wire.decode<CommandEnvelope>(Caps.fromBytes(b).at(0).string) }.getOrNull()?.let { _events.tryEmit(GlassesEvent.Issue(it)) }
                    }
                }
            }
        })
        session = s
        s.connect(token)
    }

    override suspend fun push(frame: StateFrame) = send(GlassesChannels.STATE, Wire.encode(frame))

    override suspend fun pushSettings(frame: HudSettingsFrame) = send(GlassesChannels.SETTINGS, Wire.encode(frame))

    private fun send(channel: String, json: String) {
        val s = session ?: return
        if (_status.value.link != LinkState.Connected) return
        val r = s.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))
        if (!r.isSuccess) Log.w(TAG, "push failed ${r.code} ${r.message}")
    }

    private val lifecycle = object : ISessionLifecycleCbk {
        override fun onSessionStarted() = set(LinkState.Connected)
        override fun onSessionPaused(reason: PausedReason) = set(LinkState.Connecting, "Paused: $reason")
        override fun onSessionResumed() = set(LinkState.Connected)
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = set(LinkState.Connecting, "Closing: $reason")
        override fun onSessionClosed(reason: CloseReason) { session = null; set(LinkState.Disconnected, "Closed: $reason") }
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) { if (!ok) set(LinkState.Disconnected, "Connect failed: $code") }
    }

    private fun set(link: LinkState, detail: String? = null) {
        Log.i(TAG, "link=$link $detail")
        _status.update { it.copy(link = link, detail = detail) }
    }

    /** See [authorize]: after a process restart CXR-L would otherwise bind the China app package. */
    private fun preferGlobalHiRokid() {
        val global = runCatching { app.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!global) return
        runCatching {
            Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
                .getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        }.onFailure { Log.w(TAG, "could not force global Hi Rokid", it) }
    }

    companion object {
        const val TAG = "LiveFitGlassesLink"
        const val GLASSES_PKG = "com.debasish.livefit.glasses"
        private const val PREFS = "rokid"
    }
}
