package fr.robie.machineengine.api.paper.event;

import fr.robie.machineengine.api.machine.MachineInstance;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A MachineEngine event mirrored as a Bukkit event (§26). Fired on the machine's owner thread (its
 * region on Folia); listeners must not block and must not keep the machine after the event.
 */
public abstract class MachineEvent extends Event {
    private final MachineInstance machine;

    protected MachineEvent(@NotNull MachineInstance machine) {
        super(!Bukkit.isPrimaryThread());
        this.machine = machine;
    }

    public @NotNull MachineInstance getMachine() {
        return this.machine;
    }

    public @NotNull Key getMachineType() {
        return this.machine.type().key();
    }

    /** The machine's world, or {@code null} if it is not loaded. */
    public @Nullable World getWorld() {
        Key world = this.machine.position().world();
        return Bukkit.getWorld(new NamespacedKey(world.namespace(), world.value()));
    }

    /** The machine's block location, or {@code null} if its world is not loaded. */
    public @Nullable Location getLocation() {
        World world = getWorld();
        var position = this.machine.position().position();
        return world == null ? null : new Location(world, position.x(), position.y(), position.z());
    }
}
