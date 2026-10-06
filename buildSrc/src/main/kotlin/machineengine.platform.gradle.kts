// Every module except nms:v*: Paper and CraftEngine on the compile classpath (D26), newest Paper API,
// and a check that the bytecode only uses what the oldest supported Paper version has (D25).
plugins {
    id("machineengine.java-conventions")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val paperApi = libs.findLibrary("paper-api").get()
val craftEngineVersion = providers.gradleProperty("craftengine_version").get()

/** The oldest supported Paper API, with everything it brings (Adventure, Guava...). */
val paperBaseline: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}

dependencies {
    compileOnlyApi(paperApi)
    // CraftEngine's published POMs pull in its whole runtime; take only the classes we compile against.
    compileOnlyApi("net.momirealms:craft-engine-core:$craftEngineVersion") { isTransitive = false }
    compileOnlyApi("net.momirealms:craft-engine-bukkit:$craftEngineVersion") { isTransitive = false }
    // Headless tests run without a server but still need Adventure Key and friends.
    testImplementation(paperApi)
    paperBaseline(libs.findLibrary("paper-api-baseline").get())
}

val verifyPaperBaseline = tasks.register<VerifyPaperBaseline>("verifyPaperBaseline") {
    description = "Checks that the compiled classes only use API present in the oldest supported Paper version."
    group = "verification"
    classes.from(sourceSets.main.map { it.output.classesDirs })
    baseline.from(paperBaseline)
    checkedPackages.set(listOf(
        "org/bukkit/", "io/papermc/paper/", "com/destroystokyo/paper/", "org/spigotmc/", "co/aikar/",
        "net/kyori/", "net/md_5/bungee/", "com/mojang/brigadier/",
        "com/google/common/", "com/google/gson/", "org/joml/", "org/yaml/snakeyaml/",
    ))
    maxClassVersion.set(65) // Java 21
    report.set(layout.buildDirectory.file("reports/paper-baseline.txt"))
}

tasks.named("check") {
    dependsOn(verifyPaperBaseline)
}
