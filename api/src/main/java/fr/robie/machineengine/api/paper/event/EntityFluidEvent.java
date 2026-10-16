package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.resource.fluid.FluidType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * An entity entered or left a placeable fluid (D46): its viscosity and effects start or stop.
 * Listen to {@link Enter} or {@link Leave}.
 */
public abstract sealed class EntityFluidEvent extends Event permits EntityFluidEvent.Enter, EntityFluidEvent.Leave {
    private final Entity entity;
    private final FluidType fluid;

    private EntityFluidEvent(@NotNull Entity entity, @NotNull FluidType fluid) {
        super(!Bukkit.isPrimaryThread());
        this.entity = entity;
        this.fluid = fluid;
    }

    public @NotNull Entity getEntity() {
        return this.entity;
    }

    public @NotNull FluidType getFluid() {
        return this.fluid;
    }

    /** The entity entered the fluid. */
    public static final class Enter extends EntityFluidEvent {
        private static final HandlerList HANDLERS = new HandlerList();

        public Enter(@NotNull Entity entity, @NotNull FluidType fluid) {
            super(entity, fluid);
        }

        public static @NotNull HandlerList getHandlerList() {
            return HANDLERS;
        }

        @Override
        public @NotNull HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    /** The entity left the fluid (or the fluid went away). */
    public static final class Leave extends EntityFluidEvent {
        private static final HandlerList HANDLERS = new HandlerList();

        public Leave(@NotNull Entity entity, @NotNull FluidType fluid) {
            super(entity, fluid);
        }

        public static @NotNull HandlerList getHandlerList() {
            return HANDLERS;
        }

        @Override
        public @NotNull HandlerList getHandlers() {
            return HANDLERS;
        }
    }
}
