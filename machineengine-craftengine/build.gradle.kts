plugins {
    id("machineengine.platform")
}

description = "MachineEngine CraftEngine integration (ARCHITECTURE.md §22)."

val craftEngineVersion = providers.gradleProperty("craftengine_version").get()

dependencies {
    api(project(":machineengine-paper"))
    compileOnly("net.momirealms:craft-engine-bukkit-proxy:$craftEngineVersion") { isTransitive = false }
    // CraftEngine relocates its NBT library to net.momirealms.craftengine.libraries.nbt inside craft-engine-core.
    testImplementation("net.momirealms:craft-engine-core:$craftEngineVersion") { isTransitive = false }
}
