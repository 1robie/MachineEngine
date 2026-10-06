import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Energy cubes: Mekanism's loader model split into a frame, a port per side, and a glowing core drawn
 * in code. [assets] is the generated `assets/mekanized` folder (models and textures are written there).
 */
internal class EnergyCube(private val source: MekanismSource, assets: File) {
    private val modelsRoot = assets.resolve("models")
    private val texturesRoot = assets.resolve("textures")

    /** The tier of an energy cube block, null for other blocks. */
    fun tier(name: String): String? = TIER_COLORS.keys.firstOrNull { name == "${it}_energy_cube" }

    /**
     * The energy cube split as Mekanism's loader draws it: `<tier>/frame` (the display item of the block)
     * and `<tier>/port_<side>` (connector, port and LEDs of a side, model sides as seen facing north).
     * False if Mekanism's models are not there.
     */
    fun parts(tier: String, name: String, items: MutableMap<String, Any?>): Boolean {
        val root = source.resolve("src/main/resources/assets/mekanism/models/block/energy_cube")
        val base = root.resolve("base.json").takeIf { it.isFile } ?: return false
        val json = JsonParser.parseString(Ids.renameInText(base.readText())).asJsonObject
        val textures = json.getAsJsonObject("textures")?.deepCopy() ?: JsonObject()
        root.resolve("$tier.json").takeIf { it.isFile }?.let { file ->
            JsonParser.parseString(Ids.renameInText(file.readText())).asJsonObject.getAsJsonObject("textures")
                ?.entrySet()?.forEach { (key, value) -> textures.add(key, value) }
        }
        fun write(part: String, elements: JsonArray): String {
            val model = JsonObject()
            model.addProperty("parent", "minecraft:block/block")
            model.add("textures", textures)
            model.add("elements", elements)
            val file = modelsRoot.resolve("block/energy_cube/$tier/$part.json")
            file.parentFile.mkdirs()
            file.writeText(prettyGson.toJson(model))
            return "mekanized:block/energy_cube/$tier/$part"
        }
        items["mekanized:display/$name"] = linkedMapOf<String, Any?>("model" to write("frame", json.getAsJsonArray("frame") ?: return false))
        val ports = json.getAsJsonObject("ports") ?: return false
        val leds = json.getAsJsonObject("leds")
        for (side in listOf("front", "back", "left", "right", "top", "bottom")) {
            // Mekanism's left and right are as seen facing the front; MachineEngine's from behind.
            val mekanism = when (side) { "left" -> "right"; "right" -> "left"; else -> side }
            val elements = JsonArray()
            ports.getAsJsonArray(mekanism)?.forEach { elements.add(it) }
            leds?.getAsJsonArray(mekanism)?.forEach { elements.add(it) }
            items["mekanized:display/$name/port/$side"] = linkedMapOf<String, Any?>("model" to write("port_$side", elements))
        }
        return true
    }

    /**
     * The core of a tier: Mekanism's grey `render/energy_core.png` tinted with the tier's color, as a
     * cube turned 45 degrees, in [CORE_LEVELS] sizes (items `display/energy_core/<tier>/<level>`).
     */
    fun core(tier: String, items: MutableMap<String, Any?>) {
        val texture = texturesRoot.resolve("block/energy_core/$tier.png")
        if (!texture.isFile) {
            val grey = ImageIO.read(source.resolve("src/main/resources/assets/mekanism/render/energy_core.png")) ?: return
            val color = TIER_COLORS.getValue(tier)
            val tinted = BufferedImage(grey.width, grey.height, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until grey.width) for (y in 0 until grey.height) {
                val argb = grey.getRGB(x, y)
                val level = (argb shr 16) and 0xFF
                fun channel(shift: Int) = (((color shr shift) and 0xFF) * level / 255) shl shift
                tinted.setRGB(x, y, (argb and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0))
            }
            texture.parentFile.mkdirs()
            ImageIO.write(tinted, "png", texture)
        }
        for (level in 1..CORE_LEVELS) {
            val half = 1.0 + level * 1.0 // 4 to 10 pixels wide
            val face = mapOf("uv" to listOf(0, 0, 16, 16), "texture" to "#core")
            val model = linkedMapOf<String, Any>(
                "parent" to "minecraft:block/block",
                "textures" to mapOf("core" to "mekanized:block/energy_core/$tier", "particle" to "mekanized:block/energy_core/$tier"),
                "elements" to listOf(linkedMapOf(
                    "from" to listOf(8 - half, 8 - half, 8 - half), "to" to listOf(8 + half, 8 + half, 8 + half),
                    "rotation" to mapOf("angle" to 45, "axis" to "y", "origin" to listOf(8, 8, 8)),
                    "shade" to false,
                    "faces" to listOf("north", "south", "east", "west", "up", "down").associateWith { face })))
            val file = modelsRoot.resolve("block/energy_core/$tier/$level.json")
            file.parentFile.mkdirs()
            file.writeText(prettyGson.toJson(model))
            items["mekanized:display/energy_core/$tier/$level"] = linkedMapOf<String, Any?>("model" to "mekanized:block/energy_core/$tier/$level")
        }
    }

    companion object {
        /** Sizes of the core, growing with the charge. */
        const val CORE_LEVELS = 4

        /** Mekanism's tier colors (BaseTier), which tint the core. */
        private val TIER_COLORS = mapOf("basic" to 0x55FF55, "advanced" to 0xAA0000, "elite" to 0x0000AA, "ultimate" to 0xAA00AA, "creative" to 0x202020)
    }
}
