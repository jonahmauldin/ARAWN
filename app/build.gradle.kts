// ARAWN — app module build script (Phase 1).
// Adjust namespace / SDK pins to your installed tooling as needed.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // Kotlin 2.0+ Compose compiler
    id("com.google.devtools.ksp")             // Room annotation processing
    // Free / Apache-2.0. Used here to keep the (future, free) WiGLE API token
    // out of the public source — NOT for any paid Google Maps key.
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

// --- Phase A / A1: stable signing -------------------------------------------
// Credentials come from environment variables (CI secrets) or, for local builds,
// Gradle properties (e.g. ~/.gradle/gradle.properties). When NONE are present
// (fresh clone, fork, or a CI run before the secrets exist) the build falls back
// to the auto-generated per-build debug keystore, so it still compiles + runs —
// it simply won't be installable in-place over a stable-signed build.
val stableStorePath: String? =
    System.getenv("ARAWN_KEYSTORE_PATH") ?: (project.findProperty("ARAWN_KEYSTORE_PATH") as String?)
val stableStorePassword: String? =
    System.getenv("ARAWN_KEYSTORE_PASSWORD") ?: (project.findProperty("ARAWN_KEYSTORE_PASSWORD") as String?)
val stableKeyAlias: String? =
    System.getenv("ARAWN_KEY_ALIAS") ?: (project.findProperty("ARAWN_KEY_ALIAS") as String?)
val stableKeyPassword: String? =
    System.getenv("ARAWN_KEY_PASSWORD") ?: (project.findProperty("ARAWN_KEY_PASSWORD") as String?)
val hasStableSigning: Boolean =
    stableStorePath != null && file(stableStorePath).exists() &&
        stableStorePassword != null && stableKeyAlias != null && stableKeyPassword != null

android {
    namespace = "com.arawn.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arawn.scanner"
        minSdk = 30          // Android 11 — required by the connectedDevice FGS type
        targetSdk = 35       // Android 15
        versionCode = 8
        versionName = "0.5.0-phaseA-a1"
    }

    signingConfigs {
        // Only declared when real credentials are available; otherwise the build
        // types below keep their default signing (debug keystore).
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(stableStorePath!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // A1: CI builds the debug variant. Sign it with the stable key (when
            // present) so each new build installs in-place — no signature-mismatch
            // uninstall, no Room DB wipe. Runtime behavior is otherwise unchanged.
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            if (hasStableSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true // required for the secrets plugin to inject keys
    }
}

/**
 * Sanitization layer for the public repo.
 *  - `secrets.properties`         → real values, git-ignored, never committed.
 *  - `local.defaults.properties`  → safe empty placeholders, committed, so the
 *                                   build still succeeds on a fresh clone / CI.
 * Keys are surfaced at compile time as fields on BuildConfig (e.g.
 * BuildConfig.WIGLE_API_TOKEN). No secret ever touches a source file.
 */
secrets {
    propertiesFileName = "secrets.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    // lifecycleScope for Activity-scoped coroutines (CSV export). Apache-2.0.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Room — local SQLite persistence (all open-source, no cloud).
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion") // suspend + Flow support
    ksp("androidx.room:room-compiler:$roomVersion")

    // osmdroid — Apache-2.0 OpenStreetMap view. No API key, no cloud account.
    // Used strictly offline here (setUseDataConnection(false)); the map reads
    // only from local tile archives on disk, so no INTERNET permission is added.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
