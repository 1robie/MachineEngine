package fr.robie.machineengine.api.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One setting of a {@link ConfigFile}: its path ({@code limits.max-machines-per-chunk}), its type,
 * its default, the comment written above it, and whether a reload may change it.
 *
 * <p>Keys are immutable: {@link #comment} and {@link #restartOnly} return a new key. Declare them as
 * constants and read them with {@link ConfigValues#get}.</p>
 */
public final class ConfigKey<T> {
    private final String path;
    private final T defaultValue;
    private final Function<Object, T> parser;
    private final Function<T, Object> writer;
    private final String expected;
    private final List<String> comment;
    private final boolean restartOnly;

    private ConfigKey(String path, T defaultValue, Function<Object, T> parser, Function<T, Object> writer, String expected,
                      List<String> comment, boolean restartOnly) {
        this.path = path;
        this.defaultValue = defaultValue;
        this.parser = parser;
        this.writer = writer;
        this.expected = expected;
        this.comment = comment;
        this.restartOnly = restartOnly;
    }

    private static <T> ConfigKey<T> of(String path, T defaultValue, Function<Object, T> parser, Function<T, Object> writer, String expected) {
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("invalid config path '" + path + "'");
        }
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (parser.apply(writer.apply(defaultValue)) == null) {
            throw new IllegalArgumentException("the default of " + path + " must be " + expected + " but is " + defaultValue);
        }
        return new ConfigKey<>(path, defaultValue, parser, writer, expected, List.of(), false);
    }

    /** Text. Numbers and booleans written without quotes are read as text too. */
    public static @NotNull ConfigKey<String> string(@NotNull String path, @NotNull String defaultValue) {
        return of(path, defaultValue, raw -> scalar(raw), value -> value, "text");
    }

    public static @NotNull ConfigKey<Boolean> bool(@NotNull String path, boolean defaultValue) {
        return of(path, defaultValue, raw -> raw instanceof Boolean value ? value : null, value -> value, "true or false");
    }

    public static @NotNull ConfigKey<Integer> integer(@NotNull String path, int defaultValue, int min, int max) {
        return of(path, defaultValue, raw -> {
            Long value = whole(raw, min, max);
            return value == null ? null : value.intValue();
        }, value -> value, range("a whole number", min, max, Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    public static @NotNull ConfigKey<Long> whole(@NotNull String path, long defaultValue, long min, long max) {
        return of(path, defaultValue, raw -> whole(raw, min, max), value -> value,
                range("a whole number", min, max, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    public static @NotNull ConfigKey<Double> decimal(@NotNull String path, double defaultValue, double min, double max) {
        return of(path, defaultValue, raw -> {
            if (!(raw instanceof Number number)) return null;
            double value = number.doubleValue();
            return Double.isFinite(value) && value >= min && value <= max ? value : null;
        }, value -> value, range("a number", min, max, -Double.MAX_VALUE, Double.MAX_VALUE));
    }

    /** One of the constants of {@code type}, written in lower case with dashes: {@code FAST_MODE} is {@code fast-mode}. */
    public static <E extends Enum<E>> @NotNull ConfigKey<E> choice(@NotNull String path, @NotNull E defaultValue) {
        Class<E> type = defaultValue.getDeclaringClass();
        String names = Arrays.stream(type.getEnumConstants()).map(ConfigKey::enumName).collect(Collectors.joining(", "));
        return of(path, defaultValue, raw -> {
            String text = scalar(raw);
            if (text == null) return null;
            for (E constant : type.getEnumConstants()) {
                if (enumName(constant).equalsIgnoreCase(text.trim().replace('_', '-').replace(' ', '-'))) return constant;
            }
            return null;
        }, ConfigKey::enumName, "one of: " + names);
    }

    /** A list of texts. */
    public static @NotNull ConfigKey<List<String>> strings(@NotNull String path, @NotNull List<String> defaultValue) {
        return of(path, List.copyOf(defaultValue), raw -> {
            if (!(raw instanceof List<?> list)) return null;
            List<String> values = new ArrayList<>(list.size());
            for (Object element : list) {
                String text = scalar(element);
                if (text == null) return null;
                values.add(text);
            }
            return List.copyOf(values);
        }, value -> value, "a list of texts");
    }

    /** A section of {@code name: text} entries, in file order. */
    public static @NotNull ConfigKey<Map<String, String>> map(@NotNull String path, @NotNull Map<String, String> defaultValue) {
        return of(path, ordered(defaultValue), raw -> {
            Map<?, ?> entries;
            if (raw instanceof ConfigurationSection section) entries = section.getValues(false);
            else if (raw instanceof Map<?, ?> map) entries = map;
            else return null;
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                String text = scalar(entry.getValue());
                if (text == null) return null;
                values.put(String.valueOf(entry.getKey()), text);
            }
            return ordered(values);
        }, value -> value, "a section of 'name: text' entries");
    }

    /** The comment written above the key when it is added to a file; replaces any earlier comment. */
    public @NotNull ConfigKey<T> comment(@NotNull String... lines) {
        return new ConfigKey<>(this.path, this.defaultValue, this.parser, this.writer, this.expected, List.of(lines), this.restartOnly);
    }

    /** A reload keeps the value the server started with and reports that a restart is needed. */
    public @NotNull ConfigKey<T> restartOnly() {
        return new ConfigKey<>(this.path, this.defaultValue, this.parser, this.writer, this.expected, this.comment, true);
    }

    public @NotNull String path() {
        return this.path;
    }

    public @NotNull T defaultValue() {
        return this.defaultValue;
    }

    public @NotNull List<String> comment() {
        return this.comment;
    }

    public boolean isRestartOnly() {
        return this.restartOnly;
    }

    /** What a valid value looks like, for warnings: "a whole number from 0 to 256". */
    public @NotNull String expected() {
        return this.expected;
    }

    /** The value read from YAML, or {@code null} if it is not valid for this key. */
    @Nullable T parse(@Nullable Object raw) {
        return raw == null ? null : this.parser.apply(raw);
    }

    /** The value as written to YAML. */
    @NotNull Object write(@NotNull T value) {
        return this.writer.apply(value);
    }

    @Override
    public String toString() {
        return "ConfigKey[" + this.path + "]";
    }

    private static @Nullable String scalar(@Nullable Object raw) {
        return raw instanceof String || raw instanceof Number || raw instanceof Boolean || raw instanceof Character ? String.valueOf(raw) : null;
    }

    private static @Nullable Long whole(Object raw, long min, long max) {
        long value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger) {
            return null; // beyond long
        } else if (raw instanceof Double || raw instanceof Float) {
            double decimal = ((Number) raw).doubleValue();
            if (decimal != Math.rint(decimal) || Math.abs(decimal) > 0x1p53) return null;
            value = (long) decimal;
        } else {
            return null;
        }
        return value >= min && value <= max ? value : null;
    }

    private static <N extends Comparable<N>> String range(String kind, N min, N max, N lowest, N highest) {
        boolean bottom = min.compareTo(lowest) > 0;
        boolean top = max.compareTo(highest) < 0;
        if (bottom && top) return kind + " from " + min + " to " + max;
        if (bottom) return kind + " of at least " + min;
        if (top) return kind + " of at most " + max;
        return kind;
    }

    private static String enumName(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Map<String, String> ordered(Map<String, String> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
