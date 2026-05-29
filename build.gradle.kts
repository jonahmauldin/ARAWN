// Root project build script. If your Android Studio project already has one,
// MERGE these plugin declarations in (keep your existing versions if they're
// newer and compatible). All plugins here are free / open-source.
//
// Version alignment note: the KSP version's prefix MUST match the Kotlin
// version (here 2.0.21). If you bump Kotlin, bump KSP's prefix to match.

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" apply false
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
}
