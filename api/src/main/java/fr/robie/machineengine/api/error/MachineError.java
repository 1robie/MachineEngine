package fr.robie.machineengine.api.error;

import fr.robie.machineengine.api.MachineEngineKeys;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An expected failure, returned in a {@link Result} instead of thrown (ARCHITECTURE.md §25).
 * Exceptions are reserved for programmer errors.
 *
 * <p>The standard families are the nested records. Addons can define their own errors by
 * implementing this interface with a {@link #code()} in their namespace.</p>
 */
public interface MachineError {

    /** Stable machine-readable code, e.g. {@code machineengine:insufficient_resource}. */
    @NotNull Key code();

    @NotNull String message();

    /** Debugging details. */
    default @NotNull Map<String, Object> context() {
        return Map.of();
    }

    default @NotNull String format() {
        Map<String, Object> context = context();
        return code().asString() + ": " + message() + (context.isEmpty() ? "" : " " + context);
    }

    static @NotNull MachineError of(@NotNull Key code, @NotNull String message) {
        return new Generic(code, message, Map.of());
    }

    /** An error with a free-form code. */
    record Generic(@NotNull Key code, @NotNull String message, @NotNull Map<String, Object> context) implements MachineError {
        public Generic {
            context = Map.copyOf(context);
        }
    }

    /** A definition (type, recipe, port …) is invalid. */
    record InvalidDefinition(@NotNull Key subject, @NotNull String message) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("invalid_definition");

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull Map<String, Object> context() {
            return Map.of("subject", this.subject.asString());
        }
    }

    /** Something referenced by key is not registered (e.g. data written by a removed addon). */
    record MissingResource(@NotNull Key registry, @NotNull Key id) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("missing_resource");

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull String message() {
            return "No " + this.registry.asString() + " entry '" + this.id.asString() + "' is registered";
        }
    }

    /** Less than required was available. */
    record InsufficientResource(@NotNull Key resource, long required, long available) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("insufficient_resource");

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull String message() {
            return "Needed " + this.required + " of " + this.resource.asString() + " but only " + this.available + " was available";
        }
    }

    /** A handler refused a transfer. */
    record TransferRejected(@NotNull String message, @Nullable Key resource) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("transfer_rejected");

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull Map<String, Object> context() {
            return this.resource == null ? Map.of() : Map.of("resource", this.resource.asString());
        }
    }

    /** Data could not be encoded or decoded. */
    record SerializationError(@NotNull String message, @NotNull String path) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("serialization_error");

        public static @NotNull SerializationError at(@NotNull String path, @NotNull String message) {
            return new SerializationError(message, path);
        }

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull Map<String, Object> context() {
            return this.path.isEmpty() ? Map.of() : Map.of("path", this.path);
        }

        /** @return this error with {@code segment} prepended to its path */
        public @NotNull SerializationError under(@NotNull String segment) {
            return new SerializationError(this.message, this.path.isEmpty() ? segment : segment + "." + this.path);
        }
    }

    /** Invalid configuration value. */
    record InvalidConfiguration(@NotNull String message, @NotNull Map<String, Object> details) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("invalid_configuration");

        public InvalidConfiguration {
            details = Map.copyOf(details);
        }

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull Map<String, Object> context() {
            return this.details;
        }
    }

    /** Called from a thread that does not own the target (§16). */
    record WrongThread(@NotNull String operation, @NotNull String thread) implements MachineError {
        public static final Key CODE = MachineEngineKeys.key("wrong_thread");

        @Override
        public @NotNull Key code() {
            return CODE;
        }

        @Override
        public @NotNull String message() {
            return this.operation + " was called from thread '" + this.thread + "', which does not own it";
        }
    }

    /** Builds a context map from alternating keys and values. */
    static @NotNull Map<String, Object> details(@NotNull Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
