package com.arawn.scanner.classify

import com.arawn.scanner.BleObservation
import com.arawn.scanner.WifiObservation
import com.arawn.scanner.oui.OuiLookupManager

/**
 * Adapts ARAWN's transport/UI observation models into the engine's neutral
 * [ClassifierInput]. The only place that knows about both worlds, so the
 * classifier itself never imports a scan model.
 *
 * MAC scope is derived here via [OuiLookupManager.classify] so randomized vs.
 * global is available to rules without re-parsing the address.
 */
object ClassifierInputMapper {

    fun from(w: WifiObservation): ClassifierInput = ClassifierInput(
        transport = Transport.WIFI,
        mac = w.bssid,
        vendor = w.vendorName,
        macScope = OuiLookupManager.classify(w.bssid),
        rssiDbm = w.rssiDbm,
        ssid = w.ssid,
        frequencyMhz = w.frequencyMhz,
        capabilities = w.capabilities,
    )

    fun from(b: BleObservation): ClassifierInput = ClassifierInput(
        transport = Transport.BLE,
        mac = b.macAddress,
        vendor = b.vendorName,
        macScope = OuiLookupManager.classify(b.macAddress),
        rssiDbm = b.rssiDbm,
        name = b.name,
        txPower = b.txPower,
        serviceUuids = b.serviceUuids,
        manufacturerIds = b.manufacturerIds,
    )
}
