package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.paper.event.WorldFluidPlaceEvent;
import fr.robie.machineengine.api.paper.event.WorldFluidRemoveEvent;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidEffect;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Fluids in the world (D46): which fluid a block holds, where a fluid may go, and the settings in
 * effect (a fluid's definition with the server owner's {@code fluids.yml} on top). Call it on the
 * block's thread.
 *
 * <p>Until MachineEngine's world fluids run (headless tests, or {@code world-fluids.enabled: false})
 * only vanilla water and lava are known: they may go into air or their own flowing block, and no
 * placeable fluid can be placed.</p>
 */
public final class WorldFluids {
    /** What MachineEngine installs when world fluids run. */
    @ApiStatus.Internal
    public interface Access {
        @Nullable ResourceType at(@NotNull Block block);

        @NotNull Placement canPlace(@NotNull Block block, @NotNull FluidType fluid);

        boolean place(@NotNull Block block, @NotNull FluidType fluid, WorldFluidPlaceEvent.@NotNull Cause cause);

        @Nullable ResourceType take(@NotNull Block block, WorldFluidRemoveEvent.@NotNull Cause cause);

        @NotNull FluidPhysics physics(@NotNull FluidType fluid);

        @NotNull FluidLook look(@NotNull FluidType fluid);

        @NotNull FluidMixing mixing(@NotNull FluidType fluid);

        @NotNull List<FluidEffect> effects(@NotNull FluidType fluid);
    }

    /** Whether a fluid may go somewhere, and if not, why. */
    public record Placement(boolean allowed, @Nullable Component reason) {
        public static final Placement ALLOWED = new Placement(true, null);

        public static @NotNull Placement refused(@NotNull Component reason) {
            return new Placement(false, Objects.requireNonNull(reason));
        }
    }

    private static final Access VANILLA_ONLY = new Access() {
        @Override
        public @Nullable ResourceType at(@NotNull Block block) {
            Material type = block.getType();
            if (type == Material.WATER) return Fluids.water();
            if (type == Material.LAVA) return Fluids.lava();
            return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged() ? Fluids.water() : null;
        }

        @Override
        public @NotNull Placement canPlace(@NotNull Block block, @NotNull FluidType fluid) {
            Material material = fluid == Fluids.water() ? Material.WATER : fluid == Fluids.lava() ? Material.LAVA : null;
            if (material == null) return Placement.refused(Component.text("world fluids are not running"));
            boolean flowing = block.getType() == material && block.getBlockData() instanceof org.bukkit.block.data.Levelled levelled && levelled.getLevel() != 0;
            return block.getType().isAir() || flowing ? Placement.ALLOWED : Placement.refused(Component.text("not air or flowing " + material.getKey().getKey()));
        }

        @Override
        public boolean place(@NotNull Block block, @NotNull FluidType fluid, WorldFluidPlaceEvent.@NotNull Cause cause) {
            return false;
        }

        @Override
        public @Nullable ResourceType take(@NotNull Block block, WorldFluidRemoveEvent.@NotNull Cause cause) {
            return null;
        }

        @Override
        public @NotNull FluidPhysics physics(@NotNull FluidType fluid) {
            return fluid.physics();
        }

        @Override
        public @NotNull FluidLook look(@NotNull FluidType fluid) {
            return fluid.look();
        }

        @Override
        public @NotNull FluidMixing mixing(@NotNull FluidType fluid) {
            return fluid.mixing();
        }

        @Override
        public @NotNull List<FluidEffect> effects(@NotNull FluidType fluid) {
            return fluid.effects();
        }
    };

    private static volatile Access access = VANILLA_ONLY;

    private WorldFluids() {
    }

    /** What answers while MachineEngine's world fluids don't run: vanilla water and lava only. */
    @ApiStatus.Internal
    public static @NotNull Access vanillaOnly() {
        return VANILLA_ONLY;
    }

    @ApiStatus.Internal
    public static void setAccess(@Nullable Access value) {
        access = value == null ? VANILLA_ONLY : value;
    }

    /**
     * The fluid in {@code block}: a placeable fluid MachineEngine tracks, vanilla water (also in a
     * waterlogged block) or lava; {@code null} for none.
     */
    public static @Nullable ResourceType at(@NotNull Block block) {
        return access.at(block);
    }

    /** Whether {@code fluid} may be placed in or flow into {@code block} (the same rules buckets and spreading follow). */
    public static @NotNull Placement canPlace(@NotNull Block block, @NotNull FluidType fluid) {
        return access.canPlace(block, fluid);
    }

    /**
     * Places a source of {@code fluid} in {@code block} (air, or flowing fluid), firing
     * {@link fr.robie.machineengine.api.paper.event.WorldFluidPlaceEvent}.
     *
     * @return whether it was placed
     */
    public static boolean place(@NotNull Block block, @NotNull FluidType fluid) {
        return access.place(block, fluid, WorldFluidPlaceEvent.Cause.API);
    }

    /** {@link #place(Block, FluidType)} with the cause the event reports (a machine, a command …). */
    public static boolean place(@NotNull Block block, @NotNull FluidType fluid, WorldFluidPlaceEvent.@NotNull Cause cause) {
        return access.place(block, fluid, cause);
    }

    /**
     * Removes the fluid source in {@code block}.
     *
     * @return the fluid taken, or {@code null} if the block held no source
     */
    public static @Nullable ResourceType take(@NotNull Block block) {
        return access.take(block, WorldFluidRemoveEvent.Cause.API);
    }

    /** {@link #take(Block)} with the cause the event reports. */
    public static @Nullable ResourceType take(@NotNull Block block, WorldFluidRemoveEvent.@NotNull Cause cause) {
        return access.take(block, cause);
    }

    /** The physics in effect for {@code fluid} (its definition, overridden by {@code fluids.yml}). */
    public static @NotNull FluidPhysics physics(@NotNull FluidType fluid) {
        return access.physics(fluid);
    }

    /** The look in effect for {@code fluid}. */
    public static @NotNull FluidLook look(@NotNull FluidType fluid) {
        return access.look(fluid);
    }

    /** The mixing rules in effect for {@code fluid}. */
    public static @NotNull FluidMixing mixing(@NotNull FluidType fluid) {
        return access.mixing(fluid);
    }

    /** The effects in effect for {@code fluid}. */
    public static @NotNull List<FluidEffect> effects(@NotNull FluidType fluid) {
        return access.effects(fluid);
    }
}
