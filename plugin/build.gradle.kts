import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import net.minecrell.pluginyml.paper.PaperPluginDescription
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("machineengine.platform")
    id("machineengine.publishing")
    alias(libs.plugins.shadow)
    alias(libs.plugins.plugin.yml.paper)
    alias(libs.plugins.run.paper)
    alias(libs.plugins.hangar.publish)
}

description = "The MachineEngine Paper plugin jar (api + common + hooks + nms)."

base {
    archivesName = "machineengine-plugin"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":hooks:zmenu"))
    // One module per Minecraft version; NmsLoader loads the running one only.
    implementation(project(":nms:v1_20_6"))
    implementation(project(":nms:v26_3"))
    compileOnly(libs.zmenu.api)
    // Registers the commands; the classes come with :common (bundled, relocated: D35).
    compileOnly(libs.paper.dispatch)
}

paper {
    name = "MachineEngine"
    main = "fr.robie.machineengine.plugin.MachineEnginePlugin"
    version = project.version.toString()
    description = "Server-side machine and resource infrastructure framework."
    apiVersion = "1.20"
    // Static registries must be frozen before worlds load (§8.1).
    load = BukkitPluginDescription.PluginLoadOrder.STARTUP
    // Validated on Folia in M4 (D8).
    foliaSupported = true
    serverDependencies {
        // D7: CraftEngine is required; Paper refuses to load MachineEngine without it.
        register("CraftEngine") {
            required = true
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            joinClasspath = true
        }
        // Machine menus (optional).
        register("zMenu") {
            required = false
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            joinClasspath = true
        }
    }
    permissions {
        register("machineengine.use") {
            description = "Open machine menus"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("machineengine.settings") {
            description = "See and change your own settings (/machinesettings)"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("machineengine.admin") {
            description = "MachineEngine admin commands"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "MachineEngine-${project.version}.jar"
    // The nms modules are Mojang-mapped, like the servers since 1.20.5: Paper must not remap the jar.
    manifest {
        attributes["paperweight-mappings-namespace"] = "mojang"
    }
    // Provided by Paper at runtime.
    dependencies {
        exclude(dependency("net.kyori:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
    // Bundled libraries are relocated (D35): another plugin may bundle other versions of them.
    relocate("fr.robie.paperdispatch", "fr.robie.machineengine.libs.paperdispatch")
    relocate("com.tcoded.folialib", "fr.robie.machineengine.libs.folialib")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// The published artifact is the plain jar with its dependencies (what machineengine-test needs), not
// the shaded plugin jar: servers get that from the releases.
(components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) {
    skip()
}

// Hangar (https://hangar.papermc.io/1robie/MachineEngine): the release workflow runs
// publishPluginPublicationToHangar on a tag, with -Pversion=<tag> and the HANGAR_API_TOKEN secret.
hangarPublish {
    publications.register("plugin") {
        version = project.version.toString()
        id = "MachineEngine"
        // Tags with a hyphen (0.2.0-beta.1) go to the Snapshot channel, which must exist on Hangar.
        channel = if (project.version.toString().contains('-')) "Snapshot" else "Release"
        changelog = "https://github.com/1robie/MachineEngine/releases/tag/${project.version}"
        apiKey = providers.environmentVariable("HANGAR_API_TOKEN")
        platforms {
            register(io.papermc.hangarpublishplugin.model.Platforms.PAPER) {
                jar = tasks.shadowJar.flatMap { it.archiveFile }
                platformVersions = providers.gradleProperty("hangar_paper_versions").get().split(",").map { it.trim() }
                dependencies {
                    url("CraftEngine", "https://modrinth.com/plugin/craftengine") {
                        required = true
                    }
                    hangar("zMenu") {
                        required = false
                    }
                }
            }
        }
    }
}

val verifyRelocation = tasks.register<VerifyRelocation>("verifyRelocation") {
    description = "Checks that the plugin jar holds no unrelocated bundled library (D35)."
    group = "verification"
    jar = tasks.shadowJar.flatMap { it.archiveFile }
    packages = listOf("fr/robie/paperdispatch/", "com/tcoded/folialib/")
    report = layout.buildDirectory.file("reports/relocation.txt")
}

tasks.check {
    dependsOn(verifyRelocation)
}

// ---------------------------------------------------------------------------------------------
// Dev servers (D19: 1.20.6 and 26.3). CraftEngine is either built from craftengine_dev_ref
// (default, matches the snapshot we compile against) or downloaded from Modrinth.
// ---------------------------------------------------------------------------------------------

val craftEngineRuntime = providers.gradleProperty("craftengine_runtime").get()
val craftEngineDevDir = rootProject.layout.projectDirectory.dir(".craftengine")

val buildCraftEngineDev = tasks.register<BuildCraftEngineDev>("buildCraftEngineDev") {
    group = "run dev server"
    description = "Builds the CraftEngine paper plugin from craftengine_dev_ref (use -PrefreshCraftEngine to rebuild)."
    repositoryUrl = "https://github.com/Xiao-MoMi/craft-engine.git"
    ref = providers.gradleProperty("craftengine_dev_ref")
    refresh = providers.gradleProperty("refreshCraftEngine").isPresent
    buildHeap = providers.gradleProperty("craftengine_build_heap").orElse("4G")
    sourceDirectory = craftEngineDevDir.dir("source")
    outputJar = craftEngineDevDir.file("craftengine-dev.jar")
}

fun registerDevServer(minecraftVersion: String, javaVersion: Int, folia: Boolean = false) {
    val software = if (folia) "Folia" else "Paper"
    // The blocks /machineengine check places (src/dev): a definitions file and a CraftEngine pack,
    // copied into the server before each start. Never part of the plugin jar.
    val installDevChecks = tasks.register<Copy>("installDevChecks$software-$minecraftVersion") {
        description = "Copies the /machineengine check blocks into the $software $minecraftVersion dev server."
        into(rootProject.layout.projectDirectory.dir("run/${software.lowercase()}-$minecraftVersion/plugins"))
        from(layout.projectDirectory.dir("src/dev/definitions")) { into("MachineEngine/definitions") }
        from(layout.projectDirectory.dir("src/dev/craftengine")) { into("CraftEngine/resources") }
    }
    tasks.register<RunServer>("run$software-$minecraftVersion") {
        group = "run dev server"
        description = "Runs a $software $minecraftVersion dev server with MachineEngine and CraftEngine."
        devServer(minecraftVersion, javaVersion, folia)
        dependsOn(installDevChecks)
    }
}

/** What every dev server has: its folder in run/, the plugins and the Java version. */
fun RunServer.devServer(minecraftVersion: String, javaVersion: Int, folia: Boolean) {
    val software = if (folia) "Folia" else "Paper"
    minecraftVersion(minecraftVersion)
    runDirectory = rootProject.layout.projectDirectory.dir("run/${software.lowercase()}-$minecraftVersion")
    if (folia) {
        downloadsApiService.convention(xyz.jpenilla.runtask.service.DownloadsAPIService.folia(project))
        pluginDownloadService.convention(xyz.jpenilla.runtask.pluginsapi.PluginDownloadService.paper(project))
    }
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
    // -PwithMachineEngine=false runs CraftEngine alone (checks that CE keeps machine data, §21).
    val withMachineEngine = providers.gradleProperty("withMachineEngine").orElse("true").get() == "true"
    if (withMachineEngine) pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    when (craftEngineRuntime) {
        "dev" -> pluginJars.from(buildCraftEngineDev.flatMap { it.outputJar })
        "modrinth" -> downloadPlugins {
            modrinth("craftengine", providers.gradleProperty("craftengine_modrinth_version").get())
        }
        "none" -> {} // checks that MachineEngine refuses to load without CraftEngine
        else -> error("Unknown craftengine_runtime '$craftEngineRuntime' (expected dev, modrinth or none)")
    }
    if (providers.gradleProperty("withZMenu").orElse("true").get() == "true") {
        downloadPlugins { modrinth("zmenu", libs.versions.zmenu.get()) }
    }
    jvmArgs("-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
}

registerDevServer("1.20.6", javaVersion = 21)
registerDevServer("26.3", javaVersion = 25)
registerDevServer("1.20.6", javaVersion = 21, folia = true)
registerDevServer("26.2", javaVersion = 25, folia = true)

// The stock runServer task has no version configured; point people at the explicit ones.
tasks.named<RunServer>("runServer") {
    enabled = false
}
