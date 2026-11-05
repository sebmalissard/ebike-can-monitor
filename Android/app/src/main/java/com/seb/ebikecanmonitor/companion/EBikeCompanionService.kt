package com.seb.ebikecanmonitor.companion

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import com.seb.ebikecanmonitor.Monitor

/**
 * Bound by the system when the paired ESP32 comes in range, which also starts the app process
 * if needed. We only have to trigger the connection; [com.seb.ebikecanmonitor.MonitorService]
 * then keeps the process alive for the duration of the link.
 */
class EBikeCompanionService : CompanionDeviceService() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Monitor.get(this).onDeviceNearby()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        // Nothing to do: the BLE link reports its own loss
    }
}
