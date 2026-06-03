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
        // SQLCipher for Android — Zetetic's official Maven server hosts 4.5.5+
        // (4.5.4 and earlier are on Maven Central but lack SQLCipherUtils).
        maven { url = uri("https://mvn.zetetic.net/artifactory/public") }
    }
}

rootProject.name = "ARAWN"
include(":app")
include(":core")
include(":recon")
