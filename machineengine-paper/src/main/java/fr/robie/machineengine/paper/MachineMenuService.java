package fr.robie.machineengine.paper;

import fr.robie.machineengine.core.machine.LoadedMachine;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Opens machine menus (§23.3). Implemented by a menu-plugin integration (zMenu); without one,
 * machines have no GUI.
 */
public interface MachineMenuService {

    /**
     * Opens the menu configured for {@code machine}'s type. Called on the player's thread.
     *
     * @return whether a menu was opened
     */
    boolean open(@NotNull Player player, @NotNull LoadedMachine machine);

    /** Reloads the menu files. */
    void reload();

    /**
     * The machine at {@code position} was removed or unloaded: close the menus showing it, so nobody
     * keeps acting on (or looking at) a machine that is gone.
     */
    default void machineGone(@NotNull fr.robie.machineengine.api.world.WorldPosition position) {
    }
}
