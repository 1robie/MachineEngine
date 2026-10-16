package fr.robie.machineengine.nms.v1_20_6;

import fr.robie.machineengine.api.platform.NmsAccess;
import fr.robie.machineengine.api.platform.PlayerInput;
import fr.robie.machineengine.api.platform.WorldFluidNms;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Minecraft 1.20.6: {@code InventoryView} is a class; no player input or glider API yet. */
public final class VersionNms implements NmsAccess {
    private static final WorldFluidNms WORLD_FLUIDS = new WorldFluidAccess();

    @Override
    public @NotNull String name() {
        return "v1_20_6";
    }

    @Override
    public @NotNull Inventory topInventory(@NotNull HumanEntity player) {
        return player.getOpenInventory().getTopInventory();
    }

    @Override
    public @Nullable PlayerInput input(@NotNull Player player) {
        return null;
    }

    @Override
    public boolean setGlider(@NotNull ItemMeta meta, boolean glider) {
        return false;
    }

    @Override
    public @NotNull WorldFluidNms worldFluids() {
        return WORLD_FLUIDS;
    }
}
