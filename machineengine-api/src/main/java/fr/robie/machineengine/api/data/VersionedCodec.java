package fr.robie.machineengine.api.data;

import fr.robie.machineengine.api.error.MachineError.SerializationError;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A registered, versioned codec (ARCHITECTURE.md §20.3). Encoded data is wrapped as
 * {@code {v: <version>, data: <payload>}}; decoding runs the migrations from the stored version
 * up to {@link #version()} first.
 *
 * <p>Data newer than {@link #version()} is never decoded (no downgrades); callers keep it opaque.</p>
 */
public final class VersionedCodec<T> implements Keyed {
    public static final String VERSION_FIELD = "v";
    public static final String DATA_FIELD = "data";

    private final Key key;
    private final int version;
    private final StorageCodec<T> codec;
    private final List<DataMigration> migrations;

    private VersionedCodec(Key key, int version, StorageCodec<T> codec, List<DataMigration> migrations) {
        this.key = key;
        this.version = version;
        this.codec = codec;
        this.migrations = migrations.stream().sorted(Comparator.comparingInt(DataMigration::fromVersion)).toList();
    }

    public static <T> @NotNull Builder<T> builder(@NotNull Key key, @NotNull StorageCodec<T> codec) {
        return new Builder<>(key, codec);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    /** Current data version, starting at 1. */
    public int version() {
        return this.version;
    }

    public @NotNull StorageCodec<T> codec() {
        return this.codec;
    }

    public @NotNull List<DataMigration> migrations() {
        return this.migrations;
    }

    /** Oldest data version that can still be decoded. */
    public int oldestSupportedVersion() {
        int oldest = this.version;
        for (int i = this.migrations.size() - 1; i >= 0; i--) {
            if (this.migrations.get(i).fromVersion() != oldest - 1) break;
            oldest--;
        }
        return oldest;
    }

    public @NotNull DataMap encode(@NotNull T value) {
        return DataMap.builder()
                .put(VERSION_FIELD, this.version)
                .put(DATA_FIELD, this.codec.encode(value))
                .build();
    }

    public @NotNull Result<T> decode(@NotNull DataValue data) {
        if (!(data instanceof DataMap map)) {
            return Result.err(SerializationError.at("", "expected versioned data (map) but found " + data.kind()));
        }
        Result<Integer> storedVersion = map.getInt(VERSION_FIELD);
        if (storedVersion.isErr()) return Result.err(storedVersion.error());
        Result<DataValue> payload = map.require(DATA_FIELD);
        if (payload.isErr()) return Result.err(payload.error());
        return migrate(storedVersion.value(), payload.value()).flatMap(this.codec::decode);
    }

    private Result<DataValue> migrate(int from, DataValue payload) {
        if (from > this.version) {
            return Result.err(SerializationError.at(VERSION_FIELD, "data version " + from
                    + " is newer than " + this.key.asString() + " version " + this.version));
        }
        DataValue current = payload;
        for (int version = from; version < this.version; version++) {
            DataMigration migration = migrationFrom(version);
            if (migration == null) {
                return Result.err(SerializationError.at(VERSION_FIELD, "no migration from data version " + version
                        + " of " + this.key.asString()));
            }
            Result<DataValue> migrated = migration.apply(current);
            if (migrated.isErr()) return migrated;
            current = migrated.value();
        }
        return Result.ok(current);
    }

    private DataMigration migrationFrom(int version) {
        for (DataMigration migration : this.migrations) {
            if (migration.fromVersion() == version) return migration;
        }
        return null;
    }

    /** @return problems with this codec's definition; empty if it is valid */
    public @NotNull List<String> definitionProblems() {
        List<String> problems = new ArrayList<>();
        if (this.version < 1) problems.add("version must be at least 1 but is " + this.version);
        int previous = Integer.MIN_VALUE;
        for (DataMigration migration : this.migrations) {
            int from = migration.fromVersion();
            if (from == previous) problems.add("two migrations from version " + from);
            if (from < 1 || from >= this.version) {
                problems.add("migration from version " + from + " is outside 1.." + (this.version - 1));
            }
            if (previous != Integer.MIN_VALUE && from > previous + 1) {
                problems.add("no migration from version " + (previous + 1) + " (gap between " + previous + " and " + from + ")");
            }
            previous = from;
        }
        if (!this.migrations.isEmpty() && previous != this.version - 1) {
            problems.add("migrations stop at version " + (previous + 1) + " but the codec is at version " + this.version);
        }
        return problems;
    }

    @Override
    public String toString() {
        return "VersionedCodec[" + this.key.asString() + " v" + this.version + "]";
    }

    public static final class Builder<T> {
        private final Key key;
        private final StorageCodec<T> codec;
        private int version = 1;
        private final List<DataMigration> migrations = new ArrayList<>();

        private Builder(Key key, StorageCodec<T> codec) {
            this.key = Objects.requireNonNull(key);
            this.codec = Objects.requireNonNull(codec);
        }

        public @NotNull Builder<T> version(int version) {
            this.version = version;
            return this;
        }

        /** Adds the upgrade from {@code fromVersion} to {@code fromVersion + 1}. */
        public @NotNull Builder<T> migrate(int fromVersion, @NotNull Function<DataValue, Result<DataValue>> migration) {
            this.migrations.add(new DataMigration(fromVersion, migration));
            return this;
        }

        public @NotNull VersionedCodec<T> build() {
            return new VersionedCodec<>(this.key, this.version, this.codec, this.migrations);
        }
    }
}
