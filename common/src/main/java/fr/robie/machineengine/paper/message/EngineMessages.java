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

public final class EngineMessages {
    private static final String FOLDER = "lang";
    /** Bundled with MachineEngine; English comes from {@link EngineMessage}. */
    private static final String[] BUNDLED = {"en_us", "fr_fr"};
    private static final String DEFAULT_LANGUAGE = "en_us";

    private static volatile @Nullable MessageManager<Plugin, String> manager;
    private static @Nullable NormalLanguageConfiguration languages;

    private EngineMessages() {
    }

    public static synchronized void create(@NotNull Plugin plugin) {
        ConfigurationManager.Setting.BYPASS_EXTERNAL_CONFIG.setValue(true);
        Logger.LogType.INFO.setShowTypeName(false);
        NormalLanguageConfiguration languages = new NormalLanguageConfiguration(DEFAULT_LANGUAGE);
        MessageManager<Plugin, String> created = new MessageManager<>(plugin, languages, EngineMessage.class);
        created.setPerPlayerLanguages(true);
        EngineMessages.languages = languages;
        manager = created;
    }

    public static synchronized void load(@NotNull Plugin plugin, @NotNull ConfigValues config) {
        MessageManager<Plugin, String> messageFormatter = Objects.requireNonNull(manager, "EngineMessages.create comes first");
        NormalLanguageConfiguration languages = Objects.requireNonNull(EngineMessages.languages);
        for (String language : BUNDLED) languages.addLanguage(language, FOLDER + "/" + language + ".yml");
        File[] files = new File(plugin.getDataFolder(), FOLDER).listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String language = file.getName().substring(0, file.getName().length() - 4);
                languages.addLanguage(language, FOLDER + "/" + file.getName());
            }
        }
        messageFormatter.formatter().setPrefix(config.get(EngineConfig.MESSAGES_PREFIX));
        messageFormatter.reload();
        String language = languages.getNormalizedLanguage(config.get(EngineConfig.MESSAGES_DEFAULT_LANGUAGE));
        if (languages.getRelativePath(language) == null) {
            Logger.warn("[messages]", "messages.default-language: no lang/%language%.yml; using en_us", Placeholder.of("language", language));
            language = DEFAULT_LANGUAGE;
        }
        if (!language.equals(languages.getActiveLanguage())) messageFormatter.setActiveLanguage(language);
        messageFormatter.registerTranslator("machineengine.");
    }

    public static synchronized void close() {
        MessageManager<Plugin, String> current = manager;
        manager = null;
        languages = null;
        if (current != null) current.close();
    }

    public static void send(@NotNull CommandSender to, @NotNull EngineMessage message) {
        send(to, message, Placeholder.empty());
    }

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
