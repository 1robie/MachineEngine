package fr.robie.machineengine.api.config;

import fr.robie.machineengine.api.resource.ResourceType;
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
    /** For numbers: the value limited to the range, or null if it is not a number. */
    private final @Nullable Function<Object, T> clamp;
    private final boolean clamping;

    private ConfigKey(String path, T defaultValue, Function<Object, T> parser, Function<T, Object> writer, String expected,
                      List<String> comment, boolean restartOnly, @Nullable Function<Object, T> clamp, boolean clamping) {
        this.path = path;
        this.defaultValue = defaultValue;
        this.parser = parser;
        this.writer = writer;
        this.expected = expected;
        this.comment = comment;
        this.restartOnly = restartOnly;
        this.clamp = clamp;
        this.clamping = clamping;
    }

    private static <T> ConfigKey<T> of(String path, T defaultValue, Function<Object, T> parser, Function<T, Object> writer, String expected) {
        return of(path, defaultValue, parser, writer, expected, null);
    }

    private static <T> ConfigKey<T> of(String path, T defaultValue, Function<Object, T> parser, Function<T, Object> writer, String expected,
                                       @Nullable Function<Object, T> clamp) {
        if (path.isEmpty() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("invalid config path '" + path + "'");
        }
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (parser.apply(writer.apply(defaultValue)) == null) {
            throw new IllegalArgumentException("the default of " + path + " must be " + expected + " but is " + defaultValue);
        }
        return new ConfigKey<>(path, defaultValue, parser, writer, expected, List.of(), false, clamp, false);
    }

    /** Text. Numbers and booleans written without quotes are read as text too. */
    public static @NotNull ConfigKey<String> string(@NotNull String path, @NotNull String defaultValue) {
        return of(path, defaultValue, raw -> scalar(raw), value -> value, "text");
    }

    /** A color, written {@code "#RRGGBB"} (quoted in YAML); read and written as {@code 0xRRGGBB}. */
    public static @NotNull ConfigKey<Integer> color(@NotNull String path, int defaultValue) {
        return of(path, defaultValue & 0xFFFFFF, raw -> ResourceType.parseColor(scalar(raw)),
                value -> String.format("#%06X", value), "a color like \"#RRGGBB\"");
    }

    public static @NotNull ConfigKey<Boolean> bool(@NotNull String path, boolean defaultValue) {
        return of(path, defaultValue, raw -> raw instanceof Boolean value ? value : null, value -> value, "true or false");
    }

    public static @NotNull ConfigKey<Integer> integer(@NotNull String path, int defaultValue, int min, int max) {
        return of(path, defaultValue, raw -> {
            Long value = whole(raw, min, max);
            return value == null ? null : value.intValue();
        }, value -> value, range("a whole number", min, max, Integer.MIN_VALUE, Integer.MAX_VALUE), raw -> {
            Long value = clampWhole(raw, min, max);
            return value == null ? null : value.intValue();
        });
    }

    public static @NotNull ConfigKey<Long> whole(@NotNull String path, long defaultValue, long min, long max) {
        return of(path, defaultValue, raw -> whole(raw, min, max), value -> value,
                range("a whole number", min, max, Long.MIN_VALUE, Long.MAX_VALUE), raw -> clampWhole(raw, min, max));
    }

    public static @NotNull ConfigKey<Double> decimal(@NotNull String path, double defaultValue, double min, double max) {
        return of(path, defaultValue, raw -> {
            if (!(raw instanceof Number number)) return null;
            double value = number.doubleValue();
            return Double.isFinite(value) && value >= min && value <= max ? value : null;
        }, value -> value, range("a number", min, max, -Double.MAX_VALUE, Double.MAX_VALUE), raw -> {
            if (!(raw instanceof Number number) || Double.isNaN(number.doubleValue())) return null;
            return Math.max(min, Math.min(max, number.doubleValue()));
        });
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

    /** {@code size} whole numbers from {@code min} to {@code max} ({@code size} 0: any number of them). */
    public static @NotNull ConfigKey<List<Long>> wholes(@NotNull String path, @NotNull List<Long> defaultValue, int size, long min, long max) {
        String count = size > 0 ? size + " whole numbers" : "a list of whole numbers";
        return of(path, List.copyOf(defaultValue), raw -> {
            if (!(raw instanceof List<?> list) || (size > 0 && list.size() != size)) return null;
            List<Long> values = new ArrayList<>(list.size());
            for (Object element : list) {
                Long value = whole(element, min, max);
                if (value == null) return null;
                values.add(value);
            }
            return List.copyOf(values);
        }, value -> value, range(count + ", each", min, max, Long.MIN_VALUE, Long.MAX_VALUE));
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
        return new ConfigKey<>(this.path, this.defaultValue, this.parser, this.writer, this.expected, List.of(lines), this.restartOnly,
                this.clamp, this.clamping);
    }

    /** A reload keeps the value the server started with and reports that a restart is needed. */
    public @NotNull ConfigKey<T> restartOnly() {
        return new ConfigKey<>(this.path, this.defaultValue, this.parser, this.writer, this.expected, this.comment, true,
                this.clamp, this.clamping);
    }

    /**
     * A number out of range is limited to the nearest bound (with a warning) instead of replaced by the
     * default. Only for {@link #integer}, {@link #whole} and {@link #decimal} keys.
     */
    public @NotNull ConfigKey<T> clamped() {
        if (this.clamp == null) throw new IllegalStateException(this.path + " is not a number key, so it cannot be clamped");
        return new ConfigKey<>(this.path, this.defaultValue, this.parser, this.writer, this.expected, this.comment, this.restartOnly,
                this.clamp, true);
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

    /** For a {@link #clamped()} key, an out-of-range number limited to the range; else {@code null}. */
    @Nullable T clamp(@Nullable Object raw) {
        return this.clamping && raw != null && this.clamp != null ? this.clamp.apply(raw) : null;
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

    /** A whole number limited to the range; {@code null} if it is not a whole number. */
    private static @Nullable Long clampWhole(Object raw, long min, long max) {
        if (raw instanceof BigInteger big) return big.signum() < 0 ? min : max;
        Long value = whole(raw, Long.MIN_VALUE, Long.MAX_VALUE);
        return value == null ? null : Math.max(min, Math.min(max, value));
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
