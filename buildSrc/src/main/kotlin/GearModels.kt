import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlin.math.abs

/**
 * Mekanism draws its gear (jetpack, atomic disassembler, free runners, scuba set, flamethrower) with
 * Java entity models: boxes on a texture laid out the entity way. This turns them into item models a
 * vanilla client draws: each box an element, its UVs from the box layout, its rotation the closest
 * one a block model allows (one axis, 22.5 degree steps).
 *
 * <p>The item renderers place the model with `translate(0.5, 0.5, 0.5)` then a half turn around Z
 * (entity models have y down): a model point (x, y, z) in pixels is (8 - x, 8 - y, 8 + z) here.</p>
 */
internal class GearModels(private val source: File, private val assets: File, private val report: Report) {
    private val javaDir = source.resolve("src/main/java/mekanism/client/model")

    /** A gear item: its Java model, the model it extends (for inherited parts), its texture, and the extra y the renderer adds. */
    data class Gear(val item: String, val model: String, val parent: String?, val texture: String, val shiftY: Double = 0.0)

    val gear = listOf(
        Gear("atomic_disassembler", "ModelAtomicDisassembler", null, "atomic_disassembler"),
        Gear("flamethrower", "ModelFlamethrower", null, "flamethrower"),
        Gear("jetpack", "ModelJetpack", null, "jetpack"),
        Gear("jetpack_armored", "ModelArmoredJetpack", "ModelJetpack", "jetpack"),
        Gear("free_runners", "ModelFreeRunners", null, "free_runners", 16.0),
        Gear("free_runners_armored", "ModelArmoredFreeRunners", "ModelFreeRunners", "free_runners", 16.0),
        Gear("scuba_tank", "ModelScubaTank", null, "scuba_set"),
        Gear("scuba_mask", "ModelScubaMask", null, "scuba_set"),
    )

    private data class Box(val u: Double, val v: Double, val x: Double, val y: Double, val z: Double,
                           val w: Double, val h: Double, val d: Double, val inflate: Double, val mirror: Boolean)

    private data class Part(val boxes: List<Box>, val offset: DoubleArray, val rotation: DoubleArray)

    /** Writes `models/item/<item>.json` for every gear item whose Java model reads; returns the converted items. */
    fun write(): Set<String> {
        val written = mutableSetOf<String>()
        for (item in gear) {
            try {
                if (convert(item)) written += item.item else report.skip("gear model not converted", item.item)
            } catch (e: RuntimeException) {
                report.skip("gear model not converted (${e.message})", item.item)
            }
        }
        return written
    }

