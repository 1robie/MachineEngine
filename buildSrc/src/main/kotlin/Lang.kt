import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Mekanism's chemical names and translations. */
internal class Lang(private val source: MekanismSource, private val report: Report) {
    /** Chemical names (gases, infuse types, slurries, pigments; Mekanism 1.21 unifies them) from the lang keys. */
    fun chemicals(): List<String> {
        val names = sortedSetOf<String>()
        for (module in listOf("mekanism", "mekanismgenerators")) {
            val file = source.resolve("src/datagen/generated/$module/assets/$module/lang/en_us.json")
            if (!file.isFile) continue
            JsonParser.parseString(file.readText()).asJsonObject.keySet()
                .filter { it.startsWith("chemical.$module.") }
                .map { it.removePrefix("chemical.$module.") }
                .filter { "." !in it && it != "empty" }
                .forEach { names += it }
        }
        report.count("chemicals", names.size)
        return names.toList()
    }

    /** lang -> key -> name, for [keys] (Mekanism `item.mekanism.x` keys, renamed). */
    fun languages(keys: Set<String>): Map<String, Any?> {
        val languages = sortedMapOf<String, Any?>()
        // Generators' names are merged into the same language (one namespace, K1).
        val files = source.assetRoots.flatMap { source.resolve("$it/lang").listFiles().orEmpty().toList() }
            .filter { it.extension == "json" }
        for (file in files) {
            val json: JsonObject = JsonParser.parseString(file.readText()).asJsonObject
            @Suppress("UNCHECKED_CAST")
            val entries = languages.getOrPut(file.nameWithoutExtension) { sortedMapOf<String, String>() } as MutableMap<String, String>
            for (key in keys) {
                val value = (json.get(key) ?: json.get(key.replace(".mekanism.", ".mekanismgenerators.")))?.asString ?: continue
                // Mekanism names are plain text; CraftEngine reads lang values as MiniMessage.
                entries[key.replace(".mekanism.", ".mekanized.")] = value.replace("<", "\\<")
            }
        }
        languages.entries.removeIf { (it.value as Map<*, *>).isEmpty() }
        report.count("languages")
        return languages
    }
}
