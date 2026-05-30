package com.arawn.scanner.classify.rules

import com.arawn.scanner.classify.ClassificationRule
import com.arawn.scanner.classify.ClassifierInput
import com.arawn.scanner.classify.DeviceClass
import com.arawn.scanner.classify.Transport
import com.arawn.scanner.oui.OuiLookupManager

/**
 * The default rule library.
 *
 * Each entry is one small, named heuristic. To add coverage you either add a
 * vendor to [VendorTables] or append a new `rule(...)` here — the engine, the
 * aggregator, and every other rule remain untouched. Rules that read BLE
 * service UUIDs / manufacturer IDs are included now but stay dormant until the
 * scan layer captures those fields (Phase 2 step 4 / Phase 5).
 *
 * Point budgets are deliberately modest; a "confident" verdict generally needs
 * two or three independent signals to agree, which is what keeps single-signal
 * guesses honestly in LOW_CONFIDENCE / AMBIGUOUS territory.
 */
internal object CoreRules {

    /** Concise factory for an anonymous, stateless rule. */
    private fun rule(
        id: String,
        body: (ClassifierInput, (DeviceClass, Int, String) -> Unit) -> Unit,
    ): ClassificationRule = object : ClassificationRule {
        override val id = id
        override fun evaluate(input: ClassifierInput, sink: (DeviceClass, Int, String) -> Unit) =
            body(input, sink)
    }

