plugins {
    id("machineengine.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "nms:common for Minecraft 1.20.6, compiled against its Paper dev bundle (server classes, Mojang-mapped)."

group = "fr.robie.machineengine.nms"

// Project repositories replace the settings ones here (settings.gradle.kts); paperweight adds its own.
repositories {
    mavenCentral()
}

base {
    archivesName = "machineengine-nms-v1_20_6"
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.baseline.get())
    // nms:common carries the newest Paper API; this module must see only its own version's. CraftEngine
    // is not needed here (and its repository is not declared here).
    implementation(project(":nms:common")) {
        exclude(group = "io.papermc.paper")
        exclude(group = "net.momirealms")
    }
}

// Paper servers run Mojang-mapped since 1.20.5: ship the jar as compiled, no reobfuscation.
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.withType<JavaCompile>().configureEach {
    // Java 21: the server runs on it.
    options.release = 21
}
