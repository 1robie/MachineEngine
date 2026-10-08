package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.player.PlayerSetting;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/**
 * Player-facing text, translated on each player's client in their own language (§23.4). The
 * translations are CraftEngine {@code lang} entries in the {@code machineengine} pack
 * ({@code plugins/CraftEngine/resources/machineengine/configuration/lang.yml}): server owners edit
 * wording and colors (MiniMessage) there, per language, with {@code %s} for the arguments. Players
 * without the resource pack see the English fallback.
 */
public final class Messages {
    private Messages() {
    }

    public static @NotNull Component contentsKept() {
        return lore("machineengine.contents.kept", "§7Contents kept");
    }

    /** One lore line: {@code amount} of {@code resource}. */
    public static @NotNull Component contentsEntry(long amount, @NotNull Component resource) {
        return lore("machineengine.contents.entry", "  §f%s §7%s", Component.text(amount), resource);
    }

    public static @NotNull Component contentsMore(int more) {
        return lore("machineengine.contents.more", "§8… and %s more", Component.text(more));
    }

    public static @NotNull Component missingAddon(@NotNull String machineType) {
        return message("machineengine.machine.missing_addon", "§cThis machine belongs to an addon that is not installed (%s).",
                Component.text(machineType));
    }

    public static @NotNull Component placeholderBreakDenied(@NotNull String machineType) {
        return message("machineengine.machine.placeholder_break_denied",
                "§cThis machine (%s) belongs to an addon that is not installed. Breaking it would destroy its data.",
                Component.text(machineType));
    }

    public static @NotNull Component chunkLimit(int count, int limit) {
        return message("machineengine.machine.chunk_limit", "§cThis chunk already holds %s machines (limit %s).",
                Component.text(count), Component.text(limit));
    }

    public static @NotNull Component debugOn() {
        return message("machineengine.debug.on", "§6Debug view on: dots show machine states (green active, blue sleeping, "
                + "orange blocked, red error), ports (blue in, orange out, purple both) and networks; look at a machine for details.");
    }

    public static @NotNull Component debugOff() {
        return message("machineengine.debug.off", "§7Debug view off.");
    }

    /** The login screen has no resource pack yet: players see the English fallback unless their client knows the key. */
    public static @NotNull Component playerDataNotLoaded() {
        return message("machineengine.player.data_not_loaded", "Your data could not be loaded. Please try again in a moment.");
    }

    public static @NotNull Component settingsHeader() {
        return message("machineengine.settings.header", "§6Your settings (/machinesettings <setting> <value>):");
    }

    public static @NotNull Component settingsNone() {
        return message("machineengine.settings.none", "§7There are no settings to change.");
    }

    /** One line of the list: name, value, key, description. */
    public static @NotNull Component settingEntry(@NotNull PlayerSetting<?> setting, @NotNull String value) {
        return message("machineengine.settings.entry", "§7%s: §f%s §8(%s) §7%s", name(setting), Component.text(value),
                Component.text(setting.key().asString()), Component.translatable(setting.descriptionKey(), ""));
    }

    public static @NotNull Component settingValue(@NotNull PlayerSetting<?> setting, @NotNull String value) {
        return message("machineengine.settings.value", "§7%s: §f%s", name(setting), Component.text(value));
    }

    public static @NotNull Component settingChanged(@NotNull PlayerSetting<?> setting, @NotNull String value) {
        return message("machineengine.settings.changed", "§a%s is now %s.", name(setting), Component.text(value));
    }

    public static @NotNull Component settingInvalid(@NotNull String text, @NotNull String expected) {
        return message("machineengine.settings.invalid", "§c'%s' is not %s.", Component.text(text), Component.text(expected));
    }

    public static @NotNull Component settingLocked(@NotNull PlayerSetting<?> setting) {
        return message("machineengine.settings.locked", "§c%s is set by the server.", name(setting));
    }

    public static @NotNull Component settingUnknown(@NotNull String name) {
        return message("machineengine.settings.unknown", "§cThere is no setting %s. See /machinesettings.", Component.text(name));
    }

    /** A setting's name, translated (its key when no translation exists). */
    public static @NotNull Component name(@NotNull PlayerSetting<?> setting) {
        return Component.translatable(setting.nameKey(), setting.key().value());
    }

    public static @NotNull Component message(@NotNull String key, @NotNull String fallback, @NotNull ComponentLike... arguments) {
        // Not the builder: its build() has another erased return type in the Adventure of 1.20.6.
        return Component.translatable(key, fallback, arguments);
    }

    /** Item lore is italic by default; ours is not. */
    private static Component lore(String key, String fallback, ComponentLike... arguments) {
        return message(key, fallback, arguments).decoration(TextDecoration.ITALIC, false);
    }
}
