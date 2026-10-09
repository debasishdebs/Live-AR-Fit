package com.debasish.livefit.watch

import com.debasish.livefit.model.DiscoverableRequest

/**
 * What a `livefit://` URI may do on the watch (spec §4, review P1-2). The BROWSABLE filters stay (RemoteActivityHelper
 * needs them), but a URI never authorises anything: `livefit://workout` only opens or raises the UI — starts, stops and
 * takeovers still need the phone's Data Layer command and the normal confirmation — and `livefit://discoverable` only
 * shows the system prompt, which the user must accept. Unknown hosts, paths and parameters are ignored. Pure.
 */
sealed interface LaunchEntry {
    data object OpenWorkoutUi : LaunchEntry
    data class ShowDiscoverablePrompt(val seconds: Int) : LaunchEntry
    data object Ignore : LaunchEntry

    companion object {
        const val SCHEME = "livefit"
        const val MAX_REQUEST_CHARS = 256

        fun of(scheme: String?, host: String?, path: String?, request: String?): LaunchEntry {
            if (scheme != SCHEME || !(path.isNullOrEmpty() || path == "/")) return Ignore
            return when (host) {
                "workout" -> OpenWorkoutUi
                "discoverable" -> request?.takeIf { it.length <= MAX_REQUEST_CHARS }?.let(DiscoverableRequest::parse)?.let(::ShowDiscoverablePrompt) ?: Ignore
                else -> Ignore
            }
        }
    }
}
