package fr.robie.machineengine.api.recipe;

import fr.robie.machineengine.api.resource.ResourceStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * An extra recipe output produced with probability {@link #chance()} (e.g. sawdust from a sawmill).
 * Rolled once when the process starts, with the machine's own random, then kept with the process.
 */
public record ChanceOutput(@NotNull ResourceStack stack, double chance) {

    public ChanceOutput {
        Objects.requireNonNull(stack);
        if (!(chance >= 0 && chance <= 1)) throw new IllegalArgumentException("chance must be between 0 and 1, was " + chance);
    }
}
