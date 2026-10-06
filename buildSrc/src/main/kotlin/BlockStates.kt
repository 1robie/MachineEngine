import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

/**
 * How a block state looks: the model, its rotation, and the vanilla state CraftEngine uses for it
 * ([autoState]: a name like `solid`, or a map with `type` and `id` to share one state between blocks).
 */
internal data class Appearance(val autoState: Any, val path: String, val x: Int? = null, val y: Int? = null) {
    fun toYaml(): Map<String, Any?> {
        val model = linkedMapOf<String, Any?>("path" to path)
        x?.let { model["x"] = it }
        y?.let { model["y"] = it }
        return linkedMapOf("auto_state" to autoState, "model" to model)
    }
}

/** The CraftEngine `state` (one appearance) or `states` (properties and their variants) of a block. */
internal sealed interface BlockState {
    val allAppearances: Collection<Appearance>

    fun toYaml(): Map<String, Any?>

    /** The same states with every appearance's `auto_state` replaced. */
    fun withAutoState(autoState: Any): BlockState

    /** The model of the first appearance. */
    fun firstModel(): String = allAppearances.first().path
}

internal data class SingleState(val appearance: Appearance) : BlockState {
    override val allAppearances get() = listOf(appearance)
    override fun toYaml(): Map<String, Any?> = mapOf("state" to appearance.toYaml())
    override fun withAutoState(autoState: Any) = SingleState(appearance.copy(autoState = autoState))
}

/**
 * States with properties (`facing`, `active`...): [variants] maps a property combination
 * (`facing=north,active=true`) to the name of its appearance in [appearances].
 */
internal data class VariantStates(
    val properties: Map<String, Any?>,
    val appearances: Map<String, Appearance>,
    val variants: Map<String, String>,
) : BlockState {
    override val allAppearances get() = appearances.values

    override fun toYaml(): Map<String, Any?> = mapOf("states" to linkedMapOf(
        "properties" to properties,
        "appearances" to appearances.mapValues { it.value.toYaml() },
        "variants" to variants.mapValues { mapOf("appearance" to it.value) },
    ))

    override fun withAutoState(autoState: Any) = copy(appearances = appearances.mapValues { it.value.copy(autoState = autoState) })
}

/** A block with one model (ores, storage blocks): its drops, its settings and the model. */
internal data class PlainBlock(val loot: Map<String, Any?>, val settings: Map<String, Any?>, val model: String) {
    fun toYaml(): Map<String, Any?> = linkedMapOf("loot" to loot, "settings" to settings, "state" to Appearance("solid", model).toYaml())
}

/**
 * Mekanism's blockstates, item definitions and loot tables as CraftEngine states. [modelsRoot] is the
 * generated models folder: the import has already copied (and flattened) the models by then.
 */
internal class BlockStates(private val source: MekanismSource, private val modelsRoot: File, private val report: Report) {
    /** The item definition (datagen) of an item or block item, null if Mekanism has none. */
    fun itemDefinition(name: String): File? = source.assetRoots.filter { "/datagen/" in it }
        .map { source.resolve("$it/items/$name.json") }.firstOrNull { it.isFile }

    /** The model of a plain item definition (`minecraft:model`), else null (special items come later). */
    fun itemModel(file: File): String? {
        var model = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("model") ?: return null
        // A model chosen by a mod component (the configurator's modes): its fallback.
        if (model.get("type")?.asString == "minecraft:select") model = model.getAsJsonObject("fallback") ?: return null
        // A model chosen by whether a component is there (the cardboard box, full or not): the empty one.
        if (model.get("type")?.asString == "minecraft:condition") model = model.getAsJsonObject("on_false") ?: return null
        // Drawn in code upstream (gear): its base model, made drawable by GearModels.
        if (model.get("type")?.asString == "minecraft:special") return model.get("base")?.asString
        if (model.get("type")?.asString != "minecraft:model") return null
        return model.get("model")?.asString
    }

