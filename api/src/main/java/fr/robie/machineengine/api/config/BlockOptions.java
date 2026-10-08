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
 * The numbers a block's config gives its machine (D34): storage capacities ({@code storages:}), port
 * rates ({@code ports:}) and behavior options ({@code options:}), read with the same {@link ConfigKey}s as
 * a config file.
 *
 * <pre>{@code
 * behavior:
 *   type: machineengine:machine
 *   machine: mymod:refinery
 *   storages:
 *     energy: { capacity: 40000 }
 *   ports:
 *     out: { rate: 4000 }
 *   options:
 *     energy-per-tick: 200
 * }</pre>
 *
 * <p>The machine's code declares each option as a key with its default and range, and reads it with
 * {@link #get}. A missing option is the key's default; an invalid one is reported once and replaced by
 * the default (or limited to the range, for a {@link ConfigKey#clamped()} key).</p>
 */
public final class BlockOptions {
    public static final BlockOptions EMPTY = new BlockOptions("", Map.of(), Map.of(), Map.of(), message -> {
    });

    private final String where;
    private final Map<String, Object> options;
    private final Map<String, Long> capacities;
    private final Map<String, Long> rates;
    private final Consumer<String> warnings;
    private final Map<String, Object> read = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    private BlockOptions(String where, Map<String, Object> options, Map<String, Long> capacities, Map<String, Long> rates,
                         Consumer<String> warnings) {
        this.where = where;
        this.options = options;
        this.capacities = capacities;
        this.rates = rates;
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
        return of(where, options, storages, null, warnings);
    }

    /**
     * Options read from a block config, with port rates.
     *
     * @param ports the {@code ports:} map, each port a map with {@code rate} (per tick), or {@code null}
     */
    public static @NotNull BlockOptions of(@NotNull String where, @Nullable Map<String, ?> options, @Nullable Map<String, ?> storages,
                                           @Nullable Map<String, ?> ports, @NotNull Consumer<String> warnings) {
        Objects.requireNonNull(where);
        Objects.requireNonNull(warnings);
        Map<String, Long> capacities = numbers(where, "storages", "capacity", storages, warnings);
        Map<String, Long> rates = numbers(where, "ports", "rate", ports, warnings);
        Map<String, Object> copy = options == null ? Map.of() : Map.copyOf(options);
        if (copy.isEmpty() && capacities.isEmpty() && rates.isEmpty()) return EMPTY;
        return new BlockOptions(where, copy, capacities, rates, warnings);
    }

    /** {@code section.<id>.<field>} for each id: a positive whole number, or warned about and left out. */
    private static Map<String, Long> numbers(String where, String section, String field, @Nullable Map<String, ?> entries,
                                             Consumer<String> warnings) {
        if (entries == null) return Map.of();
        ConfigKey<Long> number = ConfigKey.whole(field, 1, 1, Long.MAX_VALUE);
        Map<String, Long> parsed = new LinkedHashMap<>();
        entries.forEach((id, raw) -> {
            Object value = raw instanceof Map<?, ?> map ? map.get(field) : null;
            Long read = number.parse(value);
            if (read == null) {
                warnings.accept(where + ": " + section + "." + id + "." + field + " must be " + number.expected() + " but is "
                        + value + "; using the machine's own");
            } else {
                parsed.put(id, read);
            }
        });
        return Map.copyOf(parsed);
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

    /** The port's rate per tick from the block config, or {@code fallback} (the machine type's). */
    public long portRate(@NotNull String port, long fallback) {
        Long rate = this.rates.get(port);
        return rate != null ? rate : fallback;
    }

    /** True when the block config sets no option, capacity or port rate. */
    public boolean isEmpty() {
        return this.options.isEmpty() && this.capacities.isEmpty() && this.rates.isEmpty();
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
