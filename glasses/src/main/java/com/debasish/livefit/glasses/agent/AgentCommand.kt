package com.debasish.livefit.glasses.agent

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutType
import java.net.URLDecoder

/**
 * Hi Rokid agent commands (`GET /lf?cmd=<wire>` from the LiveFit agent page on these glasses). Each maps to the same
 * [Command] our own voice issues, so the hub applies the same Settings → Voice groups, Stop confirmation and page gate
 * (sent as [com.debasish.livefit.model.CommandVia.Agent]).
 */
enum class AgentCommand(val wire: String, private val base: Command, private val label: String) {
    Start("start", Command.StartWorkout(WorkoutType.Walk), "start workout"), // voice "start workout" is a walk too
    Pause("pause", Command.PauseWorkout, "pause workout"),
    Resume("resume", Command.ResumeWorkout, "resume workout"),
    Stop("stop", Command.StopWorkout, "stop workout"), // the hub asks first (StopWorkoutByVoice)
    PlayPause("play_pause", Command.PlayMusic, "play music"), // see commandFor
    Next("next", Command.NextTrack, "next song"),
    Previous("previous", Command.PreviousTrack, "previous song"),
    Stats("stats", Command.ShowGlassesPage(HudPage.Stats), "stats view"),
    Map("map", Command.ShowGlassesPage(HudPage.Map), "map view"),
    Music("music", Command.ShowGlassesPage(HudPage.MusicControls), "music view"),
    Playlist("playlist", Command.ShowGlassesPage(HudPage.Playlist), "playlist view"),
    Glance("glance", Command.ShowGlassesPage(HudPage.Glance), "glance view"),
    Workout("workout", Command.ShowGlassesPage(HudPage.Workout), "workout view");

    /** play_pause becomes the explicit voice command (PlayMusic / PauseMusic, a gated voice group) from the HUD's music state. */
    fun commandFor(musicPlaying: Boolean): Command = if (this == PlayPause && musicPlaying) Command.PauseMusic else base

    /** Neutral ack: the hub may still refuse it (a voice group turned off) or ignore it (no workout running). */
    fun sentAck(musicPlaying: Boolean): String = "Sent to LiveFit: " + if (this == PlayPause && musicPlaying) "pause music" else label

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

/** What the glasses know right now: the phone link (live / version mismatch), the pages that can be shown, music state. */
data class AgentContext(
    val connected: Boolean,
    val pages: PageSettings = PageSettings(),
    val mapEligible: Boolean = false,
    val outdated: Boolean = false,
    val musicPlaying: Boolean = false,
)

/** The agent's answer: [say] for the card, and the [send] command for the hub (null = nothing is sent). */
data class AgentReply(val ok: Boolean, val say: String, val send: Command?)

object AgentReplies {
    const val CANT = "I can't do that in LiveFit yet"
    const val NOT_CONNECTED = "Your phone isn't connected"
    const val OUTDATED = "Update LiveFit on your phone"

    fun plan(c: AgentCommand, ctx: AgentContext): AgentReply {
        if (ctx.outdated) return AgentReply(false, OUTDATED, null)
        if (!ctx.connected) return AgentReply(false, NOT_CONNECTED, null)
        val command = c.commandFor(ctx.musicPlaying)
        val page = (command as? Command.ShowGlassesPage)?.page
        return when {
            // Still sent: the hub's page gate toasts it on the HUD, exactly as for voice.
            page != null && !ctx.pages.isEnabled(page) -> AgentReply(false, "${page.label} page is turned off in Settings", command)
            page == HudPage.Map && !ctx.mapEligible -> AgentReply(false, "The map shows during a GPS workout", command)
            else -> AgentReply(true, c.sentAck(ctx.musicPlaying), command)
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
