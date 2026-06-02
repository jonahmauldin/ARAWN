// :core — shared infrastructure module (Phase A / A3).
// First resident: the offline OUI engine, relocated from :app with no logic
// change. Future slices will add the database, models, classify, map, location,
// and common helpers here.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp) // Room annotation processing (the DB layer lives here now)
}

android {
    namespace = "com.arawn.core"
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
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)

    // Room — local SQLite persistence (all open-source, no cloud). Exposed via
    // `api` so consumer modules (:app, future :recon) see the entities/DAO/Flow
    // types without re-declaring Room themselves.
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