    private fun convert(gear: Gear): Boolean {
        val file = javaDir.resolve("${gear.model}.java").takeIf { it.isFile } ?: return false
        val texts = listOfNotNull(file.readText(), gear.parent?.let { javaDir.resolve("$it.java").takeIf { f -> f.isFile }?.readText() })
        val helpers = texts.flatMap { helpers(it).entries }.associate { it.key to it.value }
        val constants = mutableMapOf<String, Part>()
        // The parent's constants first: the model's own replace them.
        for (text in texts.reversed()) constants += constants(text, helpers)
        val layer = Regex("""createLayerDefinition\((\d+),\s*(\d+),\s*([^;]*?)\);""").find(texts.first()) ?: return false
        val (tw, th) = layer.groupValues[1].toDouble() to layer.groupValues[2].toDouble()
        val names = layer.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() && constants.containsKey(it) }
        val parts = names.map { constants.getValue(it) }
        // Parts Mekanism draws see-through (glass, wings, blades): their alpha, by part name.
        val alphas = alphas(texts)
        if (parts.isEmpty()) return false
        val elements = JsonArray()
        val coords = mutableListOf<Double>()
        for ((name, part) in names.zip(parts)) for (box in part.boxes) {
            val alpha = alphas[name]
            val element = element(box, part, tw, th, gear.shiftY, if (alpha == null) "#0" else "#a$alpha")
            elements.add(element)
            for (key in listOf("from", "to")) element.getAsJsonArray(key).forEach { coords += it.asDouble }
        }
        // Block models keep elements within -16..32: shrink around the middle if needed, and grow the display back.
        val reach = coords.maxOf { abs(it - 8) }
        val scale = if (reach > 24) 24 / reach else 1.0
        if (scale < 1) for (element in elements) {
            val obj = element.asJsonObject
            for (key in listOf("from", "to")) obj.add(key, shrink(obj.getAsJsonArray(key), scale))
            obj.getAsJsonObject("rotation")?.let { it.add("origin", shrink(it.getAsJsonArray("origin"), scale)) }
        }
        val model = JsonObject()
        val textures = JsonObject()
        textures.addProperty("0", "mekanized:render/${gear.texture}")
        for (alpha in alphas.values.toSet()) {
            textures.addProperty("a$alpha", "mekanized:render/${gear.texture}_a$alpha")
            translucent(gear.texture, alpha)
        }
        textures.addProperty("particle", "mekanized:render/${gear.texture}")
        model.add("textures", textures)
        model.add("elements", elements)
        // Mekanism's display transforms for the item (its base model), grown back if shrunk.
        val base = assets.resolve("models/item/${gear.item}.json")
        val display = if (base.isFile) JsonParser.parseString(base.readText()).asJsonObject.getAsJsonObject("display") else null
        if (display != null) {
            if (scale < 1) for ((_, transform) in display.entrySet()) {
                val s = transform.asJsonObject.getAsJsonArray("scale") ?: continue
                transform.asJsonObject.add("scale", JsonArray().apply { s.forEach { add(minOf(4.0, it.asDouble / scale)) } })
            }
            model.add("display", display)
        }
        base.parentFile.mkdirs()
        base.writeText(GsonBuilder().setPrettyPrinting().create().toJson(model))
        // The entity texture, where the item model looks for it.
        val texture = source.resolve("src/main/resources/assets/mekanism/render/${gear.texture}.png")
        val target = assets.resolve("textures/render/${gear.texture}.png")
        if (texture.isFile && !target.isFile) {
            target.parentFile.mkdirs()
            texture.copyTo(target)
        }
        report.count("gear models converted from Java")
        return true
    }

    /** `list = getRenderableParts(root, A, B)` drawn by `collectParts(list, ..., 0xAARRGGBB, ...)`: A and B -> AA. */
    private fun alphas(texts: List<String>): Map<String, Int> {
        val lists = mutableMapOf<String, List<String>>()
        for (text in texts) for (match in Regex("""(\w+)\s*=\s*getRenderableParts\(root,\s*([^;]*)\);""").findAll(text)) {
            lists[match.groupValues[1]] = match.groupValues[2].split(",").map { it.trim() }
        }
        val alphas = mutableMapOf<String, Int>()
        for (text in texts) for (match in Regex("""collectParts\((\w+),[^;]*?,\s*0x([0-9A-Fa-f]{2})[0-9A-Fa-f]{6}""").findAll(text)) {
            val alpha = match.groupValues[2].toInt(16)
            if (alpha < 255) lists[match.groupValues[1]]?.forEach { alphas[it] = alpha }
        }
        return alphas
    }

    /** `textures/render/<texture>_a<alpha>.png`: the texture at that opacity. */
    private fun translucent(texture: String, alpha: Int) {
        val target = assets.resolve("textures/render/${texture}_a$alpha.png")
        if (target.isFile) return
        val image = javax.imageio.ImageIO.read(source.resolve("src/main/resources/assets/mekanism/render/$texture.png")) ?: return
        val result = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until image.width) for (y in 0 until image.height) {
            val argb = image.getRGB(x, y)
            val a = ((argb ushr 24) * alpha / 255) shl 24
            result.setRGB(x, y, a or (argb and 0xFFFFFF))
        }
        target.parentFile.mkdirs()
        javax.imageio.ImageIO.write(result, "png", target)
    }

    private fun shrink(array: JsonArray, scale: Double) = JsonArray().apply { array.forEach { add(8 + (it.asDouble - 8) * scale) } }

    /** One box as a block model element: corners, rotation about the part's pivot, and the entity-layout UVs. */
    private fun element(box: Box, part: Part, tw: Double, th: Double, shiftY: Double, texture: String): JsonObject {
        val o = part.offset
        val x1 = o[0] + box.x - box.inflate
        val x2 = o[0] + box.x + box.w + box.inflate
        val y1 = o[1] + box.y - box.inflate
        val y2 = o[1] + box.y + box.h + box.inflate
        val z1 = o[2] + box.z - box.inflate
        val z2 = o[2] + box.z + box.d + box.inflate
        val element = JsonObject()
        element.add("from", array(8 - x2, 8 + shiftY - y2, 8 + z1))
        element.add("to", array(8 - x1, 8 + shiftY - y1, 8 + z2))
        // The largest of the part's rotations, the half turn around Z flipping x and y; to a block model step.
        val (axis, radians) = listOf("x" to -part.rotation[0], "y" to -part.rotation[1], "z" to part.rotation[2]).maxBy { abs(it.second) }
        val degrees = Math.round(Math.toDegrees(radians) / 22.5) * 22.5
        val angle = degrees.coerceIn(-45.0, 45.0)
        if (angle != 0.0) {
            val rotation = JsonObject()
            rotation.addProperty("angle", angle)
            rotation.addProperty("axis", axis)
            rotation.add("origin", array(8 - o[0], 8 + shiftY - o[1], 8 + o[2]))
            element.add("rotation", rotation)
        }
        // Entity box layout from (u, v): up/down on top, then west, north, east, south in a row.
        val (u, v, w, h, d) = listOf(box.u, box.v, box.w, box.h, box.d)
        val su = 16 / tw
        val sv = 16 / th
        fun uv(a: Double, b: Double, c: Double, e: Double) = array(a * su, b * sv, c * su, e * sv)
        val faces = JsonObject()
        fun face(name: String, uv: JsonArray) = faces.add(name, JsonObject().apply { add("uv", uv); addProperty("texture", texture) })
        face("up", uv(u + d, v, u + d + w, v + d))
        face("down", uv(u + d + w, v + d, u + d + 2 * w, v))
        val west = uv(u + d + w, v + d, u + 2 * d + w, v + d + h)
        val east = uv(u, v + d, u + d, v + d + h)
        face(if (box.mirror) "west" else "east", east)
        face(if (box.mirror) "east" else "west", west)
        face("north", uv(u + d, v + d, u + d + w, v + d + h))
        face("south", uv(u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h))
        element.add("faces", faces)
        return element
    }

    private fun array(vararg values: Double) = JsonArray().apply { values.forEach { add(Math.round(it * 1000) / 1000.0) } }

    // -- reading the Java -----------------------------------------------------------------------

    /** `static ModelPartData name(float p) { return new ModelPartData(...); }`: name -> (parameter, body). */
    private fun helpers(text: String): Map<String, Pair<String, String>> =
        Regex("""static ModelPartData (\w+)\(float (\w+)\)\s*\{\s*return (new ModelPartData\(.*?\));\s*}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to (it.groupValues[2] to it.groupValues[3]) }

    /** `NAME = new ModelPartData(...)` or `NAME = helper(value)`. */
    private fun constants(text: String, helpers: Map<String, Pair<String, String>>): Map<String, Part> {
        val parts = mutableMapOf<String, Part>()
        for (match in Regex("""ModelPartData ([A-Z0-9_]+)\s*=\s*(.*?);\s*\n""", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val value = match.groupValues[2].trim()
            val part = if (value.startsWith("new ModelPartData(")) part(value, mapOf())
            else Regex("""(\w+)\((.*)\)""").matchEntire(value)?.let { call ->
                val (parameter, body) = helpers[call.groupValues[1]] ?: return@let null
                part(body, mapOf(parameter to evaluate(call.groupValues[2], mapOf())))
            }
            if (part != null) parts[match.groupValues[1]] = part
        }
        return parts
    }

    private fun part(text: String, variables: Map<String, Double>): Part? {
        val boxes = mutableListOf<Box>()
        var u = 0.0
        var v = 0.0
        var mirror = false
        var index = 0
        while (true) {
            val next = Regex("""\.(texOffs|addBox|mirror)\(""").find(text, index) ?: break
            val (arguments, end) = arguments(text, next.range.last)
            index = end
            when (next.groupValues[1]) {
                "texOffs" -> arguments.map { evaluate(it, variables) }.let { u = it[0]; v = it[1] }
                "mirror" -> mirror = arguments.firstOrNull()?.trim() != "false"
                "addBox" -> {
                    val numbers = arguments.take(6).map { evaluate(it, variables) }
                    val inflate = arguments.getOrNull(6)?.let { Regex("""CubeDeformation\(([^)]*)\)""").find(it)?.groupValues?.get(1) }
                        ?.let { evaluate(it, variables) } ?: 0.0
                    boxes += Box(u, v, numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5], inflate, mirror)
                }
            }
        }
        if (boxes.isEmpty()) return null
        val offset = DoubleArray(3)
        val rotation = DoubleArray(3)
        Regex("""PartPose\.(rotation|offset|offsetAndRotation)\(""").find(text)?.let { pose ->
            val values = arguments(text, pose.range.last).first.map { evaluate(it, variables) }
            when (pose.groupValues[1]) {
                "rotation" -> values.toDoubleArray().copyInto(rotation)
                "offset" -> values.toDoubleArray().copyInto(offset)
                else -> {
                    values.subList(0, 3).toDoubleArray().copyInto(offset)
                    values.subList(3, 6).toDoubleArray().copyInto(rotation)
                }
            }
        }
        return Part(boxes, offset, rotation)
    }

    /** The comma-separated arguments of the call whose `(` is at [open], and the index after its `)`. */
    private fun arguments(text: String, open: Int): Pair<List<String>, Int> {
        val arguments = mutableListOf<String>()
        var depth = 0
        var start = open + 1
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        if (text.substring(start, i).isNotBlank()) arguments += text.substring(start, i)
                        return arguments to i + 1
                    }
                }
                ',' -> if (depth == 1) {
                    arguments += text.substring(start, i)
                    start = i + 1
                }
            }
            i++
        }
        throw IllegalArgumentException("unbalanced call")
    }

    /** Numbers, variables, + - * / and parentheses (Java float literals). */
    private fun evaluate(expression: String, variables: Map<String, Double>): Double {
        val tokens = Regex("""\d+\.?\d*[fFdD]?|\.\d+[fFdD]?|[A-Za-z_][\w.]*|[-+*/()]""").findAll(expression).map { it.value }.toList()
        var position = 0
        fun peek() = tokens.getOrNull(position)
        lateinit var sum: () -> Double
        fun atom(): Double {
            val token = tokens[position++]
            return when {
                token == "(" -> sum().also { position++ }
                token == "-" -> -atom()
                token == "+" -> atom()
                token == "Math.PI" -> Math.PI
                token[0].isLetter() || token[0] == '_' -> variables[token] ?: throw IllegalArgumentException("unknown $token")
                else -> token.trimEnd('f', 'F', 'd', 'D').toDouble()
            }
        }
        fun product(): Double {
            var value = atom()
            while (peek() == "*" || peek() == "/") value = if (tokens[position++] == "*") value * atom() else value / atom()
            return value
        }
        sum = {
            var value = product()
            while (peek() == "+" || peek() == "-") value = if (tokens[position++] == "+") value + product() else value - product()
            value
        }
        return sum()
    }
}
