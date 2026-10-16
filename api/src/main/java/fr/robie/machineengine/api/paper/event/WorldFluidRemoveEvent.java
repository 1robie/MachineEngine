package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.resource.fluid.FluidType;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A placeable fluid left a block (D46). Fired after the fact: the fluid may have dried up or been
 * replaced by the game, which can't be undone.
 */
public final class WorldFluidRemoveEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Block block;
    private final FluidType fluid;
    private final Cause cause;

    public WorldFluidRemoveEvent(@NotNull Block block, @NotNull FluidType fluid, @NotNull Cause cause) {
        super(!Bukkit.isPrimaryThread());
        this.block = block;
        this.fluid = fluid;
        this.cause = cause;
    }

    public @NotNull Block getBlock() {
        return this.block;
    }

    public @NotNull FluidType getFluid() {
        return this.fluid;
    }

    public @NotNull Cause getCause() {
        return this.cause;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Why the fluid left. */
    public enum Cause {
        /** Picked up with a bucket. */
        BUCKET,
        /** Flowing fluid dried up after its source went away. */
        DRIED,
        /** A block, a sponge, a piston or an explosion replaced it. */
        REPLACED,
        /** A machine took it (e.g. a pump). */
        MACHINE,
        /** An admin command. */
        COMMAND,
        /** Another plugin, through {@link fr.robie.machineengine.api.paper.WorldFluids#take}. */
        API
    }
}
