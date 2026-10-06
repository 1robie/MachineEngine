package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.world.WorldPosition;
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

    /** Opens the menu file {@code menu} (e.g. a side configuration menu) for {@code machine}. */
    default boolean open(@NotNull Player player, @NotNull LoadedMachine machine, @NotNull String menu) {
        return false;
    }

    /** The menus of the running server, or {@code null} (no menu plugin). */
    static @org.jetbrains.annotations.Nullable MachineMenuService current() {
        return Holder.current;
    }

    /** Set by the menu integration when it starts and stops. */
    static void setCurrent(@org.jetbrains.annotations.Nullable MachineMenuService service) {
        Holder.current = service;
    }

    final class Holder {
        private static volatile @org.jetbrains.annotations.Nullable MachineMenuService current;

        private Holder() {
        }
    }

    /** Reloads the menu files. */
    void reload();

    /**
     * The machine at {@code position} was removed or unloaded: close the menus showing it, so nobody
     * keeps acting on (or looking at) a machine that is gone.
     */
    default void machineGone(@NotNull WorldPosition position) {
    }

    /** What the machine at {@code position} holds changed: refresh the menus showing it (owner thread). */
    default void machineChanged(@NotNull WorldPosition position) {
    }
}
