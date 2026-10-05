package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command

/** Everything voice needs for one language. Add a language by registering a pack (spec §3.1 principle 4). */
data class LanguagePack(
    val locale: String,
    val displayName: String,
    val parseCommand: (String) -> Command?,
    val parseYesNo: (String) -> Boolean?,
)

object LanguageRegistry {
    const val DEFAULT_LOCALE = "en-IN"

    private fun english(locale: String, name: String) =
        LanguagePack(locale, name, CommandParser::parse, YesNoParser::parse)

    val packs: Map<String, LanguagePack> = listOf(
        english("en-IN", "English (India)"),
        english("en-US", "English (US)"),
        english("en-GB", "English (UK)"),
    ).associateBy { it.locale }

    fun forLocale(locale: String): LanguagePack? = packs[locale]
}
