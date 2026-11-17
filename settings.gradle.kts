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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor, the YouTube stream resolver: published from source by JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "Valen"
include(":core")
include(":ui")
include(":libs:api")
include(":libs:web")
include(":libs:engine")
include(":libs:spotify")
include(":libs:ytmusic")
include(":libs:audio")
include(":libs:update")
include(":providers:spotify")
include(":providers:ytmusic")
include(":providers:soundcloud")
include(":providers:native")
include(":providers:lyrics")
include(":host:desktop")
include(":host:android")
include(":app-desktop")
include(":app-android")