    /** The item model: a plain model, or the base of a special one (energy cubes). */
    fun blockItemModel(name: String): String? {
        val file = itemDefinition(name) ?: return null
        val model = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("model") ?: return null
        val ref = when (model.get("type")?.asString) {
            "minecraft:model" -> model.get("model")?.asString
            "minecraft:special" -> model.get("base")?.asString
            else -> null
        }
        val renamed = ref?.let { Ids.rename(it) } ?: return null
        // Mekanism draws some items in code (energy cubes): an empty model is no use, show the block.
        val generated = modelsRoot.resolve(renamed.substringAfter(":") + ".json")
        if (generated.isFile) {
            val json = JsonParser.parseString(generated.readText()).asJsonObject
            if (!json.has("parent") && !json.has("elements")) return null
        }
        return renamed
    }

    /**
     * CraftEngine states from a Mekanism blockstate: `facing` (4 or 6 directions) and `active`
     * properties, one appearance per variant with its rotation. A model that needs a NeoForge loader
     * is replaced by the block's item model (a plain cube for energy cubes).
     */
    fun machineStates(name: String): BlockState? {
        val file = source.asset("blockstates/$name.json") ?: return null
        val variants = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("variants") ?: return null
        val values = linkedMapOf<String, MutableSet<String>>()
        var appearances = linkedMapOf<String, Appearance>()
        var mapping = linkedMapOf<String, String>()
        for ((key, value) in variants.entrySet()) {
            val raw = (if (value.isJsonArray) value.asJsonArray[0] else value).asJsonObject
            // Energy cubes: a Mekanism variant type keeping the vanilla part under "tier_model".
            val variant = raw.getAsJsonObject("tier_model") ?: raw
            val modelRef = variant.get("model")?.asString ?: return null
            // Drawn in code upstream (a loader, or the empty "nothing" model of the personal chest): the item model.
            val model = if (needsLoader(modelRef) || modelRef.endsWith(":block/nothing")) (blockItemModel(name) ?: return null)
            else Ids.rename(modelRef)
            val x = variant.get("x")?.asInt?.let { if (it != 0) Math.floorMod(it, 360) else null }
            val y = variant.get("y")?.asInt?.let { if (it != 0) Math.floorMod(it, 360) else null }
            val appearanceName = appearanceName(key)
            appearances[appearanceName] = Appearance("solid", model, x, y)
            if (key.isNotEmpty()) {
                mapping[key] = appearanceName
                key.split(",").forEach { pair -> values.getOrPut(pair.substringBefore("=")) { linkedSetOf() } += pair.substringAfter("=") }
            }
        }
        if (values.isEmpty()) return SingleState(appearances["default"] ?: return null)
        // An active state that looks like the inactive one only makes the block flicker: drop it.
        if ("active" in values && mapping.keys.filter { "active=true" in it }.all { key ->
                sameLook(appearances, mapping, key, key.replace("active=true", "active=false")) }) {
            values.remove("active")
            val newMapping = linkedMapOf<String, String>()
            val newAppearances = linkedMapOf<String, Appearance>()
            for (key in mapping.keys.filter { "active=false" in it }) {
                val stripped = key.split(",").filter { it != "active=false" }.joinToString(",")
                val newName = appearanceName(stripped)
                newAppearances[newName] = appearances[mapping.getValue(key)] ?: return null
                if (stripped.isNotEmpty()) newMapping[stripped] = newName
            }
            if (values.isEmpty()) return SingleState(newAppearances["default"] ?: return null)
            mapping = newMapping
            appearances = newAppearances
        }
        val properties = linkedMapOf<String, Any?>()
        for ((property, options) in values) {
            properties[property] = when (property) {
                "facing" -> mapOf("type" to if (options.size == 4) "4-direction" else "6-direction", "default" to "north")
                "active", "open" -> mapOf("type" to "boolean", "default" to false)
                // Valves and ports: the machine shows its mode (MachineContext.setBlockProperty).
                "mode" -> mapOf("type" to "string", "values" to options.toList(), "default" to (if ("input" in options) "input" else options.first()))
                else -> return null
            }
        }
        return VariantStates(properties, appearances, mapping)
    }

    /** `facing=north,active=false` -> `facing_north_active_false`; the empty combination is `default`. */
    private fun appearanceName(combination: String) = combination.replace("=", "_").replace(",", "_").ifEmpty { "default" }

