package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.resource.fluid.FluidType;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** A placeable fluid is about to go into a block (D46). Cancel it to keep the block as it is. */
public final class WorldFluidPlaceEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Block block;
    private final FluidType fluid;
    private final Cause cause;
    private boolean cancelled;

    public WorldFluidPlaceEvent(@NotNull Block block, @NotNull FluidType fluid, @NotNull Cause cause) {
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

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Why the fluid goes there. */
    public enum Cause {
        /** A player or a dispenser poured a bucket. */
        BUCKET,
        /** It flowed from a neighbouring block, or a new source formed. */
        SPREAD,
        /** A machine placed it (e.g. a plenisher). */
        MACHINE,
        /** An admin command. */
        COMMAND,
        /** Another plugin, through {@link fr.robie.machineengine.api.paper.WorldFluids#place}. */
        API
    }
}
