package com.debasish.livefit.phone.ui.list.sources

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Visibility
import androidx.core.content.ContextCompat
import com.debasish.livefit.services.music.MediaListener
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ItemStatus
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/** Real permission checks; tapping an ungranted row opens the matching system screen. */
class PermissionSource(private val context: Context) : ListSource {
    override val title = "Permissions"
    override val searchHint = "Search permissions"
    override val statusLabels = mapOf(ItemStatus.Done to "Granted", ItemStatus.ActionNeeded to "Needs access")
    override val doneSection = "Granted"
    override val actionSection = "Needs your OK"

    override suspend fun load(filter: JSONObject?): List<ListItem> = listOf(
        row("mic", "Microphone", "Offline voice commands", Icons.Rounded.Mic, granted(Manifest.permission.RECORD_AUDIO)),
        row("bt", "Nearby devices", "Watch and glasses links", Icons.Rounded.Bluetooth, granted(Manifest.permission.BLUETOOTH_CONNECT)),
        row("media", "Music control", "Control YouTube Music", Icons.Rounded.LibraryMusic, mediaAccess()),
        row("battery", "Run in background", "Keep the workout alive", Icons.Rounded.BatteryChargingFull, unrestrictedBattery()),
        row("rokid", "Rokid glasses", "Authorised in Hi Rokid", Icons.Rounded.Visibility, context.getSharedPreferences("rokid", 0).contains("token")),
    )

    override fun actionFor(item: ListItem): ItemAction? {
        if (item.status != ItemStatus.ActionNeeded) return null
        val intent = when (item.id) {
            "media" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
        return ItemAction { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ActionResult.Silent }
    }

    private fun row(id: String, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, ok: Boolean) =
        ListItem(id, title, subtitle, icon = icon, status = if (ok) ItemStatus.Done else ItemStatus.ActionNeeded)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun mediaAccess(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(ComponentName(context, MediaListener::class.java).flattenToString())
    }

    private fun unrestrictedBattery() =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}
