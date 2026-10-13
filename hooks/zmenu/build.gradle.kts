plugins {
    id("machineengine.platform")
    id("machineengine.publishing")
}

description = "MachineEngine machine menus on zMenu: placeholders, custom buttons, configurable menu files."

base {
    archivesName = "machineengine-zmenu"
}

dependencies {
    api(project(":common"))
    compileOnly(libs.zmenu.api)
    // Logging; the classes come with :common (bundled, relocated in the plugin jar).
    compileOnly(libs.messageflow)
}
