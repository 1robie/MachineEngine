rootProject.name = "machineengine"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Modules use these repositories, except the nms:v* ones: paperweight userdev adds its own
    // (dev bundle, mappings, remapper, decompiler), which replace these there.
    repositoriesMode = RepositoriesMode.PREFER_PROJECT
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.momirealms.net/snapshots/") {
            mavenContent { snapshotsOnly() }
        }
        maven("https://repo.groupez.dev/releases") {
            mavenContent { includeGroup("fr.maxlego08.menu") }
        }
        maven("https://repo.momirealms.net/releases/") {
            mavenContent { releasesOnly() }
        }
        // Paper-Dispatch (D35).
        maven("https://jitpack.io") {
            mavenContent { includeGroup("com.github.1robie.paper-dispatch") }
        }
        // FoliaLib (D35).
        maven("https://repo.tcoded.com/releases") {
            mavenContent { includeGroup("com.tcoded") }
        }
    }
}

// Modules of the restructure (ARCHITECTURE.md D24) have short paths; their jars keep the
// machineengine- prefix. The others move in RESTRUCTURE_PLAN.md R3-R7.
include(
    "api",
    "common",
    "test",
    // One module per optional third-party plugin (D24).
    "hooks:zmenu",
    // Version differences (D25): interfaces, then one userdev module per Minecraft version.
    "nms:common",
    "nms:v1_20_6",
    "nms:v26_3",
    "plugin",
)
