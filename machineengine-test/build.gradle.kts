plugins {
    id("machineengine.platform")
}

description = "Headless test harness for MachineEngine and its addons (ARCHITECTURE.md §28)."

dependencies {
    api(project(":machineengine-core"))
}
