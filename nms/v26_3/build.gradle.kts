plugins {
    id("machineengine.java-conventions")
    alias(libs.plugins.paperweight.userdev)
}

description = "nms:common for Minecraft 26.3, compiled against its Paper dev bundle (server classes, Mojang-mapped)."

group = "fr.robie.machineengine.nms"

// Project repositories replace the settings ones here (settings.gradle.kts); paperweight adds its own.
repositories {
    mavenCentral()
}

base {
    archivesName = "machineengine-nms-v26_3"
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.asProvider().get())
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
    // Java 25 (D25): only ever loaded on 26.3 servers, which run on Java 25.
    options.release = 25
}
