import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

// -- assets ---------------------------------------------------------------------------------------

internal class Assets(private val source: MekanismSource, private val target: File, private val report: Report) {
    fun copy() {
        val origin = mutableMapOf<String, String>()
        for (root in source.assetRoots) {
            for (folder in listOf("textures", "models")) {
                val base = source.resolve(root).resolve(folder)
                if (!base.isDirectory) continue
                base.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = folder + "/" + file.relativeTo(base).invariantSeparatorsPath
                    val previous = origin.putIfAbsent(relative, root)
                    if (previous != null) {
                        if (file.readBytes().contentEquals(target.resolve(relative).readBytes())) return@forEach
                        report.skip("asset path used by both Mekanism and Mekanism Generators (first kept)", "$relative ($root)")
                        return@forEach
                    }
                    copyFile(file, target.resolve(relative), relative)
                }
            }
        }
    }

    private fun copyFile(file: File, destination: File, relative: String) {
        destination.parentFile.mkdirs()
        if (file.extension == "mcmeta") {
            val cleaned = vanillaMetadata(Ids.renameInText(file.readText())) ?: return
            destination.writeText(cleaned)
            report.count("texture files")
            return
        }
        if (file.extension == "json") {
            val text = flattenLoaderModel(Ids.renameInText(file.readText()), mekanismModels(source))
            if ("\"loader\"" in text) {
                report.skip("model needs a mod-side loader (NeoForge composite/obj); not usable by vanilla clients yet", relative)
            }
            destination.writeText(text)
        } else {
            file.copyTo(destination, overwrite = true)
        }
        report.count(if (relative.startsWith("textures/")) "texture files" else "model files")
    }
}

/**
 * Reads a renamed model reference (`mekanized:block/x`) from Mekanism's sources, renamed too; null if
 * it is not one of Mekanism's models.
 */
internal fun mekanismModels(source: MekanismSource): (String) -> JsonObject? = { ref ->
    if (!ref.startsWith("mekanized:")) null
    else source.asset("models/${ref.removePrefix("mekanized:")}.json")
        ?.let { JsonParser.parseString(Ids.renameInText(it.readText())).asJsonObject }
}

/** A child model's elements and textures, following `parent` references to the first model with elements. */
private fun inlineParents(child: JsonObject, resolve: (String) -> JsonObject?, depth: Int = 0): JsonObject {
    if (child.has("elements") || depth > 8) return child
    val parent = child.get("parent")?.asString?.let(resolve) ?: return child
    val inlined = inlineParents(parent, resolve, depth + 1).deepCopy()
    val textures = inlined.getAsJsonObject("textures") ?: JsonObject().also { inlined.add("textures", it) }
    child.getAsJsonObject("textures")?.entrySet()?.forEach { (key, value) -> textures.add(key, value) } // the child wins
    inlined.remove("parent")
    return inlined
}

/**
 * Two of Mekanism's model loaders only group plain elements, so they flatten into vanilla models:
 * `mekanism:energy_cube` (frame, ports and leds lists) and `neoforge:composite` (child models with
 * their own textures, prefixed to avoid clashes). OBJ models and other loaders are left as they are.
 */
internal fun flattenLoaderModel(text: String, resolve: (String) -> JsonObject? = { null }): String {
    val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return text
    val loader = json.get("loader")?.asString ?: return text
    val elements = com.google.gson.JsonArray()
    fun addAll(value: JsonElement?) {
        when {
            value == null -> {}
            value.isJsonArray -> value.asJsonArray.forEach { if (it.isJsonObject && it.asJsonObject.has("from")) elements.add(it) else addAll(it) }
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { addAll(it.value) }
        }
    }
    when (loader) {
        "mekanized:energy_cube" -> {
            addAll(json.remove("frame"))
            addAll(json.remove("ports"))
            addAll(json.remove("leds"))
        }
        "neoforge:composite" -> {
            val textures = json.getAsJsonObject("textures") ?: JsonObject().also { json.add("textures", it) }
            val children = json.remove("children")?.asJsonObject ?: return text
            for ((childName, childValue) in children.entrySet()) {
                // Factories: a child is just a parent reference (the machine base, the tier's LED).
                val child = inlineParents(childValue.asJsonObject, resolve)
                val prefix = childName.replace(Regex("[^a-z0-9_]"), "_") + "_"
                child.getAsJsonObject("textures")?.entrySet()?.forEach { (key, texture) -> textures.add(prefix + key, texture) }
                child.getAsJsonArray("elements")?.forEach { element ->
                    element.asJsonObject.getAsJsonObject("faces")?.entrySet()?.forEach { (_, face) ->
                        val ref = face.asJsonObject.get("texture")?.asString
                        if (ref != null && ref.startsWith("#") && child.getAsJsonObject("textures")?.has(ref.removePrefix("#")) == true) {
                            face.asJsonObject.addProperty("texture", "#" + prefix + ref.removePrefix("#"))
                        }
                    }
                    elements.add(element)
                }
            }
            if (!json.has("parent")) json.addProperty("parent", "minecraft:block/block")
        }
        else -> return text
    }
    json.remove("loader")
    json.add("elements", elements)
    return prettyGson.toJson(json)
}

/**
 * Texture metadata a vanilla client accepts: mod-only sections (CTM, connected textures) are dropped,
 * and an empty `frames` list (NeoForge reads it as "every frame", vanilla fails to stitch the atlas)
 * is removed. Null when nothing is left to write.
 */
internal fun vanillaMetadata(text: String): String? {
    val json = JsonParser.parseString(text).asJsonObject
    for (modSection in listOf("ctm", "extra")) json.remove(modSection)
    json.getAsJsonObject("animation")?.let { animation ->
        val frames = animation.get("frames")
        if (frames != null && frames.isJsonArray && frames.asJsonArray.isEmpty) animation.remove("frames")
    }
    if (json.size() == 0) return null
    return prettyGson.toJson(json)
}
