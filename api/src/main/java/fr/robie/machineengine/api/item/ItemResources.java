package fr.robie.machineengine.api.item;

import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Items that hold resources (energy, a chemical, a fluid), seen as {@link Items#ITEM} resources in a
 * machine's slots (D32). What an item holds comes from its {@link ResourceItem} behaviors; the amount
 * lives in the item's data. The platform reads and writes it ({@link #setAccess}; on Paper,
 * {@code PaperItemResources}); headless tests install their own.
 */
public final class ItemResources {

    /** Reads and writes the resources an item resource holds. */
    public interface Access {
        /** How much of {@code resource} {@code item} holds when full; 0 if it cannot hold it. */
        long capacity(@NotNull ResourceVariant item, @NotNull Key resource);

        long amount(@NotNull ResourceVariant item, @NotNull Key resource);

        /** The same item holding {@code amount} of {@code resource}. */
        @NotNull ResourceVariant withAmount(@NotNull ResourceVariant item, @NotNull Key resource, long amount);
    }

    private static volatile @Nullable Access access;

    private ItemResources() {
    }

    @ApiStatus.Internal
    public static void setAccess(@Nullable Access value) {
        access = value;
    }

    /** The capacity of {@code item} for {@code resource}; 0 if it is not an item holding it. */
    public static long capacity(@NotNull ResourceVariant item, @NotNull ResourceType resource) {
        Access current = access;
        return current == null || item.type() != Items.ITEM ? 0 : current.capacity(item, resource.key());
    }

    /** How much of {@code resource} {@code item} holds; 0 if it is not an item holding it. */
    public static long amount(@NotNull ResourceVariant item, @NotNull ResourceType resource) {
        Access current = access;
        return current == null || item.type() != Items.ITEM ? 0 : current.amount(item, resource.key());
    }

    /** Matches items that can hold {@code resource}, whatever they hold now. */
    public static @NotNull ResourcePredicate holds(@NotNull ResourceType resource) {
        return (variant, tags) -> capacity(variant, resource) > 0;
    }

    /**
     * Moves up to {@code maxRate} of {@code resource} between the item in {@code slot} and the storage
     * {@code buffer}: out of the item when {@code discharge}, into it otherwise. All or nothing per
     * call; the first item that can move something is used.
     *
     * @return the amount moved
     */
    public static long transfer(@NotNull MachineContext context, @NotNull String slot, @NotNull String buffer,
                                @NotNull ResourceType resource, long maxRate, boolean discharge) {
        Access current = access;
        if (current == null) return 0;
        ResourceHandler items = context.storage(slot);
        ResourceHandler storage = context.storage(buffer);
        for (StorageView view : items.views()) {
            if (view.isEmpty() || view.amount() != 1 || view.variant().type() != Items.ITEM) continue;
            long capacity = current.capacity(view.variant(), resource.key());
            if (capacity <= 0) continue;
            long stored = current.amount(view.variant(), resource.key());
            long room = discharge ? stored : capacity - stored;
            if (room <= 0) continue;
            try (Transaction transaction = context.transactions().openOuter()) {
                long amount = discharge
                        ? storage.insert(resource.variant(), Math.min(room, maxRate), transaction)
                        : storage.extract(resource.variant(), Math.min(room, maxRate), transaction);
                if (amount <= 0) continue;
                ResourceVariant updated = current.withAmount(view.variant(), resource.key(), discharge ? stored - amount : stored + amount);
                if (items.extract(view.variant(), 1, transaction) != 1 || items.insert(updated, 1, transaction) != 1) continue;
                transaction.commit();
                return amount;
            }
        }
        return 0;
    }
}
