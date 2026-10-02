import net.minecrell.pluginyml.paper.PaperPluginDescription

plugins {
    id("machineengine.java-conventions")
    alias(libs.plugins.plugin.yml.paper)
}

description = "Example addon: a spark generator and a spark consumer, built only on the MachineEngine API."

val craftEngineVersion = providers.gradleProperty("craftengine_version").get()

dependencies {
    // An addon depends on the API only (plus CraftEngine for its demo placement command).
    compileOnly(project(":machineengine-api"))
    compileOnly(libs.paper.api)
    compileOnly("net.momirealms:craft-engine-core:$craftEngineVersion") { isTransitive = false }
    compileOnly("net.momirealms:craft-engine-bukkit:$craftEngineVersion") { isTransitive = false }
}

paper {
    name = "MachineEngineExample"
    main = "fr.robie.machineengine.example.ExamplePlugin"
    version = project.version.toString()
    description = "Example MachineEngine addon."
    apiVersion = "1.20"
    serverDependencies {
        register("MachineEngine") {
            required = true
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            joinClasspath = true
        }
        register("CraftEngine") {
            required = true
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            joinClasspath = true
        }
    }
}

tasks.jar {
    archiveFileName = "MachineEngineExample-${project.version}.jar"
}
