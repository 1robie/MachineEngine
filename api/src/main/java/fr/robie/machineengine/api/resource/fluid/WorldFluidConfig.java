package fr.robie.machineengine.api.resource.fluid;

import fr.robie.machineengine.api.config.ConfigKey;

/**
 * Settings of fluids in the world (D46), in {@code config.yml} under {@code world-fluids}. Each fluid's
 * own settings are in its definition and in {@code fluids.yml}.
 */
public final class WorldFluidConfig {
    public static final ConfigKey<Boolean> ENABLED = ConfigKey.bool("world-fluids.enabled", true)
            .comment("Placeable fluids in the world. Off: fluids only live in tanks and pipes.").restartOnly();

    public static final ConfigKey<Boolean> UNIFORM_WATER = ConfigKey.bool("world-fluids.uniform-water", true)
            .comment("Every biome's water gets the neutral color for players (swamps and oceans lose their tint).",
                    "Needed for more than 6 different fluid looks. Players must rejoin.").restartOnly();

    public static final ConfigKey<Integer> NEUTRAL_COLOR = ConfigKey.color("world-fluids.neutral-color", 0x3F76E4)
            .comment("The neutral water color, \"#RRGGBB\" (quote it). Players must rejoin.").restartOnly();

    public static final ConfigKey<Integer> NEUTRAL_RINGS = ConfigKey.integer("world-fluids.neutral-rings", 6, 1, 8)
            .comment("Only without uniform-water: 4-block areas around a fluid that get the neutral color.");

    public static final ConfigKey<Boolean> SHADER = ConfigKey.bool("world-fluids.shader", true)
            .comment("Draw fluids with their own textures (a core shader in the resource pack).",
                    "Off: fluids are water tinted with a code color.");

    public static final ConfigKey<Boolean> SODIUM = ConfigKey.bool("world-fluids.sodium", true)
            .comment("Also draw them for players using Sodium (its shader, in the resource pack).");

    public static final ConfigKey<Edge> EDGE = ConfigKey.choice("world-fluids.edge", Edge.SOFT)
            .comment("Where a fluid meets water: soft (fades) or sharp (a line). Applies after the pack is rebuilt.");

    public static final ConfigKey<Conflict> CONFLICT = ConfigKey.choice("world-fluids.conflict", Conflict.STOP)
            .comment("stop: a fluid and plain water never share a 4-block color area (a small gap between them).",
                    "allow: they may, and the fluid fades into the water around it (dispersion).");

    public static final ConfigKey<Integer> FLUID_GAP = ConfigKey.integer("world-fluids.fluid-gap", 2, 0, 6)
            .comment("4-block areas kept between two different fluids (2 = about 8 blocks).",
                    "Closer, the client blends both and the blocks between them show plain water.");

    public static final ConfigKey<Integer> PADDING = ConfigKey.integer("world-fluids.padding", 1, 0, 2)
            .comment("4-block areas around a fluid (where no water is) that get its color: cleaner corners.");

    public static final ConfigKey<Integer> DISPERSION = ConfigKey.integer("world-fluids.dispersion", 2, 0, 2)
            .comment("With conflict: allow, rings of 4-block areas where a fluid fades into plain water (half, then a quarter).");

    public static final ConfigKey<String> CLIENT_JAR = ConfigKey.string("world-fluids.client-jar", "download")
            .comment("Where vanilla's water textures and shaders come from to build the pack:",
                    "\"download\" (the official client jar, from Mojang, cached) or the path of a client jar.");

    public static final ConfigKey<Integer> TEXTURE_GRID = ConfigKey.integer("world-fluids.texture-grid", 8, 2, 16)
            .comment("Most fluid textures per side of the texture grid: 8 holds 63 textures, 16 holds 255 (more memory).");

    /** Where a fluid meets water. */
    public enum Edge {
        SOFT,
        SHARP
    }

    /** Whether a fluid and plain water may share a color area. */
    public enum Conflict {
        STOP,
        ALLOW
    }

    private WorldFluidConfig() {
    }
}
