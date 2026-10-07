package fr.robie.machineengine.api.item;

import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.core.item.Item;
import org.jetbrains.annotations.NotNull;

/**
 * Implemented by a CraftEngine item behavior whose item holds one resource: the engine's
 * {@code machineengine:energy_item} and {@code machineengine:resource_item}, or an addon's own (D32).
 * The amount lives in the item's data ({@link ItemResources}); the behavior only says what the item
 * holds and how much.
 *
 * <p>An item may hold several resources (energy and hydrogen): one behavior per resource.</p>
 */
public interface ResourceItem {

    /** The resource type this item holds, e.g. {@code machineengine:energy}. */
    @NotNull Key resource();

    /** How much the item holds when full, before {@link ItemCapacityModifier}s. */
    long baseCapacity();

    /** The most moved into the item per transfer (per tick for machines). */
    default long maxInput() {
        return Long.MAX_VALUE;
    }

    /** The most moved out of the item per transfer. */
    default long maxOutput() {
        return Long.MAX_VALUE;
    }

    /** {@link #baseCapacity()}, changed by the item's {@link ItemCapacityModifier} behaviors. */
    default long capacity(@NotNull Item item) {
        long[] capacity = {baseCapacity()};
        item.getBehavior().ifPresent(behavior -> behavior.let(ItemCapacityModifier.class,
                modifier -> capacity[0] = Math.max(0, modifier.modify(item, resource(), capacity[0]))));
        return capacity[0];
    }
}