    val ALL: List<ClassificationRule> = listOf(

        // ---- Wi-Fi structural -------------------------------------------------
        rule("wifi.is_ap") { i, emit ->
            if (i.transport == Transport.WIFI)
                emit(DeviceClass.ROUTER_AP, 50, "Broadcasting as a Wi-Fi access point")
        },
        rule("wifi.ssid_present") { i, emit ->
            if (i.transport == Transport.WIFI && !i.ssid.isNullOrBlank())
                emit(DeviceClass.ROUTER_AP, 12, "Advertises an SSID")
        },
        rule("wifi.vendor_networking") { i, emit ->
            if (i.transport == Transport.WIFI && i.vendorMatches(VendorTables.NETWORKING))
                emit(DeviceClass.ROUTER_AP, 20, "Vendor is a common networking brand")
        },
        rule("wifi.mesh") { i, emit ->
            if (i.transport != Transport.WIFI) return@rule
            val ssid = i.ssid?.lowercase().orEmpty()
            val meshSsid = listOf("orbi", "deco", "velop", "eero", "nest wifi", "-mesh")
                .any { ssid.contains(it) }
            if (meshSsid || i.vendorMatches(VendorTables.MESH))
                emit(DeviceClass.MESH_NODE, 30, "Mesh-system naming or vendor")
        },

        // ---- Wi-Fi SSID/vendor product hints ---------------------------------
        rule("wifi.printer_direct") { i, emit ->
            if (i.transport != Transport.WIFI) return@rule
            val s = i.ssid?.lowercase().orEmpty()
            if (s.startsWith("direct-") && VendorTables.NAME_PRINTER.any { s.contains(it) })
                emit(DeviceClass.PRINTER, 35, "Wi-Fi Direct printer SSID")
            else if (i.vendorMatches(VendorTables.PRINTER))
                emit(DeviceClass.PRINTER, 25, "Vendor is a printer maker")
        },
        rule("wifi.tv_direct") { i, emit ->
            if (i.transport != Transport.WIFI) return@rule
            val s = i.ssid?.lowercase().orEmpty()
            if (s.contains("[tv]") || s.contains("directv-") || s.contains("samsung tv"))
                emit(DeviceClass.SMART_TV, 28, "Wi-Fi Direct TV SSID")
            else if (i.vendorMatches(VendorTables.TV))
                emit(DeviceClass.SMART_TV, 18, "Vendor makes smart TVs")
        },
        rule("wifi.streaming") { i, emit ->
            if (i.transport != Transport.WIFI) return@rule
            if (i.ssid?.lowercase()?.contains("chromecast") == true)
                emit(DeviceClass.STREAMING_DEVICE, 35, "Chromecast SSID")
        },
        rule("wifi.camera_vendor") { i, emit ->
            if (i.transport == Transport.WIFI && i.vendorMatches(VendorTables.CAMERA))
                emit(DeviceClass.SECURITY_CAMERA, 30, "Vendor makes IP cameras")
        },

        // ---- BLE structural / scope ------------------------------------------
        rule("ble.baseline") { i, emit ->
            // Weak prior: a BLE-only advertiser is some kind of IoT/peripheral.
            if (i.transport == Transport.BLE)
                emit(DeviceClass.IOT_DEVICE, 8, "BLE-only advertiser")
        },
        rule("ble.randomized") { i, emit ->
            // Privacy-randomized address with no name: typical of phones/wearables.
            if (i.transport == Transport.BLE &&
                i.macScope == OuiLookupManager.MacKind.RANDOMIZED &&
                i.name.isNullOrBlank()
            ) {
                emit(DeviceClass.SMARTPHONE, 15, "Randomized (private) address")
                emit(DeviceClass.WEARABLE, 8, "Randomized address (also typical of wearables)")
            }
        },

        // ---- BLE vendor leans -------------------------------------------------
        rule("ble.lighting_vendor") { i, emit ->
            if (i.transport == Transport.BLE && i.vendorMatches(VendorTables.LIGHTING))
                emit(DeviceClass.SMART_BULB, 25, "Vendor matches lighting ecosystem")
        },
        rule("ble.wearable_vendor") { i, emit ->
            if (i.transport == Transport.BLE && i.vendorMatches(VendorTables.WEARABLE))
                emit(DeviceClass.WEARABLE, 30, "Vendor makes wearables")
        },
        rule("ble.speaker_vendor") { i, emit ->
            if (i.transport == Transport.BLE && i.vendorMatches(VendorTables.SPEAKER))
                emit(DeviceClass.SMART_SPEAKER, 26, "Vendor makes audio/speaker devices")
        },
        rule("ble.camera_vendor") { i, emit ->
            if (i.transport == Transport.BLE && i.vendorMatches(VendorTables.CAMERA))
                emit(DeviceClass.SECURITY_CAMERA, 25, "Vendor makes IP cameras")
        },
        rule("ble.vehicle_vendor") { i, emit ->
            if (i.transport == Transport.BLE && i.vendorMatches(VendorTables.VEHICLE))
                emit(DeviceClass.VEHICLE_SYSTEM, 25, "Vendor is automotive")
        },
        rule("ble.iot_vendor") { i, emit ->
            if (i.transport == Transport.BLE && i.vendorMatches(VendorTables.IOT_GENERIC))
                emit(DeviceClass.IOT_DEVICE, 22, "Vendor is generic IoT silicon")
        },

        // ---- BLE name keywords (names are captured today) --------------------
        rule("ble.name_bulb") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_BULB))
                emit(DeviceClass.SMART_BULB, 28, "Device name indicates lighting")
        },
        rule("ble.name_wearable") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_WEARABLE))
                emit(DeviceClass.WEARABLE, 26, "Device name indicates a wearable")
        },
        rule("ble.name_speaker") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_SPEAKER))
                emit(DeviceClass.SMART_SPEAKER, 24, "Device name indicates a speaker")
        },
        rule("ble.name_tv") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_TV))
                emit(DeviceClass.SMART_TV, 22, "Device name indicates a TV")
        },
        rule("ble.name_camera") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_CAMERA))
                emit(DeviceClass.SECURITY_CAMERA, 24, "Device name indicates a camera")
        },
        rule("ble.name_printer") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_PRINTER))
                emit(DeviceClass.PRINTER, 24, "Device name indicates a printer")
        },
        rule("ble.name_vehicle") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_VEHICLE))
                emit(DeviceClass.VEHICLE_SYSTEM, 22, "Device name indicates a vehicle")
        },
        rule("ble.name_beacon") { i, emit ->
            if (i.transport == Transport.BLE && i.nameContains(VendorTables.NAME_BEACON))
                emit(DeviceClass.BLE_BEACON, 30, "Device name indicates a beacon/tag")
        },

        // ---- BLE service-UUID rules (DORMANT until UUID capture lands) --------
        rule("ble.uuid_eddystone") { i, emit ->
            if (i.transport == Transport.BLE && i.hasServiceUuid(VendorTables.UUID_EDDYSTONE))
                emit(DeviceClass.BLE_BEACON, 50, "Eddystone beacon service")
        },
        rule("ble.uuid_wearable") { i, emit ->
            if (i.transport == Transport.BLE &&
                VendorTables.UUID_WEARABLE.any { i.hasServiceUuid(it) }
            ) {
                emit(DeviceClass.WEARABLE, 22, "Fitness GATT service advertised")
            }
        },
        rule("ble.uuid_lighting") { i, emit ->
            // Hue/Govee/etc. expose vendor service UUIDs; reinforces a bulb call.
            if (i.transport == Transport.BLE &&
                (i.hasServiceUuid("fe0f") || i.hasServiceUuid("ffe5") || i.hasServiceUuid("1802"))
            ) {
                emit(DeviceClass.SMART_BULB, 15, "BLE lighting service detected")
            }
        },

        // ---- BLE manufacturer-data company IDs (now captured) ----------------
        rule("ble.mfg_mobile") { i, emit ->
            if (i.transport != Transport.BLE) return@rule
            if (i.manufacturerIds.any { it in VendorTables.MFG_MOBILE })
                emit(DeviceClass.SMARTPHONE, 12, "Mobile-ecosystem manufacturer advertisement")
            if (i.manufacturerIds.contains(VendorTables.MFG_MICROSOFT))
                emit(DeviceClass.LAPTOP_DESKTOP, 12, "Microsoft Swift Pair advertisement")
        },
    )
}
