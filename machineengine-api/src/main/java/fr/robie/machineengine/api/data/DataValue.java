package fr.robie.machineengine.api.data;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A format-neutral, immutable data tree (ARCHITECTURE.md §20.3). Platform adapters map it to their
 * storage format (CraftEngine: sparrow NBT); tests keep it in memory.
 */
public sealed interface DataValue
        permits DataMap, DataValue.DataList, DataValue.DataString, DataValue.DataLong,
                DataValue.DataDouble, DataValue.DataBool, DataValue.DataBytes {

    static @NotNull DataString of(@NotNull String value) {
        return new DataString(value);
    }

    static @NotNull DataLong of(long value) {
        return new DataLong(value);
    }

    static @NotNull DataDouble of(double value) {
        return new DataDouble(value);
    }

    static @NotNull DataBool of(boolean value) {
        return value ? DataBool.TRUE : DataBool.FALSE;
    }

    static @NotNull DataList list(@NotNull List<? extends DataValue> values) {
        return new DataList(List.copyOf(values));
    }

    /** @return a short name of the value kind, for error messages */
    @NotNull String kind();

    record DataString(@NotNull String value) implements DataValue {
        public DataString {
            Objects.requireNonNull(value);
        }

        @Override
        public @NotNull String kind() {
            return "string";
        }
    }

    record DataLong(long value) implements DataValue {
        @Override
        public @NotNull String kind() {
            return "integer";
        }
    }

    /** Never used for resource amounts (D10). */
    record DataDouble(double value) implements DataValue {
        @Override
        public @NotNull String kind() {
            return "decimal";
        }
    }

    record DataBool(boolean value) implements DataValue {
        public static final DataBool TRUE = new DataBool(true);
        public static final DataBool FALSE = new DataBool(false);

        @Override
        public @NotNull String kind() {
            return "boolean";
        }
    }

    record DataList(@NotNull List<DataValue> values) implements DataValue {
        public static final DataList EMPTY = new DataList(List.of());

        public DataList {
            values = List.copyOf(values);
        }

        @Override
        public @NotNull String kind() {
            return "list";
        }
    }

    /** Raw bytes; compared by content. */
    final class DataBytes implements DataValue {
        private final byte[] value;

        public DataBytes(byte @NotNull [] value) {
            this.value = value.clone();
        }

        public byte @NotNull [] value() {
            return this.value.clone();
        }

        @Override
        public @NotNull String kind() {
            return "bytes";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DataBytes bytes && Arrays.equals(this.value, bytes.value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.value);
        }

        @Override
        public String toString() {
            return "DataBytes[" + this.value.length + " bytes]";
        }
    }
}
