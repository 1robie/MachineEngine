plugins {
    id("machineengine.java-conventions")
}

description = "MachineEngine Paper integration (lifecycle, commands, scheduling, vanilla interop)."

dependencies {
    api(project(":machineengine-core"))
    compileOnly(libs.paper.api)
}
