// Root project build script. Plugin versions are declared in the version
// catalog (gradle/libs.versions.toml) and referenced here via aliases.
// All plugins are free / open-source.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.secrets.gradle) apply false
}
