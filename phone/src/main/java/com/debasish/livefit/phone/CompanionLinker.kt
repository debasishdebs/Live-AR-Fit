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
    /** Glasses are matched by name; the watch by the companion WATCH profile (any brand), not by its Bluetooth name. */
    private val glassesName = Pattern.compile("(?i)(glasses|rokid|rg).*")

    fun associate(activity: Activity, kind: DeviceKind, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { Log.w(TAG, "companion pairing needs Android 13+"); onResult(false); return }
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothDeviceFilter.Builder().apply { if (kind == DeviceKind.Glasses) setNamePattern(glassesName) }.build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .apply { if (kind == DeviceKind.Watch) setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH) }
            .setSingleDevice(false)
            .build()
        val executor = Executor { activity.runOnUiThread(it) }
        cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0)
            }
            override fun onAssociationCreated(info: AssociationInfo) {
                activity.getSharedPreferences("companion", 0).edit().putInt(kind.name, info.id).apply()
                val enabled = (activity.application as? LiveFitApp)?.services?.settings?.startWhenNearby(kind) ?: true
                if (enabled) observe(activity, info.id)
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

    /**
     * Call at app start and whenever a "Start LiveFit when nearby" toggle changes (R2): observes the presence of saved
     * associations whose toggle is on and stops observing (and forgets the presence of) those whose toggle is off.
     */
    fun observePresence(context: Context, enabled: (DeviceKind) -> Boolean) {
        val prefs = context.getSharedPreferences("companion", 0)
        val paired = DeviceKind.entries.mapNotNull { k -> prefs.getInt(k.name, -1).takeIf { it >= 0 }?.let { k to it } }.toMap()
        val plan = NearbyPolicy.observation(paired, enabled)
        plan.observe.forEach { observe(context, it) }
        plan.stopObserving.forEach { id ->
            stopObserving(context, id)
            synchronized(this) { presence.remove(id) }
        }
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

    @Synchronized
    fun isPresent(associationId: Int): Boolean = presence.containsKey(associationId)

    fun associationId(context: Context, kind: DeviceKind): Int? =
        context.getSharedPreferences("companion", 0).getInt(kind.name, -1).takeIf { it >= 0 }

    /**
     * True when the bonded Bluetooth device for [kind] is connected right now (presence is in memory only, so it is
     * unknown after a process restart). Matches the association's MAC, else the glasses name pattern / a wearable-class device for the watch (no "watch" in the name needed).
     * Uses the hidden BluetoothDevice.isConnected() (greylisted); any failure or missing BLUETOOTH_CONNECT means false.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun isBtConnected(context: Context, kind: DeviceKind): Boolean = try {
        val mac = if (Build.VERSION.SDK_INT >= 33) associationId(context, kind)?.let { id ->
            context.getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id == id }?.deviceMacAddress?.toString()
        } else null
        val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        val bonded = adapter?.bondedDevices.orEmpty().filter { d ->
            if (mac != null) d.address.equals(mac, ignoreCase = true) else matchesKind(kind, d.name, d.bluetoothClass?.majorDeviceClass)
        }
        val isConnected = android.bluetooth.BluetoothDevice::class.java.getMethod("isConnected")
        bonded.any { isConnected.invoke(it) as? Boolean == true }
    } catch (e: Exception) {
        Log.w(TAG, "bonded ${kind.name} connection check failed", e)
        false
    }

    private fun matchesKind(kind: DeviceKind, name: String?, majorClass: Int?): Boolean = when (kind) {
        DeviceKind.Glasses -> glassesName.matcher(name ?: "").matches()
        else -> majorClass == android.bluetooth.BluetoothClass.Device.Major.WEARABLE
    }

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

    private fun stopObserving(context: Context, associationId: Int) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build())
            } else if (Build.VERSION.SDK_INT >= 33) {
                @Suppress("DEPRECATION")
                cdm.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.let { cdm.stopObservingDevicePresence(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop observing presence failed for $associationId", e)
        }
    }

    private const val TAG = "CompanionLinker"
    private const val REQUEST_CODE = 4711
}
