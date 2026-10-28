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
    }
}

rootProject.name = "WristShot"

// :core is plain Kotlin (no Android). It holds config, data formats and, later,
// the shot detector, so it can be unit-tested on your computer without a watch.
include(":core")
// :app is the Wear OS application (UI, foreground service, sensors, files).
include(":app")
