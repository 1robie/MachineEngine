package fr.robie.machineengine.api.addon;

import fr.robie.machineengine.api.version.VersionRange;
import org.jetbrains.annotations.NotNull;

/**
 * A registration-time dependency. The dependent addon's callback runs after {@link #addon()}'s.
 * A required dependency must be present in a version within {@link #versions()}; an optional one
 * only orders registration when present.
 */
public record AddonDependency(@NotNull AddonId addon, @NotNull VersionRange versions, boolean optional) {
}
