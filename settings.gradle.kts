// If your Android Studio project already has this file, MERGE the
// pluginManagement repositories below rather than overwriting it.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // net.zetetic:sqlcipher-android (4.5.5+) is on Maven Central — no extra repo needed.
    }
}

rootProject.name = "ARAWN"
include(":app")
include(":core")
include(":recon")
