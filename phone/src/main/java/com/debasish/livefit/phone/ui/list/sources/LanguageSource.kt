package com.debasish.livefit.phone.ui.list.sources

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import com.debasish.livefit.phone.speech.SpeechPacks
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ItemStatus
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject
import java.util.Locale

/** On-device speech recognition language packs. */
class LanguageSource(private val context: Context) : ListSource {
    override val title = "Languages"
    override val searchHint = "Search languages"
    override val statusLabels = mapOf(ItemStatus.Done to "Downloaded", ItemStatus.ActionNeeded to "Not downloaded")
    override val doneSection = "On this phone"
    override val actionSection = "Available to download"
    override val actionIcon = Icons.Rounded.CloudDownload

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val packs = SpeechPacks.query(context)
        return packs.supported.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            val status = when (tag) {
                in packs.installed -> ItemStatus.Done
                in packs.pending -> ItemStatus.InProgress
                else -> ItemStatus.ActionNeeded
            }
            ListItem(
                id = tag,
                title = locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() },
                subtitle = listOfNotNull(locale.getDisplayCountry(Locale.getDefault()).takeIf { it.isNotBlank() }, tag).joinToString(" · "),
                glyph = flagFor(locale) ?: locale.language.uppercase(),
                status = status,
            )
        }
    }

    override fun actionFor(item: ListItem): ItemAction? {
        if (item.status != ItemStatus.ActionNeeded) return null
        return ItemAction(
            confirmTitle = "Download ${item.title}?",
            confirmMessage = "Lets Rokid LiveFit understand voice commands in ${item.title} (${item.id}) without internet. Google downloads the pack once; Wi-Fi recommended.",
            confirmLabel = "Download",
            blocking = true,
        ) { onProgress ->
            when (val r = SpeechPacks.download(context, item.id, onProgress)) {
                SpeechPacks.Result.Success -> ActionResult.Done
                SpeechPacks.Result.Scheduled -> ActionResult.Scheduled("Download scheduled. Google will finish it in the background, usually on Wi-Fi.")
                is SpeechPacks.Result.Error -> ActionResult.Failed(r.message)
            }
        }
    }

    private fun flagFor(locale: Locale): String? {
        val region = locale.country.takeIf { it.length == 2 } ?: return null
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + region[0].code)) + String(Character.toChars(base + region[1].code))
    }
}