    /** Whether the variants [a] and [b] show the same model the same way (path, rotation, or identical model files). */
    private fun sameLook(appearances: Map<String, Appearance>, mapping: Map<String, String>, a: String, b: String): Boolean {
        val first = mapping[a]?.let { appearances[it] } ?: return false
        val second = mapping[b]?.let { appearances[it] } ?: return false
        if (first.x != second.x || first.y != second.y) return false
        if (first.path == second.path) return true
        fun text(path: String) = modelsRoot.resolve(path.substringAfter(":") + ".json").takeIf { it.isFile }?.readText()
            ?.let { JsonParser.parseString(it) }
        val jsonA = text(first.path) ?: return false
        val jsonB = text(second.path) ?: return false
        if (jsonA == jsonB) return true
        // An active model that is only its inactive one as parent.
        fun onlyParent(json: JsonElement, parent: String) =
            json.isJsonObject && json.asJsonObject.size() == 1 && json.asJsonObject.get("parent")?.asString == parent
        return onlyParent(jsonA, second.path) || onlyParent(jsonB, first.path)
    }

    private fun needsLoader(modelRef: String): Boolean {
        val path = modelRef.substringAfter(":")
        val module = if (modelRef.startsWith("mekanismgenerators:")) "mekanismgenerators" else "mekanism"
        return source.assetRoots.filter { it.endsWith("/$module") }
            .map { source.resolve("$it/models/$path.json") }.firstOrNull { it.isFile }?.readText()
            // energy_cube and composite models are flattened into vanilla ones by the import.
            ?.let { "\"loader\"" in flattenLoaderModel(Ids.renameInText(it), mekanismModels(source)) } ?: true
    }

    /** A block with one model (ores, storage blocks), its drops and its settings; null (and reported) if it has none. */
    fun plainBlock(name: String): PlainBlock? {
        val model = singleModel(name) ?: run {
            report.skip("block without a single-model blockstate (Phase 1 handles simple blocks only)", name)
            return null
        }
        val settings: Map<String, Any?> = when {
            name.endsWith("_ore") -> mapOf("template" to if (name.startsWith("deepslate_")) "default:settings/deepslate_ore" else "default:settings/ore",
                "arguments" to mapOf("break_power" to 1))
            else -> mapOf("template" to "default:settings/solid_1x1x1", "overrides" to mapOf(
                "hardness" to 5.0, "resistance" to 6.0, "tags" to listOf("minecraft:mineable/pickaxe"),
                "correct_tools" to listOf("#minecraft:pickaxes"), "required_break_power" to 1))
        }
        return PlainBlock(lootTable(name), settings, Ids.rename(model))
    }

    private fun singleModel(name: String): String? {
        for (root in source.assetRoots.take(2)) {
            val file = source.resolve("$root/blockstates/$name.json")
            if (!file.isFile) continue
            val variants = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("variants") ?: return null
            if (variants.size() != 1) return null
            val only = variants.entrySet().first().value
            return (if (only.isJsonArray) only.asJsonArray[0].asJsonObject else only.asJsonObject).get("model")?.asString
        }
        return null
    }

    /** Ores: silk touch drops the block, else the raw item with Fortune; other blocks drop themselves. */
    private fun lootTable(name: String): Map<String, Any?> {
        val self = mapOf("template" to "default:loot_table/self")
        val file = source.resolve("src/datagen/generated/mekanism/data/mekanism/loot_table/blocks/$name.json")
        if (!file.isFile) return self
        val text = file.readText()
        if ("minecraft:apply_bonus" !in text) return self
        val drop = Regex("\"name\": \"(mekanism:[a-z0-9_]+)\"").findAll(text).map { it.groupValues[1] }
            .firstOrNull { it != "mekanism:$name" } ?: return self
        val arguments = linkedMapOf<String, Any?>("ore_drop" to Ids.rename(drop), "ore_block" to "mekanized:$name")
        Regex("\"max\": ([0-9.]+),\\s*\"min\": ([0-9.]+)").find(text)?.let { range ->
            arguments["ore_drop_count"] = "${range.groupValues[2].toDouble().toInt()}~${range.groupValues[1].toDouble().toInt()}"
        }
        return mapOf("template" to "default:loot_table/ore", "arguments" to arguments)
    }
}
