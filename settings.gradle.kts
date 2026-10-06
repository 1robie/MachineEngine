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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
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
    }
}

include(
    "machineengine-api",
    "machineengine-core",
    "machineengine-test",
    "machineengine-paper",
    "machineengine-craftengine",
    "machineengine-zmenu",
    "machineengine-plugin",
    "machineengine-mekanized",
)
