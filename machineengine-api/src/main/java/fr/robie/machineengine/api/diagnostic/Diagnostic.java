package fr.robie.machineengine.api.diagnostic;

import fr.robie.machineengine.api.addon.AddonId;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One validation finding (ARCHITECTURE.md §24).
 *
 * @param severity how serious it is
 * @param code     stable machine-readable code, e.g. {@code machineengine:duplicate_key}
 * @param message  human-readable explanation
 * @param source   the addon responsible, if known. Errors disable that addon's content.
 * @param subject  the entry concerned, if any
 * @param location where it was defined (file, path), if known
 * @param context  extra key/value details for debugging
 */
public record Diagnostic(@NotNull Severity severity, @NotNull Key code, @NotNull String message,
                         @Nullable AddonId source, @Nullable Key subject, @Nullable String location,
                         @NotNull Map<String, Object> context) {

    public Diagnostic {
        Objects.requireNonNull(severity);
        Objects.requireNonNull(code);
        Objects.requireNonNull(message);
        context = Map.copyOf(context);
    }

    public static @NotNull Builder error(@NotNull Key code, @NotNull String message) {
        return new Builder(Severity.ERROR, code, message);
    }

    public static @NotNull Builder warning(@NotNull Key code, @NotNull String message) {
        return new Builder(Severity.WARNING, code, message);
    }

    public static @NotNull Builder info(@NotNull Key code, @NotNull String message) {
        return new Builder(Severity.INFO, code, message);
    }

    public boolean isError() {
        return this.severity == Severity.ERROR;
    }

    /** @return a copy attributed to {@code addon} */
    public @NotNull Diagnostic withSource(@Nullable AddonId addon) {
        return new Diagnostic(this.severity, this.code, this.message, addon, this.subject, this.location, this.context);
    }

    /** One-line, log-friendly rendering. */
    public @NotNull String format() {
        StringBuilder builder = new StringBuilder()
                .append('[').append(this.severity).append("] ")
                .append(this.code.asString()).append(": ").append(this.message);
        if (this.source != null) builder.append(" (addon ").append(this.source).append(')');
        if (this.subject != null) builder.append(" [").append(this.subject.asString()).append(']');
        if (this.location != null) builder.append(" at ").append(this.location);
        if (!this.context.isEmpty()) builder.append(' ').append(this.context);
        return builder.toString();
    }

    public static final class Builder {
        private final Severity severity;
        private final Key code;
        private final String message;
        private AddonId source;
        private Key subject;
        private String location;
        private final Map<String, Object> context = new LinkedHashMap<>();

        private Builder(Severity severity, Key code, String message) {
            this.severity = severity;
            this.code = code;
            this.message = message;
        }

        public @NotNull Builder source(@Nullable AddonId source) {
            this.source = source;
            return this;
        }

        public @NotNull Builder subject(@Nullable Key subject) {
            this.subject = subject;
            return this;
        }

        public @NotNull Builder location(@Nullable String location) {
            this.location = location;
            return this;
        }

        public @NotNull Builder with(@NotNull String key, @NotNull Object value) {
            this.context.put(key, value);
            return this;
        }

        public @NotNull Diagnostic build() {
            return new Diagnostic(this.severity, this.code, this.message, this.source, this.subject, this.location, this.context);
        }
    }
}
