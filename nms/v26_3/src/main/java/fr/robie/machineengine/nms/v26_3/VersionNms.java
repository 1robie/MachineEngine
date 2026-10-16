package fr.robie.machineengine.nms.v26_3;

import fr.robie.machineengine.api.platform.NmsAccess;
import fr.robie.machineengine.api.platform.PlayerInput;
import fr.robie.machineengine.api.platform.WorldFluidNms;
import org.bukkit.Input;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Minecraft 26.3: {@code InventoryView} is an interface; player input and gliders exist. */
public final class VersionNms implements NmsAccess {
    private static final WorldFluidNms WORLD_FLUIDS = new WorldFluidAccess();

    @Override
    public @NotNull String name() {
        return "v26_3";
    }

    @Override
    public @NotNull Inventory topInventory(@NotNull HumanEntity player) {
        return player.getOpenInventory().getTopInventory();
    }

    @Override
    public @Nullable PlayerInput input(@NotNull Player player) {
        Input input = player.getCurrentInput();
        return new PlayerInput(input.isForward(), input.isBackward(), input.isLeft(), input.isRight(),
                input.isJump(), input.isSneak(), input.isSprint());
    }

    @Override
    public boolean setGlider(@NotNull ItemMeta meta, boolean glider) {
        meta.setGlider(glider);
        return true;
    }

    @Override
    public @NotNull WorldFluidNms worldFluids() {
        return WORLD_FLUIDS;
    }
}
