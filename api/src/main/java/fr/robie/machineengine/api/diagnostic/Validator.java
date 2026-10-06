package fr.robie.machineengine.api.diagnostic;

import org.jetbrains.annotations.NotNull;

/**
 * Validates registered content. Runs after registration and after every reload, before anything
 * freezes or swaps. Report problems through the context; do not throw.
 */
@FunctionalInterface
public interface Validator {

    void validate(@NotNull ValidationContext context);
}
