import java.io.File

/**
 * Writes `manifest.yml`, the list of generated files the Mekanized plugin installs: CraftEngine
 * configuration files (`pack:`, relative to the pack folder) and MachineEngine definitions
 * (`definitions:`). The plugin installs what it lists and nothing else, so a generated file cannot be
 * forgotten in a hand-kept list. Textures, models and the license are not listed: they are always copied.
 */
internal fun writeManifest(out: File) {
    fun yamlFiles(folder: String): List<String> = out.resolve(folder).listFiles { file -> file.isFile && file.extension == "yml" }
        .orEmpty().map { it.name }.sorted()
    writeYaml(out.resolve("manifest.yml"),
        "Generated files the Mekanized plugin installs (written by the import; do not edit).",
        linkedMapOf("pack" to yamlFiles("pack/configuration").map { "configuration/$it" },
            "definitions" to yamlFiles("definitions")))
}
