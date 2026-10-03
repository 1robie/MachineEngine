import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File

/**
 * Vanilla JSON models for Mekanism's transmitters, whose own models are OBJ (NeoForge only): a center
 * piece and one arm per direction, built from Mekanism's 32x32 transmitter textures (center square
 * at UV 5..11; the `_vertical` texture's strip for the arms). The block shows the center; the arms
 * are shown by display entities on the connected sides.
 */
internal object CableModels {
    val DIRECTIONS = listOf("north", "south", "east", "west", "up", "down")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * Writes `models/block/cable/<name>/center.json`, one arm per direction, and per direction the
     * arms of pull and push sides (`<direction>_pull`: a funnel widening to the block, like a hopper;
     * `<direction>_push`: a nozzle narrowing to it), as Mekanism draws them. Returns the model paths.
     *
     * @param low the low edge of the square section, in pixels: 5 for small transmitters (cables,
     *            tubes: 32x32 textures, center at UV 5..11), 4 for large ones (pipes: 16x16, 4..12)
     */
    fun write(modelsRoot: File, name: String, texture: String, low: Int = 5): Map<String, String> {
        val high = 16 - low
        val textures = mapOf("center" to "mekanized:block/models/multipart/$texture",
            "side" to "mekanized:block/models/multipart/${texture}_vertical", "particle" to "mekanized:block/models/multipart/$texture")
        val paths = linkedMapOf<String, String>()
        paths["center"] = model(modelsRoot, name, "center", textures, listOf(cube(low, low, low, high, high, high, null, low)))
        for (direction in DIRECTIONS) {
            paths[direction] = model(modelsRoot, name, direction, textures, listOf(arm(direction, low)))
            // Sizes along the arm (from the block's edge) and across it, from Mekanism's OBJ transmitters.
            val length = low
            paths["${direction}_pull"] = model(modelsRoot, name, "${direction}_pull", textures, listOf(
                along(direction, 0, 2, low - 1, high + 1, low), along(direction, 2, length, low, high, low)))
            paths["${direction}_push"] = model(modelsRoot, name, "${direction}_push", textures, listOf(
                along(direction, length - 2, length, low, high, low), along(direction, 1, length - 2, low + 1, high - 1, low),
                along(direction, 0, 1, low + 2, high - 2, low)))
        }
        return paths
    }

    /**
     * A box of an arm toward [direction]: from [from] to [to] pixels away from the block's edge on that
     * side, and [across1]..[across2] on the two other axes.
     */
    private fun along(direction: String, from: Int, to: Int, across1: Int, across2: Int, low: Int): Map<String, Any> {
        val a = across1
        val b = across2
        return when (direction) {
            "north" -> cube(a, a, from, b, b, to, "z", low)
            "south" -> cube(a, a, 16 - to, b, b, 16 - from, "z", low)
            "west" -> cube(from, a, a, to, b, b, "x", low)
            "east" -> cube(16 - to, a, a, 16 - from, b, b, "x", low)
            "down" -> cube(a, from, a, b, to, b, "y", low)
            else -> cube(a, 16 - to, a, b, 16 - from, b, "y", low)
        }
    }

    private fun model(root: File, name: String, part: String, textures: Map<String, String>, elements: List<Map<String, Any>>): String {
        val file = root.resolve("block/cable/$name/$part.json")
        file.parentFile.mkdirs()
        val model = linkedMapOf<String, Any>(
            "parent" to "minecraft:block/block",
            "textures" to textures,
            "elements" to elements,
            // Shown at 1:1 by item display entities (display context "none").
            "display" to mapOf("none" to mapOf("rotation" to listOf(0, 0, 0), "translation" to listOf(0, 0, 0), "scale" to listOf(1, 1, 1))),
        )
        file.writeText(gson.toJson(model))
        return "mekanized:block/cable/$name/$part"
    }

    /** An arm from the center piece to the block's edge in [direction]. */
    private fun arm(direction: String, l: Int): Map<String, Any> {
        val h = 16 - l
        return when (direction) {
            "north" -> cube(l, l, 0, h, h, l, "z", l)
            "south" -> cube(l, l, h, h, h, 16, "z", l)
            "west" -> cube(0, l, l, l, h, h, "x", l)
            "east" -> cube(h, l, l, 16, h, h, "x", l)
            "down" -> cube(l, 0, l, h, l, h, "y", l)
            else -> cube(l, h, l, h, 16, h, "y", l)
        }
    }

    /**
     * A box with all six faces. For an arm ([axis] = its length axis), faces along the length use the
     * side strip, turned so the strip follows the arm; the end caps and the center use the center square.
     */
    private fun cube(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, axis: String?, low: Int): Map<String, Any> {
        val high = 16 - low
        // Mekanism's OBJ UVs. Small (32x32 textures): the center square 5..11, the side strip [5,0,11,5].
        // Large (16x16): the whole center texture, the side texture across its width, along rows 4..12.
        val large = low == 4
        val centerUv = if (large) listOf(0, 0, 16, 16) else listOf(low, low, high, high)
        val sideUv = if (large) listOf(0, 4, 16, 12) else listOf(low, 0, high, low)
        val faces = linkedMapOf<String, Any>()
        for (face in listOf("north", "south", "east", "west", "up", "down")) {
            val faceAxis = when (face) { "north", "south" -> "z"; "east", "west" -> "x"; else -> "y" }
            if (axis == null || faceAxis == axis) {
                faces[face] = mapOf("uv" to centerUv, "texture" to "#center")
                continue
            }
            // u is x on north/south and up/down faces, z on east/west faces; v is y, or z on up/down.
            val uAxis = when (face) { "east", "west" -> "z"; else -> "x" }
            val alongU = uAxis == axis
            faces[face] = if (alongU) mapOf("uv" to sideUv, "rotation" to 90, "texture" to "#side")
            else mapOf("uv" to sideUv, "texture" to "#side")
        }
        return linkedMapOf("from" to listOf(x1, y1, z1), "to" to listOf(x2, y2, z2), "faces" to faces)
    }

    /**
     * Whether a model is a full cube (machines that are not get a non-occluding block state, so the
     * client does not hide the faces of the blocks around them): a vanilla cube parent, or an element
     * covering the whole block.
     */
    fun isFullCube(modelsRoot: File, modelRef: String, depth: Int = 0): Boolean {
        if (depth > 8) return true
        val ref = if (":" in modelRef) modelRef else "minecraft:$modelRef"
        if (ref.startsWith("minecraft:")) {
            val path = ref.removePrefix("minecraft:")
            return path.startsWith("block/cube") || path.startsWith("block/orientable")
        }
        val file = modelsRoot.resolve(ref.substringAfter(":") + ".json")
        if (!file.isFile) return true
        val json = JsonParser.parseString(file.readText()).asJsonObject
        val elements = json.getAsJsonArray("elements")
        if (elements != null) {
            return elements.any { element ->
                val from = element.asJsonObject.getAsJsonArray("from").map { it.asDouble }
                val to = element.asJsonObject.getAsJsonArray("to").map { it.asDouble }
                from.all { it <= 0.0 } && to.all { it >= 16.0 }
            }
        }
        // No parent and no elements: nothing drawn (Mekanism's "nothing", rendered in code).
        val parent = json.get("parent")?.asString ?: return false
        return isFullCube(modelsRoot, parent, depth + 1)
    }
}
