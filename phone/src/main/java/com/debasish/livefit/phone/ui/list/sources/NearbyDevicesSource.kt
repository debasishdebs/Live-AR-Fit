package com.debasish.livefit.phone.ui.list.sources

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Watch
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.phone.CompanionLinker
import com.debasish.livefit.phone.NearbyPolicy
import com.debasish.livefit.phone.SettingsStore
import com.debasish.livefit.phone.ui.components.GlassesIcon
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ItemStatus
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/**
 * Settings → Nearby devices (R2, spec §5.2): companion-paired glasses / watch with "Start LiveFit when nearby".
 * Tapping a paired row flips its toggle and re-applies presence observation at once; an unpaired row opens its
 * Linked services screen.
 */
class NearbyDevicesSource(
    private val context: Context,
    private val settings: SettingsStore,
    private val open: (String) -> Unit,
) : ListSource {
    override val title = "Nearby devices"
    override val searchHint = "Search devices"
    override val sortable = false

    override suspend fun load(filter: JSONObject?): List<ListItem> = SettingsStore.NEARBY_KINDS.map { kind ->
        val paired = CompanionLinker.associationId(context, kind) != null
        val on = settings.startWhenNearby(kind)
        ListItem(
            id = kind.name,
            title = if (kind == DeviceKind.Glasses) "Rokid glasses" else "Galaxy Watch",
            subtitle = NearbyPolicy.subtitle(paired, on),
            icon = if (kind == DeviceKind.Glasses) GlassesIcon else Icons.Rounded.Watch,
            status = if (paired) ItemStatus.None else ItemStatus.ActionNeeded,
            toggle = on.takeIf { paired },
        )
    }

    override fun actionFor(item: ListItem): ItemAction {
        val kind = DeviceKind.valueOf(item.id)
        if (item.toggle == null) return ItemAction { open("linked/${kind.name.lowercase()}"); ActionResult.Silent }
        return ItemAction {
            settings.setStartWhenNearby(kind, !item.toggle)
            CompanionLinker.observePresence(context, settings::startWhenNearby)
            ActionResult.Silent
        }
    }
}
