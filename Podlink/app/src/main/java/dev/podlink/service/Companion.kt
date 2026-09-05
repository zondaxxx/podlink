package dev.podlink.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import java.util.regex.Pattern

/**
 * Companion Device Manager integration. One system dialog associates Podlink with the AirPods; from then on
 * Android itself lets us run in the background, start the foreground service from the background and
 * wakes [PodsCompanionService] when the headset appears — without depending on OEM battery whitelists.
 */
object CompanionLink {
    private const val TAG = "Companion"

    fun available(context: Context): Boolean =
        context.packageManager.hasSystemFeature("android.software.companion_device_setup")

    private fun cdm(context: Context) = context.getSystemService(CompanionDeviceManager::class.java)

    /** Addresses (upper-case) currently associated with this app. */
    @Suppress("DEPRECATION")
    fun associatedAddresses(context: Context): Set<String> {
        val m = cdm(context) ?: return emptySet()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) m.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }.toSet()
            else m.associations.map { it.uppercase() }.toSet()
        }.getOrDefault(emptySet())
    }

    fun isAssociated(context: Context, address: String?): Boolean =
        address != null && address.uppercase() in associatedAddresses(context)

    /**
     * Ask the system to associate with the given bonded headset (or any Apple/Beats headset when null).
     * [onChooser] receives the IntentSender that must be launched from an Activity.
     */
    @SuppressLint("MissingPermission")
    fun associate(context: Context, address: String?, name: String?, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val m = cdm(context) ?: return onError("no CompanionDeviceManager")
        val filter = BluetoothDeviceFilter.Builder().apply {
            if (address != null) setAddress(address)
            else setNamePattern(Pattern.compile("(?i).*(airpods|beats).*"))
        }.build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(address != null).build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(chooserLauncher: IntentSender) = onChooser(chooserLauncher)
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)
            override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "failed")
        }
        runCatching { m.associate(request, callback, null) }.onFailure { onError(it.message ?: it.toString()) }
        Log.i(TAG, "associate requested for ${name ?: address}")
    }

    /** After a successful association: subscribe to presence so the system starts us when the headset shows up. */
    fun observePresence(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val m = cdm(context) ?: return
        associatedAddresses(context).forEach { addr ->
            @Suppress("DEPRECATION")
            runCatching { m.startObservingDevicePresence(addr) }
                .onSuccess { Log.i(TAG, "observing presence of $addr") }
                .onFailure { Log.w(TAG, "observe $addr failed: ${it.message}") }
        }
    }

    fun disassociateAll(context: Context) {
        val m = cdm(context) ?: return
        @Suppress("DEPRECATION")
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) m.myAssociations.forEach { m.disassociate(it.id) }
            else m.associations.forEach { m.disassociate(it) }
        }
    }
}

/** Bound by the system when an associated device appears / disappears (API 31+). */
class PodsCompanionService : CompanionDeviceService() {
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        Log.i("Companion", "device appeared: $address")
        Watchdog.ensureRunning(this, fromBoot = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onDeviceDisappeared(address: String) {
        Log.i("Companion", "device disappeared: $address")
    }

    @Suppress("unused")
    private fun BluetoothDevice.label() = address
}
