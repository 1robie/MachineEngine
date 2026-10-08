plugins {
    id("machineengine.platform")
}

description = "MachineEngine implementation of the API: the engine, its Paper integration and its CraftEngine integration (D24)."

base {
    archivesName = "machineengine-common"
}

val craftEngineVersion = providers.gradleProperty("craftengine_version").get()

dependencies {
    api(project(":api"))
    // Calls version-specific code only through these interfaces (D24).
    api(project(":nms:common"))
    // Commands and scheduling (D35); the plugin jar relocates both.
    bundledLibraries(libs.paper.dispatch)
    bundledLibraries(libs.folialib)
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:$craftEngineVersion") { isTransitive = false }
    // CraftEngine relocates its NBT library to net.momirealms.craftengine.libraries.nbt inside craft-engine-core.
    testImplementation("net.momirealms:craft-engine-core:$craftEngineVersion") { isTransitive = false }
}
