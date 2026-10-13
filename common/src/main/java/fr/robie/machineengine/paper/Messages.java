package fr.robie.machineengine.paper;

import fr.robie.machineengine.api.player.PlayerSetting;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/**
 * Text translated on each player's client in their own language: item lore and setting names, which the server
 * can't render per player (an item stack is shared by its viewers). The translations are CraftEngine {@code lang}
 * entries in the {@code machineengine} pack ({@code plugins/CraftEngine/resources/machineengine/configuration/lang.yml}):
 * server owners edit wording and colors (MiniMessage) there, per language, with {@code %s} for the arguments. Players
 * without the resource pack see the English fallback.
 * <p>
 * Chat and action bar messages are {@link fr.robie.machineengine.paper.message.EngineMessage}s (MESSAGEFLOW_PLAN.md B1).
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

    /** A setting's name, translated (its key when no translation exists). */
    public static @NotNull Component name(@NotNull PlayerSetting<?> setting) {
        return Component.translatable(setting.nameKey(), setting.key().value());
    }

    /** Item lore is italic by default; ours is not. */
    private static Component lore(String key, String fallback, ComponentLike... arguments) {
        // Not the builder: its build() has another erased return type in the Adventure of 1.20.6.
        return Component.translatable(key, fallback, arguments).decoration(TextDecoration.ITALIC, false);
    }
}
