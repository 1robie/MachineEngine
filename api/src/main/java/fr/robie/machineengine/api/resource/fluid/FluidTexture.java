package fr.robie.machineengine.api.resource.fluid;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/**
 * Where a fluid's still or flowing texture comes from, for the resource pack MachineEngine builds
 * (docs/FLUID_TYPES_PLAN.md §2.3). Made by a {@link FluidTextureType} from a definition, or written
 * in code.
 */
@FunctionalInterface
public interface FluidTexture {
    /**
     * The texture's animation frames: square images of {@code size} px (16 for still, 32 for flowing),
     * as many as the animation has. The pack builder fits them to the water animation.
     */
    @NotNull List<BufferedImage> frames(int size, @NotNull Context context) throws IOException;

    /** What a texture can read while the pack is built. */
    interface Context {
        /** Whether this is the flowing texture (else the still one). */
        boolean flowing();

        /**
         * A vanilla texture's frames (from the official client jar), e.g. {@code block/water_still}
         * or {@code block/lava_flow}, scaled to {@code size}.
         */
        @NotNull List<BufferedImage> vanilla(@NotNull String path, int size) throws IOException;

        /**
         * A texture from the CraftEngine resource packs, e.g. {@code mymod:block/oil_still} for
         * {@code assets/mymod/textures/block/oil_still.png}, cut into frames and scaled to {@code size}.
         */
        @NotNull List<BufferedImage> pack(@NotNull Key texture, int size) throws IOException;
    }
}
