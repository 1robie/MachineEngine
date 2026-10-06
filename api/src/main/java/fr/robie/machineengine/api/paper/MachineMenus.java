package fr.robie.machineengine.api.paper;

import fr.robie.machineengine.api.world.WorldPosition;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Opens machine menus (§23.3). Implemented by a menu-plugin integration (zMenu); {@link #current()} is
 * empty without one, and machines then have no GUI.
 */
public interface MachineMenus {
    /**
     * Opens the menu configured for the type of the machine at {@code position}. Player's thread.
     *
     * @return whether a menu was opened
     */
    boolean open(@NotNull Player player, @NotNull WorldPosition position);

    /** Opens the menu file {@code menu} (e.g. a side configuration menu) for the machine at {@code position}. */
    boolean open(@NotNull Player player, @NotNull WorldPosition position, @NotNull String menu);

    /** The menus of the running server, or {@code null} (no menu plugin). */
    static @Nullable MachineMenus current() {
        return Holder.current;
    }

    /** Set by the menu integration when it starts and stops. */
    @ApiStatus.Internal
    static void setCurrent(@Nullable MachineMenus menus) {
        Holder.current = menus;
    }

    @ApiStatus.Internal
    final class Holder {
        private static volatile @Nullable MachineMenus current;

        private Holder() {
        }
    }
}
