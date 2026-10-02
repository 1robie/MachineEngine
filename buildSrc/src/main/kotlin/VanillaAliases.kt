/**
 * Vanilla members of NeoForge's `c:` convention tags (E10). On NeoForge these come from NeoForge's own
 * data; on Paper they do not exist, so Mekanized adds them as item tags. Ids that do not exist on a
 * given Minecraft version simply never match.
 */
internal object VanillaAliases {
    private val ores = mapOf(
        "iron" to "#minecraft:iron_ores", "gold" to "#minecraft:gold_ores", "copper" to "#minecraft:copper_ores",
        "coal" to "#minecraft:coal_ores", "diamond" to "#minecraft:diamond_ores", "emerald" to "#minecraft:emerald_ores",
        "lapis" to "#minecraft:lapis_ores", "redstone" to "#minecraft:redstone_ores", "quartz" to "minecraft:nether_quartz_ore",
        "netherite_scrap" to "minecraft:ancient_debris",
    )
    private val gems = mapOf(
        "diamond" to "minecraft:diamond", "emerald" to "minecraft:emerald", "quartz" to "minecraft:quartz",
        "lapis" to "minecraft:lapis_lazuli", "amethyst" to "minecraft:amethyst_shard", "prismarine" to "minecraft:prismarine_crystals",
    )
    private val storage = mapOf(
        "iron" to "minecraft:iron_block", "gold" to "minecraft:gold_block", "copper" to "minecraft:copper_block",
        "diamond" to "minecraft:diamond_block", "emerald" to "minecraft:emerald_block", "lapis" to "minecraft:lapis_block",
        "redstone" to "minecraft:redstone_block", "coal" to "minecraft:coal_block", "netherite" to "minecraft:netherite_block",
        "quartz" to "minecraft:quartz_block", "raw_iron" to "minecraft:raw_iron_block", "raw_gold" to "minecraft:raw_gold_block",
        "raw_copper" to "minecraft:raw_copper_block", "slime" to "minecraft:slime_block", "bone_meal" to "minecraft:bone_block",
        "wheat" to "minecraft:hay_block", "dried_kelp" to "minecraft:dried_kelp_block",
    )
    private val exact = mapOf(
        "c:dusts/redstone" to listOf("minecraft:redstone"), "c:dusts/glowstone" to listOf("minecraft:glowstone_dust"),
        "c:rods/wooden" to listOf("minecraft:stick"), "c:rods/blaze" to listOf("minecraft:blaze_rod"),
        "c:rods/breeze" to listOf("minecraft:breeze_rod"),
        "c:gunpowders" to listOf("minecraft:gunpowder"), "c:slime_balls" to listOf("minecraft:slime_ball"),
        "c:ender_pearls" to listOf("minecraft:ender_pearl"), "c:obsidians" to listOf("minecraft:obsidian", "minecraft:crying_obsidian"),
        "c:obsidians/normal" to listOf("minecraft:obsidian"), "c:sands" to listOf("#minecraft:sand"),
        "c:cobblestones" to listOf("minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:cobbled_deepslate"),
        "c:cobblestones/normal" to listOf("minecraft:cobblestone"), "c:cobblestones/deepslate" to listOf("minecraft:cobbled_deepslate"),
        "c:stones" to listOf("minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:deepslate", "minecraft:tuff"),
        "c:gravels" to listOf("minecraft:gravel"), "c:netherracks" to listOf("minecraft:netherrack"),
        "c:end_stones" to listOf("minecraft:end_stone"), "c:bricks/normal" to listOf("minecraft:brick"),
        "c:bricks/nether" to listOf("minecraft:nether_brick"), "c:leathers" to listOf("minecraft:leather"),
        "c:strings" to listOf("minecraft:string"), "c:feathers" to listOf("minecraft:feather"), "c:bones" to listOf("minecraft:bone"),
        "c:mushrooms" to listOf("minecraft:red_mushroom", "minecraft:brown_mushroom"),
        "c:chests/wooden" to listOf("minecraft:chest", "minecraft:trapped_chest"), "c:chests" to listOf("minecraft:chest", "minecraft:trapped_chest"),
        "c:fences/wooden" to listOf("#minecraft:wooden_fences"), "c:fence_gates/wooden" to listOf("#minecraft:fence_gates"),
        "c:glass_blocks" to listOf("minecraft:glass", "minecraft:tinted_glass"), "c:glass_blocks/colorless" to listOf("minecraft:glass"),
        "c:glass_blocks/cheap" to listOf("minecraft:glass"), "c:glass_panes" to listOf("minecraft:glass_pane"),
        "c:glass_panes/colorless" to listOf("minecraft:glass_pane"), "c:heads" to listOf("minecraft:skeleton_skull", "minecraft:wither_skeleton_skull",
            "minecraft:zombie_head", "minecraft:creeper_head", "minecraft:player_head", "minecraft:dragon_head", "minecraft:piglin_head"),
        "c:crops" to listOf("minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:nether_wart"),
        "c:seeds" to listOf("minecraft:wheat_seeds", "minecraft:beetroot_seeds", "minecraft:melon_seeds", "minecraft:pumpkin_seeds"),
        "c:foods" to listOf(), "c:dyes" to listOf("#minecraft:dyes"),
        "c:ingots" to listOf("minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:copper_ingot", "minecraft:netherite_ingot"),
        "c:nuggets" to listOf("minecraft:iron_nugget", "minecraft:gold_nugget"),
        "c:raw_materials" to listOf("minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper"),
        "c:ores" to listOf("#minecraft:iron_ores", "#minecraft:gold_ores", "#minecraft:copper_ores", "#minecraft:coal_ores",
            "#minecraft:diamond_ores", "#minecraft:emerald_ores", "#minecraft:lapis_ores", "#minecraft:redstone_ores",
            "minecraft:nether_quartz_ore", "minecraft:ancient_debris"),
        "c:gems" to listOf("minecraft:diamond", "minecraft:emerald", "minecraft:quartz", "minecraft:lapis_lazuli", "minecraft:amethyst_shard"),
        "c:storage_blocks" to storage.values.toList(),
        "c:dusts" to listOf("minecraft:redstone", "minecraft:glowstone_dust"),
        "c:rods" to listOf("minecraft:stick", "minecraft:blaze_rod"),
        "c:tools/shear" to listOf("minecraft:shears"), "c:tools/shears" to listOf("minecraft:shears"),
        "c:buckets/water" to listOf("minecraft:water_bucket"), "c:buckets/lava" to listOf("minecraft:lava_bucket"),
        "c:buckets/milk" to listOf("minecraft:milk_bucket"), "c:buckets/empty" to listOf("minecraft:bucket"),
        "c:player_workstations/crafting_tables" to listOf("minecraft:crafting_table"),
        "c:player_workstations/furnaces" to listOf("minecraft:furnace"),
        "c:villager_job_sites" to listOf(),
        "c:barrels/wooden" to listOf("minecraft:barrel"), "c:bookshelves" to listOf("minecraft:bookshelf"),
        "c:crops/cactus" to listOf("minecraft:cactus"), "c:flowers/tall" to listOf("#minecraft:tall_flowers"),
        "c:foods/berry" to listOf("minecraft:sweet_berries", "minecraft:glow_berries"), "c:nether_stars" to listOf("minecraft:nether_star"),
        "c:sandstone/red_blocks" to listOf("minecraft:red_sandstone", "minecraft:chiseled_red_sandstone", "minecraft:cut_red_sandstone",
            "minecraft:smooth_red_sandstone"),
        "c:sandstone/uncolored_blocks" to listOf("minecraft:sandstone", "minecraft:chiseled_sandstone", "minecraft:cut_sandstone",
            "minecraft:smooth_sandstone"),
    )
    private val colors = listOf("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")

