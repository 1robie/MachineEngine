plugins {
    id("machineengine.platform")
}

description = "MachineEngine machine menus on zMenu: placeholders, custom buttons, configurable menu files."

base {
    archivesName = "machineengine-zmenu"
}

dependencies {
    api(project(":common"))
    compileOnly(libs.zmenu.api)
}
