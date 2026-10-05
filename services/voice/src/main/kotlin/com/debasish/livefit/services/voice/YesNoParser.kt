package com.debasish.livefit.services.voice

/** English yes/no lexicon for confirmations (spec §5.4). Negation wins over affirmation. */
object YesNoParser {
    private val no = listOf("no", "nope", "nah", "cancel", "stop", "don t", "dont", "do not", "leave it", "not now")
    private val yes = listOf("yes", "yeah", "yep", "yup", "ok", "okay", "sure", "confirm", "take over", "do it", "go ahead")

    fun parse(utterance: String): Boolean? {
        val t = utterance.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        fun has(words: List<String>) = words.any { Regex("\\b$it\\b").containsMatchIn(t) }
        return when {
            has(no) -> false
            has(yes) -> true
            else -> null
        }
    }
}
