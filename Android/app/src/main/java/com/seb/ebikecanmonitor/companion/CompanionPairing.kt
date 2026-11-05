package com.seb.ebikecanmonitor.companion

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.seb.ebikecanmonitor.ble.BleClient

/**
 * Pairs the ESP32 with the app through the system CompanionDeviceManager, once.
 * From then on Android watches for the device and wakes the app up
 * (see [EBikeCompanionService]) when it comes in range, even if the process was killed.
 */
object CompanionPairing {

    private const val TAG = "CompanionPairing"

    private fun manager(context: Context): CompanionDeviceManager? =
        context.getSystemService(CompanionDeviceManager::class.java)

    fun isAssociated(context: Context): Boolean =
        manager(context)?.myAssociations?.isNotEmpty() == true

    /**
     * Starts the system pairing flow for the ESP32 (matched on its Nordic UART service).
     * [onPending] receives the system dialog to launch; [onFailure] is called if no device is found.
     */
    fun associate(context: Context, onPending: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val cdm = manager(context)
        if (cdm == null) {
            onFailure("CompanionDeviceManager unavailable")
            return
        }

        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleClient.NUS_SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        cdm.associate(
            request,
            ContextCompat.getMainExecutor(context),
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onPending(intentSender)

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    startObserving(context, associationInfo)
                }

                override fun onFailure(error: CharSequence?) = onFailure(error?.toString().orEmpty())
            },
        )
    }

    /** Asks the system to watch for every paired device. Safe to call repeatedly. */
    fun ensureObserving(context: Context) {
        try {
            manager(context)?.myAssociations?.forEach { startObserving(context, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot restore device presence observation", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startObserving(context: Context, info: AssociationInfo) {
        val address = info.deviceMacAddress?.toString()?.uppercase() ?: return
        try {
            manager(context)?.startObservingDevicePresence(address)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot observe device presence", e)
        }
    }
}
