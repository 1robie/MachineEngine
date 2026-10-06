package fr.robie.machineengine.api.diagnostic;

import fr.robie.machineengine.api.addon.AddonId;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Everything found in one validation pass. Validation reports all findings at once, not only the
 * first one (§24).
 */
public record ValidationReport(@NotNull List<Diagnostic> diagnostics) {
    public static final ValidationReport EMPTY = new ValidationReport(List.of());

    public ValidationReport {
        diagnostics = List.copyOf(diagnostics);
    }

    public static @NotNull ValidationReport of(@NotNull Collection<Diagnostic> diagnostics) {
        return new ValidationReport(List.copyOf(diagnostics));
    }

    public @NotNull List<Diagnostic> errors() {
        return this.diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    public @NotNull List<Diagnostic> warnings() {
        return this.diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).toList();
    }

    public boolean hasErrors() {
        return this.diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    /** @return addons that caused at least one error */
    public @NotNull Set<AddonId> failedAddons() {
        return this.diagnostics.stream()
                .filter(d -> d.isError() && d.source() != null)
                .map(Diagnostic::source)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public @NotNull ValidationReport merge(@NotNull ValidationReport other) {
        if (other.diagnostics.isEmpty()) return this;
        if (this.diagnostics.isEmpty()) return other;
        return new ValidationReport(Stream.concat(this.diagnostics.stream(), other.diagnostics.stream()).toList());
    }

    /** Multi-line, log-friendly rendering. */
    public @NotNull String format() {
        if (this.diagnostics.isEmpty()) return "No problems found.";
        return this.diagnostics.stream().map(Diagnostic::format).collect(Collectors.joining(System.lineSeparator()));
    }
}
