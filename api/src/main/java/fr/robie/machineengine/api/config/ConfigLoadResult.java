package fr.robie.machineengine.api.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What a load or reload of a {@link ConfigFile} did. Lists hold key paths.
 *
 * @param created         the file did not exist and was written with every default
 * @param added           keys missing from the file, now written with their defaults
 * @param changed         keys whose value changed on this reload
 * @param restartRequired restart-only keys changed in the file; they keep their old value until a restart
 * @param invalid         keys with an invalid value; they use their default and the file is left as it is
 * @param error           why the file could not be read (every value is then kept, or the default on the first load)
 */
public record ConfigLoadResult(boolean created, @NotNull List<String> added, @NotNull List<String> changed,
                               @NotNull List<String> restartRequired, @NotNull List<String> invalid, @Nullable String error) {
    public ConfigLoadResult {
        added = List.copyOf(added);
        changed = List.copyOf(changed);
        restartRequired = List.copyOf(restartRequired);
        invalid = List.copyOf(invalid);
    }
}
