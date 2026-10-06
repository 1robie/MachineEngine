package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.diagnostic.Diagnostic;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a registration is impossible (duplicate key, frozen registry, wrong registry kind).
 * MachineEngine reports {@link #diagnostic()} and disables the registering addon's content.
 */
public final class RegistrationException extends RuntimeException {
    private final transient Diagnostic diagnostic;

    public RegistrationException(@NotNull Diagnostic diagnostic) {
        super(diagnostic.message());
        this.diagnostic = diagnostic;
    }

    public @NotNull Diagnostic diagnostic() {
        return this.diagnostic;
    }
}
