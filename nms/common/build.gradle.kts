plugins {
    id("machineengine.platform")
    id("machineengine.publishing")
}

description = "The reflective fallback for Minecraft versions without an nms:v* module (the interfaces are in api, D28)."

// :common and :nms:common share the project name "common"; a group of their own keeps their Gradle
// identities (group:name) apart.
group = "fr.robie.machineengine.nms"

base {
    archivesName = "machineengine-nms-common"
}

dependencies {
    api(project(":api"))
    // World fluids (ReflectiveWorldFluids): a Netty handler; the server provides Netty at runtime.
    compileOnly("io.netty:netty-transport:4.1.97.Final")
}
