package fr.robie.machineengine.api.config;

import org.jetbrains.annotations.NotNull;

/** Read access to settings. Safe from any thread: a reload swaps all values at once. */
public interface ConfigValues {
    /**
     * The value of {@code key}: the file's if it is valid, else the key's default.
     *
     * @throws IllegalArgumentException if {@code key} is not part of these settings
     */
    <T> @NotNull T get(@NotNull ConfigKey<T> key);
}
