package fr.robie.machineengine.api.data;

import fr.robie.machineengine.api.error.MachineError.SerializationError;
import fr.robie.machineengine.api.error.Result;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** An immutable string-keyed map of {@link DataValue}s, preserving insertion order. */
public final class DataMap implements DataValue {
    public static final DataMap EMPTY = new DataMap(Map.of());

    private final Map<String, DataValue> entries;

    private DataMap(Map<String, DataValue> entries) {
        this.entries = entries;
    }

    public static @NotNull DataMap of(@NotNull Map<String, ? extends DataValue> entries) {
        return entries.isEmpty() ? EMPTY : new DataMap(Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    @Override
    public @NotNull String kind() {
        return "map";
    }

    public @NotNull Map<String, DataValue> entries() {
        return this.entries;
    }

    public @NotNull Set<String> keys() {
        return this.entries.keySet();
    }

    public int size() {
        return this.entries.size();
    }

    public boolean has(@NotNull String key) {
        return this.entries.containsKey(key);
    }

    public @NotNull Optional<DataValue> get(@NotNull String key) {
        return Optional.ofNullable(this.entries.get(key));
    }

    public @NotNull Result<DataValue> require(@NotNull String key) {
        DataValue value = this.entries.get(key);
        return value == null ? Result.err(SerializationError.at(key, "missing required field")) : Result.ok(value);
    }

    public @NotNull Result<String> getString(@NotNull String key) {
        return require(key).flatMap(value -> value instanceof DataString string
                ? Result.ok(string.value()) : wrongKind(key, "string", value));
    }

    public @NotNull Result<Long> getLong(@NotNull String key) {
        return require(key).flatMap(value -> value instanceof DataLong number
                ? Result.ok(number.value()) : wrongKind(key, "integer", value));
    }

    public @NotNull Result<Integer> getInt(@NotNull String key) {
        return getLong(key).flatMap(number -> number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                ? Result.err(SerializationError.at(key, "integer " + number + " is out of range"))
                : Result.ok(number.intValue()));
    }

    public @NotNull Result<Boolean> getBool(@NotNull String key) {
        return require(key).flatMap(value -> value instanceof DataBool bool
                ? Result.ok(bool.value()) : wrongKind(key, "boolean", value));
    }

    public @NotNull Result<DataMap> getMap(@NotNull String key) {
        return require(key).flatMap(value -> value instanceof DataMap map
                ? Result.ok(map) : wrongKind(key, "map", value));
    }

    public @NotNull Result<DataList> getList(@NotNull String key) {
        return require(key).flatMap(value -> value instanceof DataList list
                ? Result.ok(list) : wrongKind(key, "list", value));
    }

    /** @return a copy with {@code key} set to {@code value} */
    public @NotNull DataMap with(@NotNull String key, @NotNull DataValue value) {
        Map<String, DataValue> copy = new LinkedHashMap<>(this.entries);
        copy.put(key, value);
        return new DataMap(Collections.unmodifiableMap(copy));
    }

    /** @return a copy without {@code key} */
    public @NotNull DataMap without(@NotNull String key) {
        if (!this.entries.containsKey(key)) return this;
        Map<String, DataValue> copy = new LinkedHashMap<>(this.entries);
        copy.remove(key);
        return of(copy);
    }

    private static <T> Result<T> wrongKind(String key, String expected, DataValue actual) {
        return Result.err(SerializationError.at(key, "expected " + expected + " but found " + actual.kind()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DataMap map && this.entries.equals(map.entries);
    }

    @Override
    public int hashCode() {
        return this.entries.hashCode();
    }

    @Override
    public String toString() {
        return "DataMap" + this.entries;
    }

    public static final class Builder {
        private final Map<String, DataValue> entries = new LinkedHashMap<>();

        private Builder() {
        }

        public @NotNull Builder put(@NotNull String key, @NotNull DataValue value) {
            this.entries.put(key, value);
            return this;
        }

        public @NotNull Builder put(@NotNull String key, @NotNull String value) {
            return put(key, DataValue.of(value));
        }

        public @NotNull Builder put(@NotNull String key, long value) {
            return put(key, DataValue.of(value));
        }

        public @NotNull Builder put(@NotNull String key, boolean value) {
            return put(key, DataValue.of(value));
        }

        public @NotNull DataMap build() {
            return of(this.entries);
        }
    }
}
