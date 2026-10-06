package fr.robie.machineengine.api.data;

import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * Encodes values to {@link DataValue}s and back. Decoding never throws for bad data; it returns an
 * error {@link Result} (usually a {@link MachineError.SerializationError}).
 *
 * <p>Persisted data must go through a {@link VersionedCodec} so it can be migrated later.</p>
 */
public interface StorageCodec<T> {

    @NotNull DataValue encode(@NotNull T value);

    @NotNull Result<T> decode(@NotNull DataValue data);

    static <T> @NotNull StorageCodec<T> of(@NotNull Function<? super T, ? extends DataValue> encoder,
                                           @NotNull Function<? super DataValue, Result<T>> decoder) {
        return new StorageCodec<>() {
            @Override
            public @NotNull DataValue encode(@NotNull T value) {
                return encoder.apply(value);
            }

            @Override
            public @NotNull Result<T> decode(@NotNull DataValue data) {
                return decoder.apply(data);
            }
        };
    }

    /** Maps this codec to another type with a total, two-way conversion. */
    default <R> @NotNull StorageCodec<R> xmap(@NotNull Function<? super T, ? extends R> to,
                                              @NotNull Function<? super R, ? extends T> from) {
        StorageCodec<T> self = this;
        return of(value -> self.encode(from.apply(value)), data -> self.decode(data).map(to));
    }

    /** Maps this codec to another type when decoding may fail. */
    default <R> @NotNull StorageCodec<R> flatXmap(@NotNull Function<? super T, Result<R>> to,
                                                  @NotNull Function<? super R, ? extends T> from) {
        StorageCodec<T> self = this;
        return of(value -> self.encode(from.apply(value)), data -> self.decode(data).flatMap(to));
    }
}
