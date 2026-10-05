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
import android.util.Log
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
        if (Build.VERSION.SDK_INT < 33) { Log.w(TAG, "companion pairing needs Android 13+"); onResult(false); return }
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
            override fun onFailure(error: CharSequence?) { Log.w(TAG, "associate failed: $error"); onResult(false) }
        })
    }

    /** True when an association for [kind] exists and can be removed from the app (API 33+). */
    fun canUnpair(context: Context, kind: DeviceKind): Boolean =
        Build.VERSION.SDK_INT >= 33 && context.getSharedPreferences("companion", 0).getInt(kind.name, -1) >= 0

    fun disassociate(context: Context, kind: DeviceKind): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val prefs = context.getSharedPreferences("companion", 0)
        val id = prefs.getInt(kind.name, -1).takeIf { it >= 0 } ?: return false
        context.getSystemService(CompanionDeviceManager::class.java).disassociate(id)
        prefs.edit().remove(kind.name).apply()
        presence.remove(id)
        return true
    }

    /** Call at app start: re-arms presence observation for saved associations. */
    fun observePresence(context: Context) {
        val prefs = context.getSharedPreferences("companion", 0)
        DeviceKind.entries.mapNotNull { k -> prefs.getInt(k.name, -1).takeIf { it >= 0 } }.forEach { observe(context, it) }
    }

    /** Transports that can report a device as nearby; present = any of them still reports it. */
    enum class Source { Ble, Bt, Legacy }

    private val presence = mutableMapOf<Int, MutableSet<Source>>()

    /** Records one transport's report and returns whether the association is present on any transport. */
    @Synchronized
    fun setPresent(associationId: Int, source: Source, isPresent: Boolean): Boolean {
        val sources = presence.getOrPut(associationId) { mutableSetOf() }
        if (isPresent) sources += source else sources -= source
        if (sources.isEmpty()) presence.remove(associationId)
        return sources.isNotEmpty()
    }

    @Synchronized
    fun anyPresent(): Boolean = presence.isNotEmpty()

    fun kindFor(context: Context, associationId: Int): DeviceKind? =
        DeviceKind.entries.firstOrNull { context.getSharedPreferences("companion", 0).getInt(it.name, -1) == associationId }

    private fun observe(context: Context, associationId: Int) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build())
            } else if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("DEPRECATION")
                cdm.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.let { cdm.startObservingDevicePresence(it) }
            } else {
                Log.w(TAG, "presence observation needs Android 13+")
            }
        } catch (e: Exception) {
            Log.w(TAG, "observe presence failed for $associationId", e)
        }
    }

    private const val TAG = "CompanionLinker"
    private const val REQUEST_CODE = 4711
}
