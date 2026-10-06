import net.minecrell.pluginyml.paper.PaperPluginDescription

plugins {
    id("machineengine.platform")
    alias(libs.plugins.plugin.yml.paper)
}

description = "Example addon: a spark generator and a spark consumer, built only on the MachineEngine API."

dependencies {
    // An addon depends on the API only (plus CraftEngine for its demo placement command).
    compileOnly(project(":machineengine-api"))
    // Item conversion (ships inside the MachineEngine plugin).
    compileOnly(project(":machineengine-paper"))
}

paper {
    name = "MachineEngineExample"
    main = "fr.robie.machineengine.example.ExamplePlugin"
    version = project.version.toString()
    description = "Example MachineEngine addon."
    apiVersion = "1.20"
    foliaSupported = true
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
