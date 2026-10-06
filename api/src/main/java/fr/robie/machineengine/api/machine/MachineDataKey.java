package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.data.VersionedCodec;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A persisted value of a machine's own state (counters, modes, settings). Declared on the
 * {@link MachineType}; read and written through {@link MachineContext#get}/{@link MachineContext#set}.
 * Values must be immutable.
 *
 * @param id           unique within the machine type
 * @param codec        how the value is persisted and migrated
 * @param defaultValue the value of a newly placed machine
 */
public record MachineDataKey<T>(@NotNull String id, @NotNull VersionedCodec<T> codec, @NotNull T defaultValue) {

    public MachineDataKey {
        Objects.requireNonNull(id);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(defaultValue);
    }

    public static <T> @NotNull MachineDataKey<T> of(@NotNull String id, @NotNull VersionedCodec<T> codec, @NotNull T defaultValue) {
        return new MachineDataKey<>(id, codec, defaultValue);
    }
}
