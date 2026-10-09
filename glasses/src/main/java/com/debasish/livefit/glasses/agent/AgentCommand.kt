package com.debasish.livefit.glasses.agent

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutType
import java.net.URLDecoder

/**
 * Hi Rokid agent commands (`GET /lf?cmd=<wire>` from the LiveFit agent page on these glasses). Each maps to the same
 * [Command] the touchpad or our own voice issues; page views go through the hub's page gate like voice "map view".
 */
enum class AgentCommand(val wire: String, val command: Command, /** Short past-tense ack for the agent card. */ val say: String) {
    Start("start", Command.StartWorkout(WorkoutType.Walk), "Workout starting"), // voice "start workout" is a walk too
    Pause("pause", Command.PauseWorkout, "Workout paused"),
    Resume("resume", Command.ResumeWorkout, "Workout resumed"),
    Stop("stop", Command.StopWorkout, "Confirm to end the workout"), // the hub asks first (StopWorkoutByVoice)
    PlayPause("play_pause", Command.PlayPause, "Music played / paused"),
    Next("next", Command.NextTrack, "Next song"),
    Previous("previous", Command.PreviousTrack, "Previous song"),
    Stats("stats", Command.ShowGlassesPage(HudPage.Stats), "Showing stats"),
    Map("map", Command.ShowGlassesPage(HudPage.Map), "Showing the map"),
    Music("music", Command.ShowGlassesPage(HudPage.MusicControls), "Showing music controls"),
    Playlist("playlist", Command.ShowGlassesPage(HudPage.Playlist), "Showing the playlist"),
    Glance("glance", Command.ShowGlassesPage(HudPage.Glance), "Showing glance"),
    Workout("workout", Command.ShowGlassesPage(HudPage.Workout), "Showing the workout");

    companion object {
        /** Forgiving: case, surrounding whitespace, and spaces/hyphens for underscores ("Play Pause"). */
        fun of(text: String?): AgentCommand? {
            val t = text?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_") ?: return null
            return entries.firstOrNull { it.wire == t }
        }
    }
}

/** What a request head asks for. */
sealed interface AgentRequest {
    data class Run(val command: AgentCommand) : AgentRequest
    /** `/lf` without a known `cmd`. */
    data object Unknown : AgentRequest
    data object NotFound : AgentRequest
    data object BadMethod : AgentRequest
    data object Malformed : AgentRequest
    data object TooLarge : AgentRequest
}

object AgentRequestParser {
    /** Cap on the whole request head (request line + headers). */
    const val MAX_REQUEST_BYTES = 2_048

    fun parse(head: String): AgentRequest {
        if (head.length > MAX_REQUEST_BYTES) return AgentRequest.TooLarge
        val parts = head.substringBefore('\n').trimEnd('\r').split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/") || !parts[1].startsWith("/")) return AgentRequest.Malformed
        if (parts[0] != "GET") return if (parts[0].matches(Regex("[A-Z]{3,10}"))) AgentRequest.BadMethod else AgentRequest.Malformed
        val target = parts[1]
        if (target.substringBefore('?') != "/lf") return AgentRequest.NotFound
        val cmd = target.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == "cmd" }
            ?.substringAfter('=', "")
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        return AgentCommand.of(cmd)?.let(AgentRequest::Run) ?: AgentRequest.Unknown
    }
}

/** What the glasses know right now: the phone link and the pages that can be shown. */
data class AgentContext(val connected: Boolean, val pages: PageSettings = PageSettings(), val mapEligible: Boolean = false)

/** The agent's answer: [say] for the card, and the [send] command for the hub (null = nothing is sent). */
data class AgentReply(val ok: Boolean, val say: String, val send: Command?)

object AgentReplies {
    const val CANT = "I can't do that in LiveFit yet"
    const val NOT_CONNECTED = "Your phone isn't connected"

    fun plan(c: AgentCommand, ctx: AgentContext): AgentReply {
        if (!ctx.connected) return AgentReply(false, NOT_CONNECTED, null)
        val page = (c.command as? Command.ShowGlassesPage)?.page
        return when {
            // Still sent: the hub's page gate toasts it on the HUD, exactly as for voice.
            page != null && !ctx.pages.isEnabled(page) -> AgentReply(false, "${page.label} page is turned off in Settings", c.command)
            page == HudPage.Map && !ctx.mapEligible -> AgentReply(false, "The map shows during a GPS workout", c.command)
            else -> AgentReply(true, c.say, c.command)
        }
    }
}

object AgentJson {
    fun reply(ok: Boolean, say: String): String = """{"ok":$ok,"say":"${escape(say)}"}"""

    private fun escape(s: String) = buildString {
        s.forEach { ch ->
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }
}