    /** Vanilla item ids or `#minecraft:` tags in the `c:` tag [tag]; empty if none are known. */
    fun of(tag: String): List<String> {
        exact[tag]?.let { return it }
        val path = tag.removePrefix("c:")
        if (path == tag) return listOf()
        val group = path.substringBefore("/")
        val name = path.substringAfter("/", "")
        if (name.isEmpty()) return listOf()
        return when (group) {
            "ores" -> listOfNotNull(ores[name])
            "ingots" -> if (name in setOf("iron", "gold", "copper", "netherite")) listOf("minecraft:${name}_ingot") else listOf()
            "nuggets" -> if (name in setOf("iron", "gold")) listOf("minecraft:${name}_nugget") else listOf()
            "raw_materials" -> if (name in setOf("iron", "gold", "copper")) listOf("minecraft:raw_$name") else listOf()
            "storage_blocks" -> listOfNotNull(storage[name])
            "gems" -> listOfNotNull(gems[name])
            "dyes" -> if (name in colors) listOf("minecraft:${name}_dye") else listOf()
            "crops" -> if (name in setOf("wheat", "carrot", "potato", "beetroot", "nether_wart", "cocoa_bean", "sugar_cane", "pumpkin", "melon"))
                listOf("minecraft:" + (if (name == "cocoa_bean") "cocoa_beans" else name)) else listOf()
            "seeds" -> if (name in setOf("wheat", "beetroot", "melon", "pumpkin", "torchflower")) listOf("minecraft:${name}_seeds") else listOf()
            "glass_blocks", "glass_panes" -> if (name in colors) listOf("minecraft:${name}_stained_" + (if (group == "glass_panes") "glass_pane" else "glass")) else listOf()
            else -> listOf()
        }
    }
}
