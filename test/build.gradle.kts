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

// Dev check of the world fluids pack on every version's vanilla shaders (FluidPackTest):
// -Pmachineengine.vanilla=<folder of extracted client files> [-Pmachineengine.vanillaOut=<folder>]
tasks.test {
    for (name in listOf("machineengine.vanilla", "machineengine.vanillaOut")) {
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}
