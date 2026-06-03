// :recon — field-validated recon module (Phase A / A5).
// Owns the scanner service, sensor models, classification engine, exporters,
// report generator, and recon UI panels. Depends on :core for DB + OUI.
// No Room annotation processing here — entities/DAOs live in :core.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.arawn.recon"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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
    }
}

dependencies {
    // Shared infrastructure: DB, OUI engine, Room api-exposed types.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)

    // osmdroid — Apache-2.0 OpenStreetMap view. No API key required.
    implementation(libs.osmdroid.android)

    testImplementation("junit:junit:4.13.2")
}
