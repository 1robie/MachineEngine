package fr.robie.machineengine.api.resource.fluid;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * How a placed fluid looks. Without textures it is drawn with the vanilla water texture tinted with
 * {@link #tint()} (or the fluid's menu color): such a fluid needs no texture of its own. Fluids that
 * look the same share one look in the client (docs/FLUID_TYPES_PLAN.md §2.2).
 *
 * <p>Textures, tint, opacity and glow apply after the pack is rebuilt; the fog after players rejoin
 * (it is sent with the world's biomes at login).</p>
 *
 * @param still     the still texture, or {@code null} for water's
 * @param flow      the flowing texture, or {@code null} for water's
 * @param tint      color multiplied over the texture ({@code 0xRRGGBB}); {@code null}: the fluid's menu
 *                  color without a texture, white with one
 * @param opacity   0 (invisible) to 1 (opaque); water is about 0.7
 * @param glow      0 to 1: how much it ignores darkness (1 = fully lit, like a light source)
 * @param fog       underwater fog color ({@code 0xRRGGBB}), or {@code null} for the tint's
 * @param frameTime ticks per animation frame, 1 or more (water: 2)
 */
public record FluidLook(@Nullable FluidTexture still, @Nullable FluidTexture flow, @Nullable Integer tint,
                        double opacity, double glow, @Nullable Integer fog, int frameTime) {
    /** Water's texture with the fluid's color. */
    public static final FluidLook TINTED_WATER = new FluidLook(null, null, null, 180 / 255.0, 0, null, 2);

    public FluidLook {
        if (!(opacity >= 0 && opacity <= 1)) throw new IllegalArgumentException("opacity must be between 0 and 1, got " + opacity);
        if (!(glow >= 0 && glow <= 1)) throw new IllegalArgumentException("glow must be between 0 and 1, got " + glow);
        if (frameTime < 1) throw new IllegalArgumentException("frameTime must be 1 or more, got " + frameTime);
        tint = tint == null ? null : tint & 0xFFFFFF;
        fog = fog == null ? null : fog & 0xFFFFFF;
    }

    /** Whether the fluid has a texture of its own (it then takes a tile in the texture grid). */
    public boolean textured() {
        return this.still != null || this.flow != null;
    }

    /** The tint to draw with, for a fluid whose menu color is {@code color} (or {@code null}). */
    public int tintFor(@Nullable Integer color) {
        if (this.tint != null) return this.tint;
        if (!textured() && color != null) return color;
        return 0xFFFFFF;
    }

    /** The fog to use, for a fluid whose menu color is {@code color} (or {@code null}). */
    public int fogFor(@Nullable Integer color) {
        if (this.fog != null) return this.fog;
        return tintFor(color);
    }

    public @NotNull FluidLook withTint(@Nullable Integer value) {
        return new FluidLook(this.still, this.flow, value, this.opacity, this.glow, this.fog, this.frameTime);
    }
}
