package fr.robie.machineengine.api.platform;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything MachineEngine and its addons need that differs between supported Minecraft versions
 * (D25): newer API, or API whose binary shape changed. Implemented once per version module
 * ({@code nms:v1_20_6}, {@code nms:v26_3}), and reflectively for versions without one.
 * Get it from {@link Nms#get()}.
 */
public interface NmsAccess {
    /** A short name for logs, e.g. {@code v26_3}. */
    @NotNull String name();

    /** The top inventory of what the player has open (their crafting grid when nothing is open). */
    @NotNull Inventory topInventory(@NotNull HumanEntity player);

    /** The movement keys the player holds, or {@code null} where the server does not report them (before 1.21.2). */
    @Nullable PlayerInput input(@NotNull Player player);

    /**
     * Makes an item glide like an elytra, or stop.
     *
     * @return false where the server has no such item property (before 1.21.2)
     */
    boolean setGlider(@NotNull ItemMeta meta, boolean glider);
}
