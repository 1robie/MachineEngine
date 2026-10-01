package fr.robie.machineengine.api.data;

import fr.robie.machineengine.api.data.DataValue.DataBool;
import fr.robie.machineengine.api.data.DataValue.DataBytes;
import fr.robie.machineengine.api.data.DataValue.DataList;
import fr.robie.machineengine.api.data.DataValue.DataLong;
import fr.robie.machineengine.api.data.DataValue.DataString;
import fr.robie.machineengine.api.error.MachineError.SerializationError;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Codecs for common value types. */
public final class Codecs {
    public static final StorageCodec<String> STRING = StorageCodec.of(DataValue::of,
            data -> data instanceof DataString string ? Result.ok(string.value()) : wrongKind("string", data));

    public static final StorageCodec<Long> LONG = StorageCodec.of(DataValue::of,
            data -> data instanceof DataLong number ? Result.ok(number.value()) : wrongKind("integer", data));

    public static final StorageCodec<Integer> INT = LONG.flatXmap(
            number -> number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                    ? Result.err(SerializationError.at("", "integer " + number + " is out of range"))
                    : Result.ok(number.intValue()),
            Integer::longValue);

    public static final StorageCodec<Boolean> BOOL = StorageCodec.of(DataValue::of,
            data -> data instanceof DataBool bool ? Result.ok(bool.value()) : wrongKind("boolean", data));

    public static final StorageCodec<byte[]> BYTES = StorageCodec.of(DataBytes::new,
            data -> data instanceof DataBytes bytes ? Result.ok(bytes.value()) : wrongKind("bytes", data));

    /** Keys are stored as their {@code namespace:value} string. */
    public static final StorageCodec<Key> KEY = STRING.flatXmap(Codecs::parseKey, Key::asString);

    private Codecs() {
    }

    public static <T> @NotNull StorageCodec<List<T>> list(@NotNull StorageCodec<T> element) {
        return StorageCodec.of(
                values -> DataValue.list(values.stream().map(element::encode).toList()),
                data -> {
                    if (!(data instanceof DataList list)) return wrongKind("list", data);
                    List<T> decoded = new ArrayList<>(list.values().size());
                    for (int i = 0; i < list.values().size(); i++) {
                        Result<T> result = element.decode(list.values().get(i));
                        if (result.isErr()) return Result.err(underIndex(result, i));
                        decoded.add(result.value());
                    }
                    return Result.ok(List.copyOf(decoded));
                });
    }

    public static @NotNull Result<Key> parseKey(@NotNull String text) {
        try {
            return Result.ok(Key.key(text));
        } catch (InvalidKeyException e) {
            return Result.err(SerializationError.at("", "invalid key '" + text + "': " + e.getMessage()));
        }
    }

    private static <T> fr.robie.machineengine.api.error.MachineError underIndex(Result<T> result, int index) {
        return result.error() instanceof SerializationError error ? error.under("[" + index + "]") : result.error();
    }

    private static <T> Result<T> wrongKind(String expected, DataValue actual) {
        return Result.err(SerializationError.at("", "expected " + expected + " but found " + actual.kind()));
    }
}
