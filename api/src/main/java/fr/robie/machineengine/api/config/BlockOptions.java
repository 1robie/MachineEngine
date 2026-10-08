package fr.robie.machineengine.api.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The numbers a block's config gives its machine (D34): storage capacities ({@code storages:}) and
 * behavior options ({@code options:}), read with the same {@link ConfigKey}s as a config file.
 *
 * <pre>{@code
 * behavior:
 *   type: machineengine:machine
 *   machine: mymod:refinery
 *   storages:
 *     energy: { capacity: 40000 }
 *   options:
 *     energy-per-tick: 200
 * }</pre>
 *
 * <p>The machine's code declares each option as a key with its default and range, and reads it with
 * {@link #get}. A missing option is the key's default; an invalid one is reported once and replaced by
 * the default (or limited to the range, for a {@link ConfigKey#clamped()} key).</p>
 */
public final class BlockOptions {
    public static final BlockOptions EMPTY = new BlockOptions("", Map.of(), Map.of(), message -> {
    });

    private final String where;
    private final Map<String, Object> options;
    private final Map<String, Long> capacities;
    private final Consumer<String> warnings;
    private final Map<String, Object> read = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private BlockOptions(String where, Map<String, Object> options, Map<String, Long> capacities, Consumer<String> warnings) {
        this.where = where;
        this.options = options;
        this.capacities = capacities;
        this.warnings = warnings;
    }

    /**
     * Options read from a block config.
     *
     * @param where      names the block in warnings ({@code mymod:refinery})
     * @param options    the {@code options:} map (nested maps for dotted paths), or {@code null}
     * @param storages   the {@code storages:} map, each storage a map with {@code capacity}, or {@code null}
     * @param warnings   receives one line per invalid value
     */
    public static @NotNull BlockOptions of(@NotNull String where, @Nullable Map<String, ?> options, @Nullable Map<String, ?> storages,
                                           @NotNull Consumer<String> warnings) {
        Objects.requireNonNull(where);
        Objects.requireNonNull(warnings);
        Map<String, Long> capacities = new LinkedHashMap<>();
        if (storages != null) {
            ConfigKey<Long> capacity = ConfigKey.whole("capacity", 1, 1, Long.MAX_VALUE);
            storages.forEach((storage, raw) -> {
                Object value = raw instanceof Map<?, ?> map ? map.get("capacity") : null;
                Long parsed = capacity.parse(value);
                if (parsed == null) {
                    warnings.accept(where + ": storages." + storage + ".capacity must be " + capacity.expected() + " but is "
                            + value + "; using the machine's own");
                } else {
                    capacities.put(storage, parsed);
                }
            });
        }
        Map<String, Object> copy = options == null ? Map.of() : Map.copyOf(options);
        if (copy.isEmpty() && capacities.isEmpty()) return EMPTY;
        return new BlockOptions(where, copy, Map.copyOf(capacities), warnings);
    }

    /** The option's value from the block config, or the key's default. */
    @SuppressWarnings("unchecked")
    public <T> @NotNull T get(@NotNull ConfigKey<T> key) {
        if (this.options.isEmpty()) return key.defaultValue();
        return (T) this.read.computeIfAbsent(key.path(), path -> read(key));
    }

    /** The storage's capacity from the block config, or {@code fallback} (the machine type's). */
    public long capacity(@NotNull String storage, long fallback) {
        Long capacity = this.capacities.get(storage);
        return capacity != null ? capacity : fallback;
    }

    /** True when the block config sets no option and no capacity. */
    public boolean isEmpty() {
        return this.options.isEmpty() && this.capacities.isEmpty();
    }

    private <T> T read(ConfigKey<T> key) {
        Object raw = lookup(key.path());
        if (raw == null) return key.defaultValue();
        T value = key.parse(raw);
        if (value != null) return value;
        T clamped = key.clamp(raw);
        String path = "options." + key.path();
        if (clamped != null) {
            warn(path, path + " must be " + key.expected() + " but is " + raw + "; using " + clamped);
            return clamped;
        }
        warn(path, path + " must be " + key.expected() + " but is " + raw + "; using the default " + key.defaultValue());
        return key.defaultValue();
    }

    private @Nullable Object lookup(String path) {
        Object node = this.options;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) return null;
            node = map.get(part);
        }
        return node;
    }

    private void warn(String path, String message) {
        if (this.warned.add(path)) this.warnings.accept(this.where + ": " + message);
    }

    @Override
    public String toString() {
        return "BlockOptions[" + this.where + "]";
    }
}
