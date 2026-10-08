package fr.robie.machineengine.paper;

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

    public static @NotNull Component message(@NotNull String key, @NotNull String fallback, @NotNull ComponentLike... arguments) {
        // Not the builder: its build() has another erased return type in the Adventure of 1.20.6.
        return Component.translatable(key, fallback, arguments);
    }

    /** Item lore is italic by default; ours is not. */
    private static Component lore(String key, String fallback, ComponentLike... arguments) {
        return message(key, fallback, arguments).decoration(TextDecoration.ITALIC, false);
    }
}
