package fr.robie.machineengine.api.item;

import net.kyori.adventure.key.Key;
import net.momirealms.craftengine.core.item.Item;
import org.jetbrains.annotations.NotNull;

/**
 * Implemented by a CraftEngine item behavior that changes how much its item can hold, for example an
 * installed module that multiplies the energy capacity. {@link ResourceItem#capacity} asks every such
 * behavior on the item, in the order of its {@code behaviors:} list.
 */
@FunctionalInterface
public interface ItemCapacityModifier {

    /** @return the capacity of {@code resource} in {@code item}, given the capacity so far */
    long modify(@NotNull Item item, @NotNull Key resource, long capacity);
}
