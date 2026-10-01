package fr.robie.machineengine.api.transfer.storage;

import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.TransactionContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Several handlers seen as one, e.g. the slots of an inventory. Inserts fill parts that already
 * hold something first, then empty parts, in order.
 */
public class CombinedResourceHandler implements ResourceHandler {
    private final List<ResourceHandler> parts;

    public CombinedResourceHandler(@NotNull List<? extends ResourceHandler> parts) {
        this.parts = List.copyOf(parts);
    }

    /** @return {@code count} slots of {@code capacity} each */
    public static @NotNull CombinedResourceHandler slots(int count, long capacity) {
        if (count <= 0) throw new IllegalArgumentException("Slot count must be positive: " + count);
        List<SingleVariantStorage> slots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) slots.add(SingleVariantStorage.of(capacity));
        return new CombinedResourceHandler(slots);
    }

    public @NotNull List<ResourceHandler> parts() {
        return this.parts;
    }

    @Override
    public long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        if (maxAmount < 0) throw new IllegalArgumentException("Amount must not be negative: " + maxAmount);
        long inserted = 0;
        // First pass: parts that already hold something, so stacks fill up before new slots are used.
        for (ResourceHandler part : this.parts) {
            if (inserted == maxAmount) return inserted;
            if (!isEmpty(part)) inserted += part.insert(variant, maxAmount - inserted, transaction);
        }
        for (ResourceHandler part : this.parts) {
            if (inserted == maxAmount) return inserted;
            if (isEmpty(part)) inserted += part.insert(variant, maxAmount - inserted, transaction);
        }
        return inserted;
    }

    @Override
    public long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
        if (maxAmount < 0) throw new IllegalArgumentException("Amount must not be negative: " + maxAmount);
        long extracted = 0;
        for (ResourceHandler part : this.parts) {
            if (extracted == maxAmount) break;
            extracted += part.extract(variant, maxAmount - extracted, transaction);
        }
        return extracted;
    }

    @Override
    public @NotNull List<StorageView> views() {
        List<StorageView> views = new ArrayList<>();
        for (ResourceHandler part : this.parts) views.addAll(part.views());
        return views;
    }

    @Override
    public boolean supportsInsertion() {
        return this.parts.stream().anyMatch(ResourceHandler::supportsInsertion);
    }

    @Override
    public boolean supportsExtraction() {
        return this.parts.stream().anyMatch(ResourceHandler::supportsExtraction);
    }

    private static boolean isEmpty(ResourceHandler part) {
        for (StorageView view : part.views()) {
            if (!view.isEmpty()) return false;
        }
        return true;
    }
}
