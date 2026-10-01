package fr.robie.machineengine.api.upgrade;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Units of one upgrade type installed in a machine. */
public record InstalledUpgrade(@NotNull UpgradeType type, int count) {

    public InstalledUpgrade {
        Objects.requireNonNull(type);
        if (count < 1) throw new IllegalArgumentException("count must be at least 1");
    }
}
