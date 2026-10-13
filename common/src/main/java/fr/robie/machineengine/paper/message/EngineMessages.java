package fr.robie.machineengine.paper.message;

import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.messageflow.configuration.ConfigurationManager;
import fr.robie.messageflow.configuration.lang.NormalLanguageConfiguration;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.impl.MessageManager;
import fr.robie.messageflow.logger.Logger;
import fr.robie.messageflow.model.SimpleMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Locale;
import java.util.Objects;

/**
 * MachineEngine's messages and log lines through MessageFlow (MESSAGEFLOW_PLAN.md B1, B4). {@link #create} comes first
 * in {@code onLoad} (it makes the logger); {@link #load} reads the language files once {@code config.yml} is loaded,
 * and again on {@code /machineengine reload config}; {@link #close} comes last in {@code onDisable}.
 * <p>
 * Each player reads {@code plugins/MachineEngine/lang/<their client's language>.yml}, else the same language in another
 * country, else {@code messages.default-language}. {@code en_us.yml} is written from the defaults in
 * {@link EngineMessage}, {@code fr_fr.yml} comes with MachineEngine, and server owners may add any other language.
 * {@code Component.translatable("machineengine.<key>")} renders from the same files (the translator), so code that
 * can't reach this class (the API) can still send these messages.
 * <p>
 * Sends happen on the caller's thread: callers already own the player (D35).
 */
public final class EngineMessages {
    private static final String FOLDER = "lang";
    /** Bundled with MachineEngine; English comes from {@link EngineMessage}. */
    private static final String[] BUNDLED = {"en_us", "fr_fr"};
    private static final String DEFAULT_LANGUAGE = "en_us";

    private static volatile @Nullable MessageManager<Plugin, String> manager;
    private static @Nullable NormalLanguageConfiguration languages;

    private EngineMessages() {
    }

    /** Makes the message manager and with it the logger. Its own settings file is bypassed: config.yml has them. */
    public static synchronized void create(@NotNull Plugin plugin) {
        ConfigurationManager.Setting.BYPASS_EXTERNAL_CONFIG.setValue(true);
        Logger.LogType.INFO.setShowTypeName(false);
        NormalLanguageConfiguration languages = new NormalLanguageConfiguration(DEFAULT_LANGUAGE);
        MessageManager<Plugin, String> created = new MessageManager<>(plugin, languages, EngineMessage.class);
        created.setPerPlayerLanguages(true);
        EngineMessages.languages = languages;
        manager = created;
    }

    /** (Re)reads the language files and the {@code messages.*} settings. */
    public static synchronized void load(@NotNull Plugin plugin, @NotNull ConfigValues config) {
        MessageManager<Plugin, String> current = Objects.requireNonNull(manager, "EngineMessages.create comes first");
        NormalLanguageConfiguration languages = Objects.requireNonNull(EngineMessages.languages);
        for (String language : BUNDLED) languages.addLanguage(language, FOLDER + "/" + language + ".yml");
        File[] files = new File(plugin.getDataFolder(), FOLDER).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String language = file.getName().substring(0, file.getName().length() - 4);
                languages.addLanguage(language, FOLDER + "/" + file.getName());
            }
        }
        current.formatter().setPrefix(config.get(EngineConfig.MESSAGES_PREFIX));
        current.reload();
        String language = languages.getNormalizedLanguage(config.get(EngineConfig.MESSAGES_DEFAULT_LANGUAGE));
        if (languages.getRelativePath(language) == null) {
            Logger.warn("[messages]", "messages.default-language: no lang/%language%.yml; using en_us", Placeholder.of("language", language));
            language = DEFAULT_LANGUAGE;
        }
        if (!language.equals(languages.getActiveLanguage())) current.setActiveLanguage(language);
        current.registerTranslator("machineengine.");
    }

    /** Releases the logger and the translator; log lines then go to plain java.util.logging. */
    public static synchronized void close() {
        MessageManager<Plugin, String> current = manager;
        manager = null;
        languages = null;
        if (current != null) current.close();
    }

    /** Sends a message (chat, action bar... as its language file says) in the recipient's language. */
    public static void send(@NotNull CommandSender to, @NotNull EngineMessage message) {
        send(to, message, Placeholder.empty());
    }

    /** Sends a message in the recipient's language; values are inserted as text (components keep their style). */
    public static void send(@NotNull CommandSender to, @NotNull EngineMessage message, @NotNull Placeholder placeholders) {
        MessageManager<Plugin, String> current = manager;
        if (current != null) {
            current.formatter().sendMessage(message, to, true, placeholders);
        } else {
            to.sendMessage(fallback(message, placeholders));
        }
    }

    /** A message's text as one component, for a kick screen or any API that takes one. */
    public static @NotNull Component component(@NotNull EngineMessage message, @Nullable Player viewer, @NotNull Placeholder placeholders) {
        MessageManager<Plugin, String> current = manager;
        return current != null ? current.adventure().formatMessage(message, viewer, placeholders) : fallback(message, placeholders);
    }

    /** Without a manager (tests, tools): the English default as plain text. */
    private static @NotNull Component fallback(@NotNull EngineMessage message, @NotNull Placeholder placeholders) {
        String text = ((SimpleMessage) message.defaults().getFirst()).messages().getFirst();
        return Component.text(placeholders.parse(MiniMessage.miniMessage().stripTags(text)));
    }
}
