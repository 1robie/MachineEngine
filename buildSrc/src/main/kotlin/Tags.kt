import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File

/**
 * `c:` tags with their vanilla members (E10): Mekanized item tags that make NeoForge convention tags
 * work on Paper. Tags with neither vanilla nor Mekanized members are listed in the report.
 */
internal fun writeAliases(file: File, tags: Set<String>, itemTags: Map<String, List<String>>, report: Report) {
    val aliases = sortedMapOf<String, Any?>()
    for (tag in tags.filter { it.startsWith("c:") }.sorted()) {
        val vanilla = VanillaAliases.of(tag)
        if (vanilla.isNotEmpty()) {
            aliases[tag] = vanilla
            report.count("vanilla tag aliases")
        } else if (itemTags[tag].isNullOrEmpty()) {
            report.skip("c: tag with no known vanilla or Mekanized members (add them to an item-tags overlay)", tag)
        }
    }
    writeYaml(file, "Vanilla members of NeoForge's c: convention tags, which do not exist on Paper.\n" +
            "Generated for Mekanized; add your own items to these tags in another definitions file.", linkedMapOf("item-tags" to aliases))
}

// -- tags -----------------------------------------------------------------------------------------

/**
 * Item and chemical tags. Mekanism's datagen only lists its own items in `c:` tags (NeoForge adds
 * the vanilla members); the vanilla members are added by the Mekanized overlay (Phase 1, E10).
 */
internal class Tags(private val source: MekanismSource, private val target: File, private val report: Report) {
    fun convert(): Map<String, List<String>> {
        val itemTags = sortedMapOf<String, Any?>()
        val chemicalTags = sortedMapOf<String, Any?>()
        source.datagenModules.forEach { module ->
            val namespaces = module.resolve("data").listFiles() ?: return@forEach
            for (namespace in namespaces) {
                read(namespace.resolve("tags/item"), namespace.name, itemTags, "item tags")
                read(namespace.resolve("tags/mekanism/chemical"), namespace.name, chemicalTags, "chemical tags")
                read(namespace.resolve("tags/chemical"), namespace.name, chemicalTags, "chemical tags")
            }
        }
        writeYaml(target.resolve("tags.yml"),
            "Mekanized item and chemical tags, generated from Mekanism (MIT, see LICENSE-MEKANISM.txt).\n" +
                    "\"#name\" entries include another tag.",
            linkedMapOf("item-tags" to itemTags, "resource-tags" to chemicalTags))
        @Suppress("UNCHECKED_CAST")
        return itemTags as Map<String, List<String>>
    }

    private fun read(base: File, namespace: String, into: MutableMap<String, Any?>, what: String) {
        if (!base.isDirectory) return
        base.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            val tag = Ids.rename("$namespace:" + file.relativeTo(base).invariantSeparatorsPath.removeSuffix(".json"))
            val values = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("values") ?: JsonArray()
            val members = values.mapNotNull { value ->
                when {
                    value.isJsonPrimitive -> Ids.rename(value.asString)
                    value.isJsonObject && value.asJsonObject.get("required")?.asBoolean == false -> null
                    value.isJsonObject -> Ids.rename(value.asJsonObject.get("id").asString)
                    else -> null
                }
            }.filter { !it.removePrefix("#").contains(":") || it.removePrefix("#").substringBefore(":") in setOf("mekanized", "minecraft", "c") }
            if (members.isEmpty()) return@forEach
            @Suppress("UNCHECKED_CAST")
            val existing = into[tag] as MutableList<String>? ?: mutableListOf<String>().also { into[tag] = it }
            members.filter { it !in existing }.forEach { existing += it }
            report.count(what)
        }
    }
}
