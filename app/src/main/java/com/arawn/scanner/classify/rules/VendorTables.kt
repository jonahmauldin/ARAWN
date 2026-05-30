package com.arawn.scanner.classify.rules

/**
 * Vendor + keyword lookup tables for the rule library.
 *
 * Keys are **pre-normalized** to lowercase alphanumerics (no spaces/hyphens) so
 * they match the terse Wireshark `manuf` short-names ARAWN stores — e.g. the
 * stored vendor "Tp-LinkT" normalizes to "tplinkt", which contains "tplink".
 *
 * This file is pure data: extending coverage means editing a set here, never
 * touching the engine or a rule's logic. That keeps the "no giant if/else
 * sprawl" guarantee — heuristics live in rules, vocabulary lives here.
 */
internal object VendorTables {

    /** Routers / gateways / consumer + enterprise AP brands. */
    val NETWORKING = setOf(
        "tplink", "netgear", "ubiquiti", "cisco", "aruba", "ruckus", "mikrotik",
        "asus", "dlink", "zyxel", "arris", "technicolor", "sagemcom", "actiontec",
        "linksys", "commscope", "calix", "fortinet", "juniper", "huawei",
        "zte", "tenda", "belkin",
    )

    /** Mesh-system makers (a mesh node is also an AP; this just tips the lean). */
    val MESH = setOf("eero", "plume", "amplifi")

    /** Phone / mobile-handset makers. */
    val PHONE = setOf(
        "apple", "samsung", "xiaomi", "oneplus", "oppo", "vivo", "motorola",
        "google", "huawei", "nokia", "realme",
    )

    /** PC / laptop / Wi-Fi-NIC makers. */
    val COMPUTER = setOf(
        "intel", "dell", "lenovo", "hewlettpackard", "hp", "microsoft", "liteon",
        "azurewave", "framework", "razer", "msi", "gigabyte",
    )

    /** Smart-TV / display makers. */
    val TV = setOf("lg", "sony", "vizio", "tcl", "hisense", "panasonic", "sharp", "philips")

    /** Streaming sticks / boxes. */
    val STREAMING = setOf("roku", "amazon", "google")

    /** Smart-speaker / voice-assistant makers. */
    val SPEAKER = setOf("sonos", "bose", "amazon", "google", "harman", "jbl", "denon")

    /** Lighting ecosystems. */
    val LIGHTING = setOf("govee", "signify", "philipshue", "lifx", "nanoleaf", "yeelight", "wiz")

    /** Generic IoT / maker-board silicon (weak, generic IoT lean). */
    val IOT_GENERIC = setOf("espressif", "tuya", "shelly", "sonoff", "itead", "raspberry", "particle")

    /** IP / smart-camera makers. */
    val CAMERA = setOf("hikvision", "dahua", "axis", "wyze", "reolink", "amcrest", "lorex", "vivotek")

    /** Printer makers. */
    val PRINTER = setOf("canon", "epson", "brother", "lexmark", "xerox", "kyocera", "ricoh")

    /** Wearable / fitness-tracker makers. */
    val WEARABLE = setOf("fitbit", "garmin", "polar", "whoop", "huami", "amazfit", "suunto", "withings")

    /** Automotive / in-vehicle system makers. */
    val VEHICLE = setOf(
        "tesla", "ford", "harman", "continental", "bosch", "bmw", "toyota",
        "honda", "hyundai", "kia", "volkswagen", "denso", "aptiv",
    )

    // ---- Name-keyword vocab (BLE device names ARE captured today) ----------

    val NAME_TV = setOf("tv", "bravia", "roku tv", "[lg]", "[tv]")
    val NAME_STREAMING = setOf("chromecast", "firetv", "fire tv", "roku", "shield")
    val NAME_SPEAKER = setOf("echo", "sonos", "homepod", "speaker", "soundbar", "bose")
    val NAME_BULB = setOf("bulb", "light", "lamp", "strip", "govee", "hue", "lifx", "lumin")
    val NAME_CAMERA = setOf("cam", "camera", "doorbell")
    val NAME_PRINTER = setOf("printer", "officejet", "deskjet", "laserjet", "envy")
    val NAME_WEARABLE = setOf("watch", "band", "fit", "tracker", "buds", "ring")
    val NAME_VEHICLE = setOf("car", "vehicle", "tesla", "carplay", "obd", "myhonda", "uconnect")
    val NAME_BEACON = setOf("beacon", "ibeacon", "eddystone", "tile", "airtag")

    // ---- BLE 16-bit GATT service-UUID fragments (dormant until UUID capture) -

    /** Eddystone beacon service. */
    const val UUID_EDDYSTONE = "feaa"

    /** Standard SIG services that signal a class. */
    val UUID_WEARABLE = setOf("180d" /* heart rate */, "1816" /* cycling */, "1814" /* run speed */)
    val UUID_HID = "1812" // human-interface device (keyboards, remotes, some wearables)

    // ---- BLE manufacturer-data company IDs (now captured) ------------------

    /** Mobile-handset ecosystems: Apple, Samsung, Google. */
    val MFG_MOBILE = setOf(0x004C, 0x0075, 0x00E0)

    /** Microsoft Swift Pair — typically a PC/laptop or its accessory. */
    const val MFG_MICROSOFT = 0x0006
}
