// Publishes an engine module as a Maven artifact, to https://1robie.github.io/maven/ on a version tag
// (.github/workflows/maven.yml): addons depend on machineengine-api, and their headless tests on
// machineengine-test, which brings the rest.
plugins {
    `java-library`
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // The group JitPack gave the first releases, kept so addons' coordinates stay the same.
            groupId = "com.github.1robie.MachineEngine"
            from(components["java"])
            pom {
                description = provider { project.description }
                url = "https://github.com/1robie/MachineEngine"
                licenses {
                    license {
                        name = "GPL-3.0"
                        url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                    }
                }
                scm {
                    url = "https://github.com/1robie/MachineEngine"
                }
            }
        }
    }
}

// The artifact is named like the jar (machineengine-api, machineengine-nms-v26_3 ...), not like the
// project (api, v26_3); archivesName is set in each module's build file, after this plugin runs.
afterEvaluate {
    publishing.publications.named<MavenPublication>("maven") {
        artifactId = the<BasePluginExtension>().archivesName.get()
        pom.name = artifactId
    }
}
