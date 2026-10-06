package fr.robie.machineengine.api.error;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/** Either a value or a {@link MachineError}. */
public sealed interface Result<T> permits Result.Ok, Result.Err {

    static <T> @NotNull Result<T> ok(@NotNull T value) {
        return new Ok<>(value);
    }

    static <T> @NotNull Result<T> err(@NotNull MachineError error) {
        return new Err<>(error);
    }

    boolean isOk();

    default boolean isErr() {
        return !isOk();
    }

    /** @throws IllegalStateException if this is an error */
    @NotNull T value();

    /** @throws IllegalStateException if this is a value */
    @NotNull MachineError error();

    default @NotNull Optional<T> toOptional() {
        return isOk() ? Optional.of(value()) : Optional.empty();
    }

    default @NotNull T orElse(@NotNull T fallback) {
        return isOk() ? value() : fallback;
    }

    /** @throws ResultException wrapping the error */
    default @NotNull T orElseThrow() {
        if (isOk()) return value();
        throw new ResultException(error());
    }

    @SuppressWarnings("unchecked")
    default <R> @NotNull Result<R> map(@NotNull Function<? super T, ? extends R> mapper) {
        return isOk() ? ok(mapper.apply(value())) : (Result<R>) this;
    }

    @SuppressWarnings("unchecked")
    default <R> @NotNull Result<R> flatMap(@NotNull Function<? super T, Result<R>> mapper) {
        return isOk() ? mapper.apply(value()) : (Result<R>) this;
    }

    default @NotNull Result<T> mapError(@NotNull UnaryOperator<MachineError> mapper) {
        return isOk() ? this : err(mapper.apply(error()));
    }

    default @NotNull Result<T> ifOk(@NotNull Consumer<? super T> action) {
        if (isOk()) action.accept(value());
        return this;
    }

    default @NotNull Result<T> ifErr(@NotNull Consumer<? super MachineError> action) {
        if (isErr()) action.accept(error());
        return this;
    }

    record Ok<T>(@NotNull T value) implements Result<T> {
        public Ok {
            Objects.requireNonNull(value);
        }

        @Override
        public boolean isOk() {
            return true;
        }

        @Override
        public @NotNull MachineError error() {
            throw new IllegalStateException("Result is a value: " + this.value);
        }
    }

    record Err<T>(@NotNull MachineError error) implements Result<T> {
        public Err {
            Objects.requireNonNull(error);
        }

        @Override
        public boolean isOk() {
            return false;
        }

        @Override
        public @NotNull T value() {
            throw new ResultException(this.error);
        }
    }

    /** Thrown when the value of an error result is requested. */
    final class ResultException extends IllegalStateException {
        private final transient MachineError error;

        public ResultException(@NotNull MachineError error) {
            super(error.format());
            this.error = error;
        }

        public @NotNull MachineError error() {
            return this.error;
        }
    }
}
