plugins {
    id("machineengine.platform")
}

description = "What differs between Minecraft versions, as interfaces; nms:v* implement them (D24, D25)."

// :common and :nms:common share the project name "common"; a group of their own keeps their Gradle
// identities (group:name) apart.
group = "fr.robie.machineengine.nms"

base {
    archivesName = "machineengine-nms-common"
}
