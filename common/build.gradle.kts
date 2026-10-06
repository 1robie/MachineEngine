plugins {
    id("machineengine.platform")
}

description = "MachineEngine implementation of the API: the engine, its Paper integration and its CraftEngine integration (D24)."

base {
    archivesName = "machineengine-common"
}

dependencies {
    api(project(":api"))
}
