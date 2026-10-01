// Applied to modules that must stay server-free (§5): api, core, test.
// Fails `check` if Paper, Bukkit or CraftEngine end up on the module's classpaths.
plugins {
    id("machineengine.java-conventions")
}

val verifyPureJava = tasks.register<VerifyPureJava>("verifyPureJava") {
    description = "Verifies that no server or CraftEngine dependency is on this module's classpaths."
    group = "verification"
    projectPath.set(project.path)
    forbiddenGroups.set(listOf("io.papermc", "org.bukkit", "org.spigotmc", "com.destroystokyo", "net.momirealms"))
    for (name in listOf("compileClasspath", "runtimeClasspath")) {
        modules.addAll(configurations.named(name).flatMap { configuration ->
            configuration.incoming.resolutionResult.rootComponent.map(::collectModules)
        })
    }
}

tasks.named("check") {
    dependsOn(verifyPureJava)
}
