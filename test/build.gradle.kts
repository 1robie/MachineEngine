plugins {
    id("machineengine.platform")
    id("machineengine.publishing")
}

description = "Headless test harness for MachineEngine and its addons (ARCHITECTURE.md §28)."

base {
    archivesName = "machineengine-test"
}

dependencies {
    // The assembled plugin (D24): tests see the same wiring as a server, nms modules included.
    api(project(":plugin"))
    // The harness drives the engine directly (TestEngine, FakeWorld), and the plugin keeps it internal.
    api(project(":common"))
}
