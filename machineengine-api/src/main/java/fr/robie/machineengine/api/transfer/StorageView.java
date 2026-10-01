package fr.robie.machineengine.api.transfer;

import fr.robie.machineengine.api.resource.ResourceVariant;
import org.jetbrains.annotations.Nullable;

/**
 * A read-only snapshot of one storage slot.
 *
 * @param variant  what the slot holds, or {@code null} if it is empty
 * @param amount   how much it holds
 * @param capacity how much it can hold
 */
public record StorageView(@Nullable ResourceVariant variant, long amount, long capacity) {

    public boolean isEmpty() {
        return this.variant == null || this.amount == 0;
    }
}
