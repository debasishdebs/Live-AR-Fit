package com.debasish.livefit.phone

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import com.debasish.livefit.model.DeviceKind
import java.util.concurrent.Executor
import java.util.regex.Pattern

/** Associates the glasses / watch with LiveFit so Android wakes the hub when they're nearby (spec §5.2). */
object CompanionLinker {
    private val namePatterns = mapOf(
        DeviceKind.Glasses to Pattern.compile("(?i)(glasses|rokid|rg).*"),
        DeviceKind.Watch to Pattern.compile("(?i)(galaxy watch|watch).*"),
    )

    fun associate(activity: Activity, kind: DeviceKind, onResult: (Boolean) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setNamePattern(namePatterns.getValue(kind)).build())
            .setSingleDevice(false)
            .build()
        val executor = Executor { activity.runOnUiThread(it) }
        cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0)
            }
            override fun onAssociationCreated(info: AssociationInfo) {
                activity.getSharedPreferences("companion", 0).edit().putInt(kind.name, info.id).apply()
                observe(activity, info.id)
                onResult(true)
            }
            override fun onFailure(error: CharSequence?) = onResult(false)
        })
    }

    /** Call at app start: re-arms presence observation for saved associations. */
    fun observePresence(context: Context) {
        val prefs = context.getSharedPreferences("companion", 0)
        DeviceKind.entries.mapNotNull { k -> prefs.getInt(k.name, -1).takeIf { it >= 0 } }.forEach { observe(context, it) }
    }

    private val present = mutableSetOf<Int>()
    fun setPresent(context: Context, associationId: Int, isPresent: Boolean) { if (isPresent) present += associationId else present -= associationId }
    fun anyPresent(context: Context): Boolean = present.isNotEmpty()

    fun kindFor(context: Context, associationId: Int): DeviceKind? =
        DeviceKind.entries.firstOrNull { context.getSharedPreferences("companion", 0).getInt(it.name, -1) == associationId }

    private fun observe(context: Context, associationId: Int) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.let { cdm.startObservingDevicePresence(it) }
            }
        }
    }

    private const val REQUEST_CODE = 4711
}
