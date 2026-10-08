package fr.robie.machineengine.api.player;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.error.MachineError.SerializationError;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * A per-player value players change themselves (D37), with {@code /machinesettings} or the zMenu
 * settings menu. Registered with {@code RegistrationContext#playerSettings()}; read with
 * {@link MachinePlayer#get(PlayerSetting)}, which applies the server owner's default and lock
 * ({@code players.setting-defaults}, {@code players.locked-settings} in MachineEngine's {@code config.yml}).
 *
 * <p>The value is saved as text, so a setting's default may change between versions without a migration.
 * Players see the name and description translated on their client (D23): the lang keys
 * {@code setting.<namespace>.<id>} and {@code setting.<namespace>.<id>.description} of the addon's
 * CraftEngine pack.</p>
 *
 * <pre>{@code
 * public static final PlayerSetting<Boolean> HUD = PlayerSetting.bool(Key.key("mymod", "hud"), true);
 * }</pre>
 */
public final class PlayerSetting<T> implements Keyed {
    private final Key key;
    private final T defaultValue;
    private final Function<String, @Nullable T> parser;
    private final Function<T, String> formatter;
    private final Stepper<T> stepper;
    private final List<String> suggestions;
    private final String expected;
    private final PlayerDataKey<T> data;

    @FunctionalInterface
    private interface Stepper<T> {
        T step(T current, boolean forward);
    }

    private PlayerSetting(Key key, T defaultValue, Function<String, @Nullable T> parser, Function<T, String> formatter,
                          Stepper<T> stepper, List<String> suggestions, String expected) {
        this.key = Objects.requireNonNull(key, "key");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.parser = parser;
        this.formatter = formatter;
        this.stepper = stepper;
        this.suggestions = List.copyOf(suggestions);
        this.expected = expected;
        if (!defaultValue.equals(parser.apply(formatter.apply(defaultValue)))) {
            throw new IllegalArgumentException("the default of " + key.asString() + " must be " + expected + " but is " + defaultValue);
        }
        VersionedCodec<T> codec = VersionedCodec.builder(key, Codecs.STRING.flatXmap(
                text -> {
                    T value = parser.apply(text);
                    return value != null ? Result.ok(value)
                            : Result.<T>err(SerializationError.at("", "'" + text + "' is not " + expected));
                }, formatter)).build();
        this.data = PlayerDataKey.persistent(key, codec, defaultValue).display(formatter);
    }

    /** On or off; a click in the menu toggles it. */
    public static @NotNull PlayerSetting<Boolean> bool(@NotNull Key key, boolean defaultValue) {
        return new PlayerSetting<>(key, defaultValue, text -> switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "true", "on", "yes" -> Boolean.TRUE;
            case "false", "off", "no" -> Boolean.FALSE;
            default -> null;
        }, String::valueOf, (current, forward) -> !current, List.of("true", "false"), "true or false");
    }

    /** One of the constants of an enum, written in lower case with dashes; a click cycles through them. */
    public static <E extends Enum<E>> @NotNull PlayerSetting<E> choice(@NotNull Key key, @NotNull E defaultValue) {
        E[] constants = defaultValue.getDeclaringClass().getEnumConstants();
        List<String> names = Arrays.stream(constants).map(PlayerSetting::enumName).toList();
        return new PlayerSetting<>(key, defaultValue, text -> {
            String wanted = text.trim().replace('_', '-').replace(' ', '-');
            for (E constant : constants) {
                if (enumName(constant).equalsIgnoreCase(wanted)) return constant;
            }
            return null;
        }, PlayerSetting::enumName, (current, forward) ->
                constants[Math.floorMod(current.ordinal() + (forward ? 1 : -1), constants.length)], names, "one of: " + String.join(", ", names));
    }

    /** A whole number from {@code min} to {@code max}; a click adds or removes {@code step}. */
    public static @NotNull PlayerSetting<Long> whole(@NotNull Key key, long defaultValue, long min, long max, long step) {
        if (min > max || step <= 0) throw new IllegalArgumentException("invalid range or step for " + key.asString());
        List<String> suggestions = new ArrayList<>(List.of(String.valueOf(min), String.valueOf(defaultValue), String.valueOf(max)));
        return new PlayerSetting<>(key, defaultValue, text -> {
            try {
                long value = Long.parseLong(text.trim());
                return value >= min && value <= max ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }, String::valueOf, (current, forward) -> Math.clamp(forward ? current + step : current - step, min, max),
                suggestions.stream().distinct().toList(), "a whole number from " + min + " to " + max);
    }

    /** A number from {@code min} to {@code max}; a click adds or removes {@code step}. */
    public static @NotNull PlayerSetting<Double> decimal(@NotNull Key key, double defaultValue, double min, double max, double step) {
        if (!(min <= max) || !(step > 0)) throw new IllegalArgumentException("invalid range or step for " + key.asString());
        return new PlayerSetting<>(key, defaultValue, text -> {
            try {
                double value = Double.parseDouble(text.trim());
                return Double.isFinite(value) && value >= min && value <= max ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }, String::valueOf, (current, forward) -> Math.clamp(forward ? current + step : current - step, min, max),
                List.of(String.valueOf(min), String.valueOf(max)), "a number from " + min + " to " + max);
    }

    @Override
    public @NotNull Key key() {
        return this.key;
    }

    public @NotNull T defaultValue() {
        return this.defaultValue;
    }

    /** Where the value is kept: a persistent key of the same id, registered with the setting. */
    public @NotNull PlayerDataKey<T> data() {
        return this.data;
    }

    /** The value {@code text} stands for ({@code "on"}, {@code "12"}, {@code "fast-mode"}), or {@code null} if it is not valid. */
    public @Nullable T parse(@NotNull String text) {
        return this.parser.apply(text);
    }

    /** The value as players type it. */
    public @NotNull String format(@NotNull T value) {
        return this.formatter.apply(value);
    }

    /** The value after one click in the menu: toggled, the next (or previous) choice, or one step more (or less). */
    public @NotNull T step(@NotNull T current, boolean forward) {
        return this.stepper.step(current, forward);
    }

    /** Values offered when the player types the command. */
    public @NotNull List<String> suggestions() {
        return this.suggestions;
    }

    /** What a valid value looks like: "true or false". */
    public @NotNull String expected() {
        return this.expected;
    }

    /** Lang key of the setting's name. */
    public @NotNull String nameKey() {
        return "setting." + this.key.namespace() + "." + this.key.value().replace('/', '.');
    }

    /** Lang key of the setting's one-line description. */
    public @NotNull String descriptionKey() {
        return nameKey() + ".description";
    }

    @Override
    public String toString() {
        return "PlayerSetting[" + this.key.asString() + "]";
    }

    private static String enumName(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
