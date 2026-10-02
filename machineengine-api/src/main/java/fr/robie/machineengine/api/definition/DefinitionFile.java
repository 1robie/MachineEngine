package fr.robie.machineengine.api.definition;

import fr.robie.machineengine.api.data.DataMap;
import org.jetbrains.annotations.NotNull;

/**
 * One data-driven definition file, already parsed into a format-neutral tree.
 *
 * @param name where it came from, for diagnostics (e.g. {@code definitions/furnace.yml})
 */
public record DefinitionFile(@NotNull String name, @NotNull DataMap content) {
}
