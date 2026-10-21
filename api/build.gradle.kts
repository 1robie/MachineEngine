import me.champeau.gradle.japicmp.JapicmpTask

plugins {
    id("machineengine.platform")
    id("machineengine.publishing")
    alias(libs.plugins.japicmp)
}

description = "MachineEngine public API and SPI."

base {
    archivesName = "machineengine-api"
}

tasks.processResources {
    val apiVersion = project.version.toString()
    inputs.property("version", apiVersion)
    filesMatching("**/version.properties") {
        expand("version" to apiVersion)
    }
}

// API compatibility (ARCHITECTURE §5): this jar against the last released API. Raise apiBaseline after
// each release. Pre-1.0, a minor version may break (documented in CHANGELOG.md); a patch may not.
val apiBaseline = "0.2.0"

val japicmpBaseline = configurations.create("japicmpBaseline") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    japicmpBaseline("com.github.1robie.MachineEngine:machineengine-api:$apiBaseline")
}

/** Whether [current] may break the API of [baseline]: a new major, or a new minor before 1.0. */
fun breaksAllowed(baseline: String, current: String): Boolean {
    fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val (oldMajor, oldMinor) = parts(baseline)
    val (newMajor, newMinor) = parts(current)
    return newMajor > oldMajor || (newMajor == 0 && newMinor > oldMinor)
}

tasks.register<JapicmpTask>("japicmp") {
    description = "Checks binary compatibility with the released API $apiBaseline."
    group = "verification"
    oldClasspath.from(japicmpBaseline)
    newClasspath.from(tasks.jar)
    // The engine's own hooks (setters the engine installs) carry no guarantee (§5 tiers).
    annotationExcludes.add("@org.jetbrains.annotations.ApiStatus.Internal")
    onlyBinaryIncompatibleModified = true
    // Types from Paper, Adventure... are not on the classpath; only the API's own classes are compared.
    ignoreMissingClasses = true
    failOnModification = !breaksAllowed(apiBaseline, project.version.toString())
    txtOutputFile = layout.buildDirectory.file("reports/japicmp/report.txt")
    htmlOutputFile = layout.buildDirectory.file("reports/japicmp/report.html")
}
