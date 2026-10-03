package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.SnapshotParticipant;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.paper.PaperItems;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A vanilla container (chest, barrel, shulker box, hopper, dispenser, dropper) as an item handler, so
 * machines push into it, sorters pull from it and item networks fill it. Changes go straight to the
 * inventory and are undone if the transaction aborts (a snapshot of its contents). Owner thread only.
 */
final class ContainerHandler extends SnapshotParticipant<ItemStack[]> implements ResourceHandler {
    private final Inventory inventory;

    private ContainerHandler(Inventory inventory) {
        this.inventory = inventory;
    }

    /** The handler of {@code state}'s inventory, or {@code null} if it is not one of the supported containers. */
    static @Nullable ContainerHandler of(@NotNull BlockState state) {
        if (!(state instanceof Container container)) return null;
        if (!(state instanceof Chest || state instanceof Barrel || state instanceof ShulkerBox || state instanceof Hopper
                || state instanceof Dispenser || state instanceof Dropper)) return null; // furnaces & co have slot roles
        return new ContainerHandler(container.getInventory());
    }

    @Override
    public long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        ItemStack prototype = maxAmount <= 0 ? null : PaperItems.toItemStack(variant, 1);
        if (prototype == null) return 0;
        ItemStack[] contents = this.inventory.getStorageContents();
        long left = maxAmount;
        int max = Math.min(prototype.getMaxStackSize(), this.inventory.getMaxStackSize());
        boolean changed = false;
        // Fill matching stacks first, then empty slots.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < contents.length && left > 0; i++) {
                ItemStack slot = contents[i];
                boolean empty = slot == null || slot.getType().isAir();
                if (pass == 0 ? empty || !slot.isSimilar(prototype) : !empty) continue;
                int present = empty ? 0 : slot.getAmount();
                int added = (int) Math.min(left, max - present);
                if (added <= 0) continue;
                if (!changed) {
                    updateSnapshots(transaction);
                    changed = true;
                }
                contents[i] = prototype.asQuantity(present + added);
                left -= added;
            }
        }
        if (changed) this.inventory.setStorageContents(contents);
        return maxAmount - left;
    }

    @Override
    public long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        ItemStack prototype = maxAmount <= 0 ? null : PaperItems.toItemStack(variant, 1);
        if (prototype == null) return 0;
        ItemStack[] contents = this.inventory.getStorageContents();
        long left = maxAmount;
        boolean changed = false;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack slot = contents[i];
            if (slot == null || !slot.isSimilar(prototype)) continue;
            int taken = (int) Math.min(left, slot.getAmount());
            if (!changed) {
                updateSnapshots(transaction);
                changed = true;
            }
            contents[i] = taken == slot.getAmount() ? null : slot.asQuantity(slot.getAmount() - taken);
            left -= taken;
        }
        if (changed) this.inventory.setStorageContents(contents);
        return maxAmount - left;
    }

    @Override
    public @NotNull List<StorageView> views() {
        ItemStack[] contents = this.inventory.getStorageContents();
        List<StorageView> views = new ArrayList<>(contents.length);
        for (ItemStack slot : contents) {
            if (slot == null || slot.getType().isAir()) {
                views.add(new StorageView(null, 0, this.inventory.getMaxStackSize()));
            } else {
                views.add(new StorageView(PaperItems.toResource(slot).variant(), slot.getAmount(), slot.getMaxStackSize()));
            }
        }
        return views;
    }

    @Override
    protected @NotNull ItemStack[] createSnapshot() {
        ItemStack[] contents = this.inventory.getStorageContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
        return copy;
    }

    @Override
    protected void readSnapshot(@NotNull ItemStack[] snapshot) {
        this.inventory.setStorageContents(snapshot);
    }
}
