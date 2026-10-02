package fr.robie.machineengine.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Upgrades were installed in or removed from a machine. */
public final class MachineUpgradeChangeEvent extends MachineEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UpgradeType upgrade;
    private final int previous;
    private final int current;

    public MachineUpgradeChangeEvent(@NotNull MachineInstance machine, @NotNull UpgradeType upgrade, int previous, int current) {
        super(machine);
        this.upgrade = upgrade;
        this.previous = previous;
        this.current = current;
    }

    public @NotNull UpgradeType getUpgrade() {
        return this.upgrade;
    }

    /** How many were installed before the change. */
    public int getPrevious() {
        return this.previous;
    }

    /** How many are installed now (0: removed). */
    public int getCurrent() {
        return this.current;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
