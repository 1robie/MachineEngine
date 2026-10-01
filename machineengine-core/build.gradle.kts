plugins {
    id("machineengine.pure-java")
}

description = "MachineEngine implementation of the API (server-free)."

dependencies {
    api(project(":machineengine-api"))
}
