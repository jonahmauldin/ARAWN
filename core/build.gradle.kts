// :core — shared infrastructure module (Phase A / A3).
// First resident: the offline OUI engine, relocated from :app with no logic
// change. Future slices will add the database, models, classify, map, location,
// and common helpers here.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
}
