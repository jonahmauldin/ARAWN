// ARAWN — app module build script (Phase 1).
// Adjust namespace / SDK pins to your installed tooling as needed.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)  // Kotlin 2.0+ Compose compiler
    // Free / Apache-2.0. Used here to keep the (future, free) WiGLE API token
    // out of the public source — NOT for any paid Google Maps key.
    alias(libs.plugins.secrets.gradle)
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
        versionCode = 25
        versionName = "1.1.0"
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
    // :recon owns the scanner, models, classify, exporters, report, and recon UI.
    // :core owns Room DB, OUI engine; its api() deps (Room runtime/ktx) are
    // visible here transitively so no explicit Room dep is needed in :app.
    implementation(project(":recon"))
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // lifecycleScope for Activity-scoped coroutines (CSV export, HTML report).
    implementation(libs.androidx.lifecycle.runtime.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // osmdroid — OPS Center multi-track map (same dep as :recon, declared here so :app
    // can reference Polyline / Marker / BoundingBox directly in OpsMapPanel.kt).
    implementation(libs.osmdroid.android)

    // Phase E: Vault / Biometric
    // AppCompat required by BiometricPrompt (needs FragmentActivity).
    implementation(libs.androidx.appcompat)
    // BiometricPrompt — fingerprint / device-credential gate for the Vault screen.
    implementation(libs.androidx.biometric)
}
