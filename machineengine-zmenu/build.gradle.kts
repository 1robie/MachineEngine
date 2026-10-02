plugins {
    id("machineengine.java-conventions")
}

description = "MachineEngine machine menus on zMenu: placeholders, custom buttons, configurable menu files."

dependencies {
    api(project(":machineengine-paper"))
    compileOnly(libs.paper.api)
    compileOnly(libs.zmenu.api)
}
