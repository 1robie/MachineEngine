package fr.robie.machineengine.paper;

import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Open inventories, read reflectively: {@code InventoryView} is a class on 1.20.6 and an interface on
 * 1.21+, so a direct call compiled against one version fails on the other
 * ({@code IncompatibleClassChangeError}, caught by {@code verifyPaperBaseline}). Moves to nms:common in
 * RESTRUCTURE_PLAN.md R5.
 */
public final class InventoryViews {
    private static final MethodHandle GET_OPEN_INVENTORY;
    private static final MethodHandle GET_TOP_INVENTORY;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // findVirtual adapts to InventoryView being a class or an interface at runtime.
            GET_OPEN_INVENTORY = lookup.findVirtual(HumanEntity.class, "getOpenInventory", MethodType.methodType(InventoryView.class));
            GET_TOP_INVENTORY = lookup.findVirtual(InventoryView.class, "getTopInventory", MethodType.methodType(Inventory.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private InventoryViews() {
    }

    /** The top inventory of what the player has open (their crafting grid when nothing is open). */
    public static @NotNull Inventory topInventory(@NotNull HumanEntity player) {
        try {
            return (Inventory) GET_TOP_INVENTORY.invoke(GET_OPEN_INVENTORY.invoke(player));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not read the open inventory of " + player.getName(), e);
        }
    }
}
