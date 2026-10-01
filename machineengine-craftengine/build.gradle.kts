plugins {
    id("machineengine.java-conventions")
}

description = "MachineEngine CraftEngine adapter, the only module that uses CraftEngine classes (ARCHITECTURE.md §22)."

val craftEngineVersion = providers.gradleProperty("craftengine_version").get()

dependencies {
    api(project(":machineengine-paper"))
    compileOnly(libs.paper.api)
    // CraftEngine's published POMs pull in its whole runtime; take only the classes we compile against.
    compileOnly("net.momirealms:craft-engine-core:$craftEngineVersion") { isTransitive = false }
    compileOnly("net.momirealms:craft-engine-bukkit:$craftEngineVersion") { isTransitive = false }
    testImplementation("net.momirealms:craft-engine-core:$craftEngineVersion") { isTransitive = false }
}
