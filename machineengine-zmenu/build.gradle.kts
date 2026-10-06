plugins {
    id("machineengine.platform")
}

description = "MachineEngine machine menus on zMenu: placeholders, custom buttons, configurable menu files."

dependencies {
    api(project(":machineengine-paper"))
    compileOnly(libs.zmenu.api)
}
