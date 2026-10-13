package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.ValidationContext;
import fr.robie.machineengine.api.diagnostic.Validator;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Runs validators, attributing their findings and catching their failures. */
final class ValidationRunner {

    record OwnedValidator(@NotNull AddonId owner, @NotNull Validator validator) {
    }

    private ValidationRunner() {
    }

    static @NotNull List<Diagnostic> run(@NotNull RegistryAccess registries, @NotNull List<OwnedValidator> validators,
                                         @NotNull Logger logger) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (OwnedValidator owned : validators) {
            ValidationContext context = new ValidationContext() {
                @Override
                public @NotNull RegistryAccess registries() {
                    return registries;
                }

                @Override
                public void report(@NotNull Diagnostic diagnostic) {
                    diagnostics.add(diagnostic.source() == null ? diagnostic.withSource(owned.owner()) : diagnostic);
                }
            };
            try {
                owned.validator().validate(context);
            } catch (RuntimeException e) {
                logger.logError("[core]", "A validator of %owner% failed", e, Placeholder.of("owner", String.valueOf(owned.owner())));
                diagnostics.add(Diagnostic.error(DiagnosticCodes.VALIDATOR_FAILED,
                                "A validator threw " + e.getClass().getSimpleName() + ": " + e.getMessage())
                        .source(owned.owner()).build());
            }
        }
        return diagnostics;
    }
}
