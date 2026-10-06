package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command

/** One utterance → commands in spoken order, plus the clauses that matched nothing (F5). */
data class ParsedUtterance(val commands: List<Command>, val notUnderstood: List<String>)

/** Splits "pause music and stop workout" into clauses for the single-command parser (spec §5.4). */
object EnglishClauses {
    private val separators = Regex("""\s*,\s*|\s*\band then\b\s*|\s*\bthen\b\s*|\s*\band\b\s*""", RegexOption.IGNORE_CASE)
    /** Clauses made only of these are dropped ("hey, start workout please" is one command). */
    private val filler = setOf("hey", "hi", "ok", "okay", "please", "um", "uh", "so", "now", "alright", "rokid", "also")

    fun split(utterance: String): List<String> = utterance.split(separators)
        .map { it.trim().trim('.', '!', '?', ';', ':').trim() }
        .filter { clause -> clause.isNotEmpty() && !clause.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.all { it in filler } }
}

/** Parses each clause with this pack's single-command parser. */
fun LanguagePack.parseUtterance(utterance: String): ParsedUtterance {
    val commands = mutableListOf<Command>()
    val missed = mutableListOf<String>()
    for (clause in splitClauses(utterance)) parseCommand(clause)?.let { commands += it } ?: run { missed += clause }
    return ParsedUtterance(commands, missed)
}
