import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import net.minecrell.pluginyml.paper.PaperPluginDescription
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    id("machineengine.platform")
    alias(libs.plugins.shadow)
    alias(libs.plugins.plugin.yml.paper)
    alias(libs.plugins.run.paper)
}

description = "The MachineEngine Paper plugin jar (api + common + zmenu)."

/** Addon jars installed on dev servers next to MachineEngine (Mekanized). */
val devAddons: Configuration by configurations.creating {
    isTransitive = false
}

dependencies {
    implementation(project(":common"))
    implementation(project(":hooks:zmenu"))
    compileOnly(libs.zmenu.api)
    devAddons(project(":machineengine-mekanized"))
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
        register("machineengine.admin") {
            description = "MachineEngine admin commands"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
    }
}

tasks.shadowJar {
    archiveClassifier = ""
    archiveFileName = "MachineEngine-${project.version}.jar"
    // Provided by Paper at runtime.
    dependencies {
        exclude(dependency("net.kyori:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
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
    tasks.register<RunServer>("run$software-$minecraftVersion") {
        group = "run dev server"
        description = "Runs a $software $minecraftVersion dev server with MachineEngine and CraftEngine."
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
        if (withMachineEngine && providers.gradleProperty("withAddons").orElse("true").get() == "true") pluginJars.from(devAddons)
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
}

registerDevServer("1.20.6", javaVersion = 21)
registerDevServer("26.3", javaVersion = 25)
registerDevServer("1.20.6", javaVersion = 21, folia = true)
registerDevServer("26.2", javaVersion = 25, folia = true)

// The stock runServer task has no version configured; point people at the explicit ones.
tasks.named<RunServer>("runServer") {
    enabled = false
}
