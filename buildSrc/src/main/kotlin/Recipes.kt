import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File

// -- recipes --------------------------------------------------------------------------------------

/**
 * Machine recipe conversion. Durations and energy per tick are Mekanism's defaults for the machine
 * running that recipe type; they are written into every recipe so server owners can tune them.
 */
internal class Recipes(
    private val source: MekanismSource,
    private val out: File,
    private val report: Report,
    /**
     * Content per plan phase, in order (file prefix to ids): a CraftEngine recipe goes into the first
     * phase's file whose content, with the earlier phases', has every Mekanized item it uses.
     */
    private val phases: List<Pair<String, Set<String>>>,
    private val itemTags: Map<String, List<String>>,
) {
    /**
     * Mekanism's fluids (its `c:` fluid tags, one per fluid), plus water and lava: resources
     * `mekanized:fluid/<name>`, apart from the chemicals of the same name.
     */
    val fluids: List<String> get() = source.fluids

    /** A fluid id or `c:`/`minecraft:` fluid tag as a fluid resource type. */
    private fun fluidType(ref: String): String {
        val id = Ids.rename(ref.removePrefix("#"))
        return when {
            id == "minecraft:water" || id == "minecraft:lava" -> "mekanized:fluid/" + id.removePrefix("minecraft:")
            id.startsWith("c:") && id.removePrefix("c:") in fluids -> "mekanized:fluid/" + id.removePrefix("c:")
            id.startsWith("mekanized:") && id.removePrefix("mekanized:") in fluids -> "mekanized:fluid/" + id.removePrefix("mekanized:")
            else -> throw Unsupported("unknown fluid $ref")
        }
    }

    /**
     * The first chemical of a chemical tag: per-tick costs are exact resources (e.g. water vapor
     * for `#mekanism:water_vapor`, which also lists steam).
     */
    private fun firstChemical(tag: String): String {
        val namespace = tag.substringBefore(":")
        val path = tag.substringAfter(":")
        // Already renamed: Mekanism's own tags live under its original namespaces.
        val namespaces = if (namespace == "mekanized") listOf("mekanism", "mekanismgenerators") else listOf(namespace)
        for (module in source.datagenModules) for (namespace in namespaces) {
            for (folder in listOf("tags/mekanism/chemical", "tags/chemical")) {
                val file = module.resolve("data/$namespace/$folder/$path.json")
                if (!file.isFile) continue
                val first = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("values")
                    .map { if (it.isJsonPrimitive) it.asString else it.asJsonObject.get("id").asString }
                    .firstOrNull { !it.startsWith("#") } ?: continue
                return Ids.rename(first)
            }
        }
        throw Unsupported("chemical tag $tag has no chemical")
    }
    /** Every item tag the recipes reference, for the vanilla aliases. */
    val referencedTags = sortedSetOf<String>()
    private data class Machine(val duration: Int, val energyPerTick: Long)

    private val machines = mapOf(
        "enriching" to Machine(200, 50),
        "crushing" to Machine(200, 50),
        "combining" to Machine(200, 50),
        "sawing" to Machine(200, 50),
        "metallurgic_infusing" to Machine(200, 50),
        "compressing" to Machine(200, 100),
        "purifying" to Machine(200, 200),
        "injecting" to Machine(200, 400),
        "oxidizing" to Machine(100, 200),
        "dissolution" to Machine(100, 400),
        "washing" to Machine(1, 200),
        "crystallizing" to Machine(200, 160),
        "chemical_infusing" to Machine(1, 200),
        "separating" to Machine(1, 400),
        // Rotary recipes go both ways: split into one recipe type per direction (the machine's mode).
        // Phase 6 machines (Mekanism's default usage): painting and pigments, late-game chemistry.
        "painting" to Machine(256, 100),
        "pigment_extracting" to Machine(100, 200),
        "pigment_mixing" to Machine(1, 200),
        "centrifuging" to Machine(1, 200),
        "activating" to Machine(1, 0),
        "nucleosynthesizing" to Machine(500, 100_000),
        // The Thermal Evaporation Plant: no energy, its rate comes from its height and the sun (Phase 5a).
        "evaporating" to Machine(1, 0),
        "condensing" to Machine(1, 100),
        "decondensing" to Machine(1, 100),
        // The Pressurized Reaction Chamber: 100 J/t, plus each recipe's energy_required.
        "reaction" to Machine(100, 100),
        // Not machines: items turned into a chemical or energy when put into a machine's slot.
        "chemical_conversion" to Machine(0, 0),
        "energy_conversion" to Machine(0, 0),
    )

    private val outOfPlan = mapOf(
        "bin_insert" to "special recipe (bins handle it in code)",
        "bin_extract" to "special recipe (bins handle it in code)",
        "clear_configuration" to "special recipe (handled in code)",
    )

    private val machineRecipes = sortedMapOf<String, MutableMap<String, Any?>>()
    private val craftEngineRecipes = sortedMapOf<String, MutableMap<String, Any?>>()

    fun convert() {
        for (module in listOf("mekanism", "mekanismgenerators")) {
            val base = source.resolve("src/datagen/generated/$module/data/$module/recipe")
            if (!base.isDirectory) continue
            base.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.forEach { file ->
                val path = file.relativeTo(base).invariantSeparatorsPath.removeSuffix(".json")
                val id = "mekanized:$path"
                val json = JsonParser.parseString(file.readText()).asJsonObject
                try {
                    convert(id, json)
                } catch (e: Unsupported) {
                    report.skip(e.message ?: "unsupported", id)
                }
            }
        }
        writeOutputs()
    }

    private class Unsupported(message: String) : RuntimeException(message)

    /**
     * Ingredients replaced where Mekanism's recipe needs content outside the plan, e.g. the chemical
     * oxidizer's dynamic tank (a Phase 5 multiblock) and personal storage. Listed in the report.
     */
    private val adaptedIngredients = mapOf(
        "mekanized:chemical_oxidizer" to mapOf("mekanism:dynamic_tank" to "mekanism:basic_fluid_tank",
            "#mekanism:personal_storage" to "minecraft:chest"),
        // Mekanized has no fluid bucket items: blue ice stands for the liquid hydrogen.
        "mekanized:module_frost_walker_unit" to mapOf("mekanism:hydrogen_bucket" to "minecraft:blue_ice"),
    )

    private fun convert(id: String, json: JsonObject) {
        if (json.has("neoforge:conditions")) throw Unsupported("needs another mod (neoforge:conditions)")
        adaptedIngredients[id]?.let { replacements ->
            // Shaped recipes name their ingredients in "key", shapeless ones list them in "ingredients".
            json.getAsJsonObject("key")?.let { key ->
                for ((symbol, value) in key.entrySet().toList()) {
                    if (value.isJsonPrimitive) replacements[value.asString]?.let { key.addProperty(symbol, it) }
                }
            }
            json.getAsJsonArray("ingredients")?.let { list ->
                for (index in 0 until list.size()) {
                    val value = list[index]
                    if (value.isJsonPrimitive) replacements[value.asString]?.let { list[index] = JsonPrimitive(it) }
                }
            }
            report.adapt("ingredients outside the plan replaced (" + replacements.entries.joinToString { "${it.key} -> ${it.value}" } + ")", id)
        }
        val type = json.get("type")?.asString ?: throw Unsupported("no type")
        when (type) {
            "minecraft:crafting_shaped", "mekanism:mek_data" -> craftEngine(id, "shaped", shaped(json))
            "minecraft:crafting_shapeless" -> craftEngine(id, "shapeless", shapeless(json))
            "minecraft:smelting", "minecraft:blasting" -> craftEngine(id, "cooking", cooking(type.removePrefix("minecraft:"), json))
            else -> {
                val kind = type.removePrefix("mekanism:")
                outOfPlan[kind]?.let { throw Unsupported(it) }
                if (kind == "rotary") {
                    rotary(id, json)
                    return
                }
                val machine = machines[kind] ?: throw Unsupported("recipe type $type has no converter")
                evaporating = kind == "evaporating"
                machineRecipes[kind + "|" + id] = try {
                    machineRecipe(kind, machine, json)
                } finally {
                    evaporating = false
                }
                report.count("machine recipes")
            }
        }
    }

    // -- machine recipes

    /** Chemical to fluid (`condensing`) and fluid to chemical (`decondensing`). */
    private fun rotary(id: String, json: JsonObject) {
        for ((kind, fields) in listOf("condensing" to listOf("chemical_input", "fluid_output"),
            "decondensing" to listOf("fluid_input", "chemical_output"))) {
            val half = JsonObject()
            for (field in fields) half.add(field, json.get(field) ?: throw Unsupported("rotary recipe without $field"))
            machineRecipes[kind + "|" + id.replace("mekanized:rotary/", "mekanized:$kind/")] = machineRecipe(kind, machines.getValue(kind), half)
            report.count("machine recipes")
        }
    }

    private fun machineRecipe(kind: String, machine: Machine, json: JsonObject): MutableMap<String, Any?> {
        val recipe = linkedMapOf<String, Any?>("type" to "mekanized:$kind")
        val inputs = mutableListOf<Any?>()
        val outputs = mutableListOf<Any?>()
        val perTick = mutableListOf<Any?>()
        val chanceOutputs = mutableListOf<Any?>()
        val perTickChemical = json.get("per_tick_usage")?.asBoolean ?: false

        for ((field, value) in json.entrySet()) {
            when (field) {
                "type", "per_tick_usage", "duration", "secondary_chance", "energy_required", "energy_multiplier" -> {}
                "input", "item_input", "main_input", "extra_input" ->
                    if (value.isJsonObject && value.asJsonObject.has("chemical") || value.isJsonObject && value.asJsonObject.has("tag")
                        && value.asJsonObject.has("amount")) {
                        if (perTickChemical) perTick += chemicalCost(value.asJsonObject) else inputs += chemicalInput(value.asJsonObject)
                    } else if (value.isJsonObject && value.asJsonObject.has("amount") && !value.asJsonObject.has("count")) {
                        inputs += fluidInput(value.asJsonObject)
                    } else {
                        inputs += itemInput(value.asJsonObject)
                    }
                "chemical_input", "left_input", "right_input" ->
                    if (perTickChemical) perTick += chemicalCost(value.asJsonObject) else inputs += chemicalInput(value.asJsonObject)
                "fluid_input" -> inputs += fluidInput(value.asJsonObject)
                "output", "main_output", "item_output", "chemical_output", "fluid_output",
                "left_chemical_output", "right_chemical_output" ->
                    if (value.isJsonPrimitive) outputs += mapOf("type" to "mekanized:energy", "amount" to value.asLong)
                    else outputs += output(field, value.asJsonObject)
                "secondary_output" -> chanceOutputs += output(field, value.asJsonObject).toMutableMap().apply {
                    put("chance", json.get("secondary_chance")?.asDouble ?: 1.0)
                }
                else -> throw Unsupported("recipe field '$field' has no converter")
            }
        }
        val duration = json.get("duration")?.asInt ?: machine.duration
        if (duration > 0) recipe["duration"] = duration
        if (inputs.isNotEmpty()) recipe["inputs"] = inputs
        if (outputs.isNotEmpty()) recipe["outputs"] = outputs
        if (chanceOutputs.isNotEmpty()) recipe["chance-outputs"] = chanceOutputs
        // energy_required: the reaction's own energy per tick; energy_multiplier: times the machine's base usage.
        val energy = if (kind == "reaction") machine.energyPerTick + (json.get("energy_required")?.asLong ?: 0)
        else json.get("energy_required")?.asLong
            ?: (machine.energyPerTick * (json.get("energy_multiplier")?.asDouble ?: 1.0)).toLong()
        if (energy > 0) perTick.add(0, mapOf("type" to "mekanized:energy", "amount" to energy))
        if (perTick.isNotEmpty()) recipe["cost-per-tick"] = perTick
        return recipe
    }

    /** An item ingredient: `{item: id}`, `{item-tag: id}` or `{any-of: [...]}`, with its count. */
    private fun itemInput(json: JsonObject): Map<String, Any?> {
        val filter = itemFilter(json.get("ingredient") ?: throw Unsupported("item input without ingredient")).toMutableMap()
        filter["amount"] = json.get("count")?.asInt ?: 1
        return filter
    }

    private fun itemFilter(ingredient: JsonElement): Map<String, Any?> = when {
        ingredient.isJsonPrimitive -> itemRef(ingredient.asString)
        ingredient.isJsonArray -> mapOf("any-of" to ingredient.asJsonArray.map { itemFilter(it) })
        // NeoForge's compound ingredients: everything in base but not in subtracted; in every child.
        ingredient.isJsonObject && ingredient.asJsonObject.get("neoforge:ingredient_type")?.asString == "neoforge:difference" ->
            mapOf("all" to listOf(itemFilter(ingredient.asJsonObject.get("base")),
                mapOf("not" to itemFilter(ingredient.asJsonObject.get("subtracted")))))
        ingredient.isJsonObject && ingredient.asJsonObject.get("neoforge:ingredient_type")?.asString == "neoforge:intersection" ->
            mapOf("all" to ingredient.asJsonObject.getAsJsonArray("children").map { itemFilter(it) })
        ingredient.isJsonObject && ingredient.asJsonObject.get("type")?.asString == "neoforge:or" ->
            mapOf("any-of" to ingredient.asJsonObject.getAsJsonArray("values").map { itemFilter(it) })
        ingredient.isJsonObject && ingredient.asJsonObject.has("tag") -> itemRef("#" + ingredient.asJsonObject.get("tag").asString)
        ingredient.isJsonObject && ingredient.asJsonObject.has("item") -> mapOf("item" to Ids.rename(ingredient.asJsonObject.get("item").asString))
        else -> throw Unsupported("item ingredient form ${ingredient.toString().take(60)}")
    }

    private fun itemRef(value: String): Map<String, Any?> {
        if (!value.startsWith("#")) return mapOf("item" to Ids.rename(value))
        val tag = Ids.rename(value.removePrefix("#"))
        referencedTags += tag
        return mapOf("item-tag" to tag)
    }

    private fun chemicalInput(json: JsonObject): Map<String, Any?> {
        val amount = json.get("amount")?.asLong ?: 1
        return when {
            json.has("chemical") -> mapOf("type" to Ids.rename(json.get("chemical").asString), "amount" to amount)
            json.has("tag") -> mapOf("tag" to Ids.rename(json.get("tag").asString), "amount" to amount)
            else -> throw Unsupported("chemical input form ${json.toString().take(60)}")
        }
    }

    /** A per-tick chemical cost: an exact chemical (costs are resources, not tags). */
    private fun chemicalCost(json: JsonObject): Map<String, Any?> {
        val input = chemicalInput(json)
        val tag = input["tag"] as String? ?: return input
        return mapOf("type" to firstChemical(tag), "amount" to input["amount"])
    }

    /** Fluids are resources of the Mekanized fluid category (Phase 3): a fluid tag names one fluid. */
    private fun fluidInput(json: JsonObject): Map<String, Any?> {
        val ingredient = json.get("ingredient") ?: throw Unsupported("fluid input without ingredient")
        val ref = if (ingredient.isJsonPrimitive) ingredient.asString else ingredient.asJsonObject.get("tag")?.asString?.let { "#$it" }
            ?: ingredient.asJsonObject.get("fluid")?.asString ?: throw Unsupported("fluid ingredient form ${ingredient.toString().take(60)}")
        return mapOf("type" to fluidType(ref), "amount" to (json.get("amount")?.asLong ?: 1))
    }

    /** Set while converting an evaporating recipe: its outputs are fluids. */
    private var evaporating = false

    private fun output(field: String, json: JsonObject): Map<String, Any?> {
        val id = Ids.rename(json.get("id")?.asString ?: throw Unsupported("output without id"))
        return when {
            json.has("count") || field in setOf("main_output", "item_output", "secondary_output") || !json.has("amount") ->
                mapOf("item" to id, "amount" to (json.get("count")?.asInt ?: 1))
            field == "fluid_output" -> mapOf("type" to fluidType(id), "amount" to json.get("amount").asLong)
            // Evaporating makes fluids (brine, liquid lithium), under an "output" field like chemicals'.
            evaporating -> mapOf("type" to fluidType(id), "amount" to json.get("amount").asLong)
            else -> mapOf("type" to id, "amount" to json.get("amount").asLong)
        }
    }

    // -- crafting (CraftEngine)

    private fun craftEngine(id: String, group: String, recipe: Map<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val result = (recipe["result"] as Map<String, Any?>)["id"] as String
        // Each plan phase enables the files whose items all exist: the result (vanilla or already
        // generated) and every Mekanized ingredient.
        val ingredients = mekanizedIds(recipe) + listOf(result).filter { it.startsWith("mekanized:") }
        val known = mutableSetOf<String>()
        var file: String? = null
        for ((prefix, ids) in phases) {
            known += ids
            if (ingredients.all { it in known }) {
                file = "${prefix}_$group"
                break
            }
        }
        if (file == null) {
            // Its result or an ingredient is content Mekanized does not have: nothing would install it.
            report.skip("crafting recipe for content Mekanized does not have", id)
            return
        }
        craftEngineRecipes["$file|$id"] = recipe.toMutableMap()
        report.count("crafting/smelting recipes")
    }

    /** Every `mekanized:` item id in a recipe's ingredients (tags excluded). */
    private fun mekanizedIds(value: Any?): List<String> = when (value) {
        is String -> if (value.startsWith("mekanized:")) listOf(value) else listOf()
        is Map<*, *> -> value.filterKeys { it != "result" }.values.flatMap { mekanizedIds(it) }
        is List<*> -> value.flatMap { mekanizedIds(it) }
        else -> listOf()
    }

    private fun shaped(json: JsonObject): Map<String, Any?> = linkedMapOf(
        "type" to "shaped",
        "pattern" to json.getAsJsonArray("pattern").map { it.asString },
        "ingredients" to json.getAsJsonObject("key").entrySet().associate { (key, value) -> key to craftIngredient(value) },
        "result" to result(json.getAsJsonObject("result")),
    )

    private fun shapeless(json: JsonObject): Map<String, Any?> = linkedMapOf(
        "type" to "shapeless",
        "ingredients" to json.getAsJsonArray("ingredients").map { craftIngredient(it) },
        "result" to result(json.getAsJsonObject("result")),
    )

    private fun cooking(type: String, json: JsonObject): Map<String, Any?> = linkedMapOf(
        "type" to type,
        "experience" to (json.get("experience")?.asDouble ?: 0.0),
        "time" to (json.get("cookingtime")?.asInt ?: if (type == "blasting") 100 else 200),
        "ingredient" to craftIngredient(json.get("ingredient")),
        "result" to result(json.getAsJsonObject("result")),
    )

    /**
     * A `#c:` tag becomes a list of its vanilla members plus the tag itself while Mekanized items are
     * in it (CraftEngine knows those from the items' tags); vanilla items are not in `c:` tags on Paper.
     */
    private fun craftIngredient(value: JsonElement): Any {
        val ingredient = craftIngredientRaw(value)
        if (ingredient !is String || !ingredient.startsWith("#") || ingredient.startsWith("#minecraft:")) return ingredient
        val tag = ingredient.removePrefix("#")
        if (tag.startsWith("c:")) referencedTags += tag
        // CraftEngine knows a tag through the Mekanized items declaring it; vanilla members are listed.
        val expanded = vanillaMembers(tag, mutableSetOf()).distinct() + (if (hasMekanizedItems(tag, mutableSetOf())) listOf(ingredient) else listOf())
        return when (expanded.size) {
            0 -> ingredient
            1 -> expanded.first()
            else -> expanded
        }
    }

    /** Vanilla items and `#minecraft:` tags in [tag], following `c:` / `mekanized:` includes. */
    private fun vanillaMembers(tag: String, visited: MutableSet<String>): List<String> {
        if (!visited.add(tag)) return listOf()
        val direct = itemTags[tag].orEmpty().flatMap { member ->
            when {
                member.startsWith("#minecraft:") -> listOf(member)
                member.startsWith("#") -> vanillaMembers(member.removePrefix("#"), visited)
                member.startsWith("minecraft:") -> listOf(member)
                else -> listOf()
            }
        }
        return VanillaAliases.of(tag) + direct
    }

    private fun hasMekanizedItems(tag: String, visited: MutableSet<String>): Boolean {
        if (!visited.add(tag)) return false
        return itemTags[tag].orEmpty().any { member ->
            if (member.startsWith("#")) hasMekanizedItems(member.removePrefix("#"), visited) else member.startsWith("mekanized:")
        }
    }

    private fun craftIngredientRaw(value: JsonElement): Any = when {
        value.isJsonPrimitive -> Ids.rename(value.asString)
        value.isJsonArray -> value.asJsonArray.map { craftIngredient(it) }
        value.isJsonObject && value.asJsonObject.get("type")?.asString == "neoforge:or" ->
            value.asJsonObject.getAsJsonArray("values").map { craftIngredient(it) }
        value.isJsonObject && value.asJsonObject.has("tag") -> "#" + Ids.rename(value.asJsonObject.get("tag").asString)
        value.isJsonObject && value.asJsonObject.has("item") -> Ids.rename(value.asJsonObject.get("item").asString)
        else -> throw Unsupported("crafting ingredient form ${value.toString().take(60)}")
    }

    private fun result(json: JsonObject): Map<String, Any?> {
        if (json.has("components")) throw Unsupported("crafting result with item components")
        return linkedMapOf("id" to Ids.rename(json.get("id").asString), "count" to (json.get("count")?.asInt ?: 1))
    }

    // -- output

    private fun writeOutputs() {
        out.resolve("fluids.txt").writeText(fluids.joinToString("\n", postfix = "\n"))
        // Bucket slots of fluid machines and tanks (E5): only vanilla buckets, Mekanism's need a mod-side model.
        val buckets = linkedMapOf<String, Any?>()
        for (fluid in listOf("water", "lava")) {
            buckets["mekanized:bucket_filling/$fluid"] = linkedMapOf("type" to "mekanized:bucket_filling",
                "inputs" to listOf(mapOf("item" to "minecraft:${fluid}_bucket", "amount" to 1)),
                "outputs" to listOf(mapOf("type" to "mekanized:fluid/$fluid", "amount" to 1000), mapOf("item" to "minecraft:bucket", "amount" to 1)))
            buckets["mekanized:bucket_draining/$fluid"] = linkedMapOf("type" to "mekanized:bucket_draining",
                "inputs" to listOf(mapOf("item" to "minecraft:bucket", "amount" to 1), mapOf("type" to "mekanized:fluid/$fluid", "amount" to 1000)),
                "outputs" to listOf(mapOf("item" to "minecraft:${fluid}_bucket", "amount" to 1)))
        }
        writeYaml(out.resolve("definitions/recipes_buckets.yml"),
            "Mekanized bucket slots: a full bucket empties into the tank, an empty one is filled from it (1000 mB).\n" +
                    "Edit freely: updates replace this file only while it is unedited.", linkedMapOf("recipes" to buckets))
        machineRecipes.entries.groupBy { it.key.substringBefore("|") }.forEach { (kind, entries) ->
            writeYaml(out.resolve("definitions/recipes_$kind.yml"),
                "Mekanized $kind recipes, generated from Mekanism (MIT, see LICENSE-MEKANISM.txt).\n" +
                        "Edit freely: updates replace this file only while it is unedited; delete it to get the defaults back.\n" +
                        "Durations are ticks; energy is Joules per tick. Phase ${ContentPhases.planPhase(kind)} of MEKANIZED_PLAN.md.",
                linkedMapOf("recipes" to entries.associate { it.key.substringAfter("|") to it.value }))
        }
        craftEngineRecipes.entries.groupBy { it.key.substringBefore("|") }.forEach { (group, entries) ->
            writeYaml(out.resolve("pack/configuration/recipes_$group.yml"),
                "Mekanized $group recipes for CraftEngine, generated from Mekanism (MIT, see LICENSE-MEKANISM.txt).",
                linkedMapOf("recipes" to entries.associate { it.key.substringAfter("|") to it.value }))
        }
    }
}
