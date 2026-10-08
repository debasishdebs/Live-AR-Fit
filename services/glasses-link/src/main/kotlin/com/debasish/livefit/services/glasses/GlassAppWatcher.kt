package com.debasish.livefit.services.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * Watches the glasses' foreground app while no CUSTOM_APP session is open (R1, spec §5.3).
 *
 * CXR-L 1.1.2 reports glasses foreground-app changes at link level: Hi Rokid's IAiEventCallback
 * `onGlassAppResumeChange(pkg, …)` (registered when CXRLink binds the media-stream service), which CXRLink turns into
 * [IGlassAppCbk.onGlassAppResume] (true when pkg is the configured custom-app package). Each CxrSession owns its own
 * CXRLink and unbinds it on close, so after a close on the glasses nothing is listening; this keeps a separate,
 * session-less CXRLink bound only while the link is "closed on glasses". [configCXRSession] is local state only
 * (it sets the package the callback compares against); no session is opened and nothing is sent to the glasses.
 */
internal class GlassAppWatcher(private val app: Context, private val onOurAppResumed: () -> Unit) {
    private var link: CXRLink? = null

    val active: Boolean get() = link != null

    fun start(token: String) {
        if (link != null) return
        try {
            val l = CXRLink(app)
            l.configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, CxrGlassesLink.GLASSES_PKG))
            l.setCXRLinkCbk(NoLinkCbk) // CXRLink dereferences it on every service callback
            l.setCXRGlassAppCbk(object : IGlassAppCbk {
                override fun onGlassAppResume(resumed: Boolean) {
                    Log.i(CxrGlassesLink.TAG, "watcher: glasses app resume ours=$resumed")
                    if (resumed) onOurAppResumed()
                }
                override fun onInstallAppResult(ok: Boolean) = Unit
                override fun onUnInstallAppResult(ok: Boolean) = Unit
                override fun onOpenAppResult(ok: Boolean) = Unit
                override fun onStopAppResult(ok: Boolean) = Unit
                override fun onQueryAppResult(ok: Boolean) = Unit
            })
            if (!l.connect(token)) { Log.w(CxrGlassesLink.TAG, "watcher: bind failed"); return }
            link = l
            Log.i(CxrGlassesLink.TAG, "watcher: watching for LiveFit reopened on glasses")
        } catch (e: Exception) {
            Log.w(CxrGlassesLink.TAG, "watcher: start failed", e)
        }
    }

    fun stop() {
        val l = link ?: return
        link = null
        try { l.disconnect() } catch (e: Exception) { Log.w(CxrGlassesLink.TAG, "watcher: stop failed", e) }
        Log.i(CxrGlassesLink.TAG, "watcher: stopped")
    }

    private object NoLinkCbk : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) = Unit
        override fun onGlassBtConnected(connected: Boolean) = Unit
        override fun onGlassDeviceInfo(info: GlassInfo) = Unit
        override fun onGlassWearingStatus(wearing: Boolean) = Unit
        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
        override fun onGlassLauncherResume() = Unit
    }
}
