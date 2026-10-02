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

    /** Writes `models/block/cable/<name>/center.json` and one per direction; returns the model paths. */
    fun write(modelsRoot: File, name: String, texture: String): Map<String, String> {
        val textures = mapOf("center" to "mekanized:block/models/multipart/$texture",
            "side" to "mekanized:block/models/multipart/${texture}_vertical", "particle" to "mekanized:block/models/multipart/$texture")
        val paths = linkedMapOf<String, String>()
        paths["center"] = model(modelsRoot, name, "center", textures, listOf(cube(5, 5, 5, 11, 11, 11, null)))
        for (direction in DIRECTIONS) paths[direction] = model(modelsRoot, name, direction, textures, listOf(arm(direction)))
        return paths
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
    private fun arm(direction: String): Map<String, Any> = when (direction) {
        "north" -> cube(5, 5, 0, 11, 11, 5, "z")
        "south" -> cube(5, 5, 11, 11, 11, 16, "z")
        "west" -> cube(0, 5, 5, 5, 11, 11, "x")
        "east" -> cube(11, 5, 5, 16, 11, 11, "x")
        "down" -> cube(5, 0, 5, 11, 5, 11, "y")
        else -> cube(5, 11, 5, 11, 16, 11, "y")
    }

    /**
     * A box with all six faces. For an arm ([axis] = its length axis), faces along the length use the
     * side strip, turned so the strip follows the arm; the end caps and the center use the center square.
     */
    private fun cube(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, axis: String?): Map<String, Any> {
        val faces = linkedMapOf<String, Any>()
        for (face in listOf("north", "south", "east", "west", "up", "down")) {
            val faceAxis = when (face) { "north", "south" -> "z"; "east", "west" -> "x"; else -> "y" }
            if (axis == null || faceAxis == axis) {
                faces[face] = mapOf("uv" to listOf(5, 5, 11, 11), "texture" to "#center")
                continue
            }
            // u is x on north/south and up/down faces, z on east/west faces; v is y, or z on up/down.
            val uAxis = when (face) { "east", "west" -> "z"; else -> "x" }
            val alongU = uAxis == axis
            faces[face] = if (alongU) mapOf("uv" to listOf(5, 0, 11, 5), "rotation" to 90, "texture" to "#side")
            else mapOf("uv" to listOf(5, 0, 11, 5), "texture" to "#side")
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
        val parent = json.get("parent")?.asString ?: return true
        return isFullCube(modelsRoot, parent, depth + 1)
    }
}
