import com.google.gson.JsonParser
import java.io.File

/**
 * Display-rendered blocks: blocks that are not a full cube are drawn by a display entity (turned to
 * the facing, switching to the active model) over one empty, non-occluding state. Non-occluding states
 * are scarce (about 117 slots), so blocks that break with the same particles share one.
 *
 * @param modelsRoot the generated models folder (empty models are written there)
 */
internal class DisplayStates(private val modelsRoot: File) {
    /** Shared empty models of display-rendered blocks, by family. */
    private val families = mutableMapOf<String, String>()

    /** The models of the "north, inactive" and "north, active" appearances (active is null if none). */
    fun displayModels(states: BlockState): Pair<String, String?> {
        val appearances = (states as? VariantStates)?.appearances ?: return states.firstModel() to null
        fun model(name: String) = appearances[name]?.path
        val inactive = model("facing_north_active_false") ?: model("facing_north") ?: states.firstModel()
        return inactive to model("facing_north_active_true")
    }

    /** The single appearance shared by the four tiers of a factory kind (and the basic tier's particles). */
    fun factory(states: BlockState, kind: String): BlockState =
        singleAppearance(states, emptyModel("${kind}_factory", "mekanized:block/factory/$kind/basic"), "mekanized_${kind}_factory")

    /** Blocks players walk on get a thin state with no collision instead of a full leaves block. */
    fun thin(states: BlockState, name: String, inactive: String, autoState: String): BlockState =
        singleAppearance(states, emptyModel(name, inactive), autoState = autoState)

    /**
     * Non-occluding states are scarce (CraftEngine's leaves): blocks that break with the same particles,
     * and the tiers of a block, share one state.
     */
    fun shared(states: BlockState, name: String, inactive: String): BlockState {
        val family = TIERED.firstOrNull { suffix -> (ContentPhases.tiers + "creative").any { name == "${it}_$suffix" } }
            ?: ("p_" + (particle(inactive) ?: name).substringAfter(":").replace(Regex("[^a-z0-9_]"), "_"))
        val model = families.getOrPut(family) { emptyModel("shared/$family", inactive) }
        return singleAppearance(states, model, "mekanized_display_$family")
    }

    /** The same properties, every variant shown by one empty, non-occluding appearance. */
    fun singleAppearance(states: BlockState, emptyModel: String, sharedId: String? = null, autoState: String = "leaves"): BlockState {
        // The model is empty, so leaf tint does not matter: any leaves state (tintable or not; CraftEngine
        // has 65 tintable ones only). Blocks with the same sharedId share one state.
        val state: Any = if (sharedId == null) autoState else mapOf("type" to autoState, "id" to sharedId)
        val appearance = Appearance(state, emptyModel)
        val nested = states as? VariantStates ?: return SingleState(appearance)
        // The display entity shows whether the machine works: an active property would only make block updates.
        val properties = nested.properties.filterKeys { it != "active" }
        if (properties.isEmpty()) return SingleState(appearance)
        val variants = nested.variants.keys.filter { "active=true" !in it }
            .associate { key -> key.split(",").filter { it != "active=false" }.joinToString(",") to "default" }
        return VariantStates(properties, linkedMapOf("default" to appearance), variants)
    }

    /** Each facing's blockstate rotation as "facing=x/y,..." (inactive appearances); null without a facing property. */
    fun facingRotations(states: BlockState): String? {
        val appearances = (states as? VariantStates)?.appearances ?: return null
        val rotations = mutableListOf<String>()
        for (facing in listOf("north", "south", "east", "west", "up", "down")) {
            val appearance = appearances["facing_${facing}_active_false"] ?: appearances["facing_${facing}_open_false"]
                ?: appearances["facing_$facing"] ?: continue
            rotations += "$facing=${appearance.x ?: 0}/${appearance.y ?: 0}"
        }
        return if (rotations.isEmpty()) null else rotations.joinToString(",")
    }

    /** The y rotation of the "north, inactive" appearance, in degrees (0 to 270). */
    fun northRotation(states: BlockState): Int {
        val appearances = (states as? VariantStates)?.appearances ?: return 0
        val north = appearances["facing_north_active_false"] ?: appearances["facing_north"] ?: return 0
        return north.y ?: 0
    }

    /** A model with no faces (the display entity draws the block) but the real model's breaking particles. */
    fun emptyModel(name: String, realModel: String): String {
        val file = modelsRoot.resolve("block/display_empty/$name.json")
        file.parentFile.mkdirs()
        file.writeText(prettyGson.toJson(mapOf(
            "textures" to mapOf("particle" to (particle(realModel) ?: "minecraft:block/iron_block")),
            "elements" to listOf<Any>())))
        return "mekanized:block/display_empty/$name"
    }

    private fun particle(model: String, depth: Int = 0): String? {
        if (depth > 8 || !model.startsWith("mekanized:")) return null
        val file = modelsRoot.resolve(model.substringAfter(":") + ".json")
        if (!file.isFile) return null
        val json = JsonParser.parseString(file.readText()).asJsonObject
        val textures = json.getAsJsonObject("textures")
        val direct = textures?.get("particle")?.asString
        if (direct != null && !direct.startsWith("#")) return direct
        textures?.entrySet()?.firstOrNull { !it.value.asString.startsWith("#") }?.let { return it.value.asString }
        return json.get("parent")?.asString?.let { particle(it, depth + 1) }
    }

    private companion object {
        /** Tiered display-rendered blocks: every tier shares the basic tier's state. */
        val TIERED = listOf("energy_cube", "chemical_tank", "fluid_tank")
    }
}
