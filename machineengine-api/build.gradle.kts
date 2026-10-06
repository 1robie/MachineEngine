plugins {
    id("machineengine.platform")
}

description = "MachineEngine public API and SPI."

tasks.processResources {
    val apiVersion = project.version.toString()
    inputs.property("version", apiVersion)
    filesMatching("**/version.properties") {
        expand("version" to apiVersion)
    }
}
