plugins {
    id("machineengine.pure-java")
}

description = "MachineEngine public API and SPI (server-free)."

dependencies {
    // D6: identity uses Adventure Key. Paper provides it at runtime.
    api(libs.adventure.key)
}

tasks.processResources {
    val apiVersion = project.version.toString()
    inputs.property("version", apiVersion)
    filesMatching("**/version.properties") {
        expand("version" to apiVersion)
    }
}
