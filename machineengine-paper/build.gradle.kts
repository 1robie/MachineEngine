plugins {
    id("machineengine.platform")
}

description = "MachineEngine Paper integration (lifecycle, commands, scheduling, vanilla interop)."

dependencies {
    api(project(":machineengine-core"))
}
