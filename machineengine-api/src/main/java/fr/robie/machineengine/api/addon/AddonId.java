package fr.robie.machineengine.api.addon;

import org.jetbrains.annotations.NotNull;

/**
 * Identifies an addon (normally the name of the plugin that registers content).
 * Every registration, listener and diagnostic is attributed to one.
 */
public record AddonId(@NotNull String name) implements Comparable<AddonId> {
    /** MachineEngine itself; reserved. */
    public static final AddonId MACHINE_ENGINE = new AddonId("MachineEngine");

    public AddonId {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Addon name must not be blank");
        }
    }

    @Override
    public int compareTo(@NotNull AddonId other) {
        return this.name.compareTo(other.name);
    }

    @Override
    public @NotNull String toString() {
        return this.name;
    }
}
