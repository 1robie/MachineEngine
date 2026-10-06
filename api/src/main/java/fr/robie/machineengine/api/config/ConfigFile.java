package fr.robie.machineengine.api.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * A YAML settings file read through typed {@link ConfigKey}s.
 *
 * <p>Loading never fails the plugin and never loses what the owner wrote:</p>
 * <ul>
 *   <li>a missing file is created from the {@link ConfigSpec}, with its header and comments;</li>
 *   <li>a missing key is added with its default and comment, and the file is saved;</li>
 *   <li>an invalid value logs a warning and uses the default; the file keeps the owner's text;</li>
 *   <li>keys the spec does not know are kept;</li>
 *   <li>a file that cannot be parsed is not touched: defaults are used (or, on a reload, the old values).</li>
 * </ul>
 * The file is only saved when something was added, so an up-to-date file is never reformatted.
 */
public final class ConfigFile implements ConfigValues {
    private final Path file;
    private final ConfigSpec spec;
    private final Logger logger;
    private volatile ConfigValues values;

    private ConfigFile(Path file, ConfigSpec spec, Logger logger) {
        this.file = file;
        this.spec = spec;
        this.logger = logger;
        this.values = spec.defaults();
    }

    /** Loads {@code file}, creating or completing it. */
    public static @NotNull ConfigFile load(@NotNull Path file, @NotNull ConfigSpec spec, @NotNull Logger logger) {
        ConfigFile config = new ConfigFile(Objects.requireNonNull(file), Objects.requireNonNull(spec), Objects.requireNonNull(logger));
        config.read(true);
        return config;
    }

    /** Loads {@code fileName} in the plugin's data folder. */
    public static @NotNull ConfigFile load(@NotNull Plugin plugin, @NotNull String fileName, @NotNull ConfigSpec spec) {
        return load(plugin.getDataFolder().toPath().resolve(fileName), spec, plugin.getLogger());
    }

    @Override
    public <T> @NotNull T get(@NotNull ConfigKey<T> key) {
        return this.values.get(key);
    }

    /** Reads the file again; restart-only keys keep their value. Also adds keys that went missing. */
    public synchronized @NotNull ConfigLoadResult reload() {
        return read(false);
    }

    public @NotNull Path file() {
        return this.file;
    }

    public @NotNull ConfigSpec spec() {
        return this.spec;
    }

    private synchronized ConfigLoadResult read(boolean first) {
        String name = this.file.getFileName().toString();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        boolean created = !Files.exists(this.file);
        if (created) {
            yaml.options().setHeader(this.spec.header());
        } else {
            try {
                yaml.load(this.file.toFile());
            } catch (IOException | InvalidConfigurationException e) {
                String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                this.logger.severe(name + " could not be read, so it was left as it is and "
                        + (first ? "every setting uses its default" : "the settings were not changed") + ": " + error);
                return new ConfigLoadResult(false, List.of(), List.of(), List.of(), List.of(), error);
            }
        }

        ConfigValues old = this.values;
        Map<ConfigKey<?>, Object> read = new IdentityHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> restartRequired = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (ConfigKey<?> key : this.spec.keys()) {
            Object value = readKey(yaml, key, name, added, invalid);
            Object previous = old.get(key);
            if (!first && !Objects.equals(previous, value)) {
                if (key.isRestartOnly()) {
                    restartRequired.add(key.path());
                    value = previous;
                } else {
                    changed.add(key.path());
                }
            }
            read.put(key, value);
        }
        this.values = values(read);

        if (created || !added.isEmpty()) {
            try {
                Path parent = this.file.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                yaml.save(this.file.toFile());
                if (created) this.logger.info("Created " + name + " with the default settings.");
                else this.logger.info(name + ": added " + added.size() + " missing setting(s) with their defaults: " + String.join(", ", added));
            } catch (IOException e) {
                this.logger.warning("Could not save " + name + ": " + e.getMessage());
            }
        }
        return new ConfigLoadResult(created, created ? List.of() : added, changed, restartRequired, invalid, null);
    }

    private <T> T readKey(YamlConfiguration yaml, ConfigKey<T> key, String name, List<String> added, List<String> invalid) {
        String path = key.path();
        if (!yaml.contains(path)) {
            String blocker = scalarAncestor(yaml, path);
            if (blocker != null) {
                this.logger.warning(name + ": " + blocker + " must be a section (it holds " + path + "); using the default " + path + ": "
                        + key.defaultValue());
                invalid.add(path);
            } else {
                add(yaml, key);
                added.add(path);
            }
            return key.defaultValue();
        }
        Object raw = yaml.get(path);
        T value = key.parse(raw);
        if (value == null) {
            this.logger.warning(name + ": " + path + " must be " + key.expected() + " but is " + describe(raw)
                    + "; using the default " + key.defaultValue());
            invalid.add(path);
            return key.defaultValue();
        }
        return value;
    }

    /** Writes {@code key} at its default, creating its sections with their comments. */
    private <T> void add(YamlConfiguration yaml, ConfigKey<T> key) {
        String path = key.path();
        for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
            String section = path.substring(0, dot);
            if (yaml.contains(section)) continue;
            List<String> comment = spaced(yaml, section, this.spec.sectionComment(section));
            yaml.createSection(section);
            yaml.setComments(section, comment);
        }
        List<String> comment = spaced(yaml, path, key.comment());
        Object value = key.write(key.defaultValue());
        if (value instanceof Map<?, ?> map) yaml.createSection(path, map);
        else yaml.set(path, value);
        yaml.setComments(path, comment);
    }

    /** A blank line before a new top-level entry, unless it is the first one. */
    private static List<String> spaced(YamlConfiguration yaml, String path, List<String> comment) {
        if (path.indexOf('.') >= 0 || yaml.getKeys(false).isEmpty()) return comment;
        List<String> lines = new ArrayList<>(comment.size() + 1);
        lines.add(null); // Bukkit writes null as an empty line
        lines.addAll(comment);
        return lines;
    }

    /** The first parent of {@code path} that holds a value instead of a section, or {@code null}. */
    private static @Nullable String scalarAncestor(YamlConfiguration yaml, String path) {
        for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
            String parent = path.substring(0, dot);
            if (yaml.contains(parent) && !yaml.isConfigurationSection(parent)) return parent;
        }
        return null;
    }

    private static String describe(Object raw) {
        if (raw instanceof ConfigurationSection) return "a section";
        if (raw instanceof List<?>) return "a list";
        return "'" + raw + "'";
    }

    static ConfigValues values(Map<ConfigKey<?>, Object> values) {
        Map<ConfigKey<?>, Object> copy = Collections.unmodifiableMap(new IdentityHashMap<>(values));
        return new ConfigValues() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> @NotNull T get(@NotNull ConfigKey<T> key) {
                Object value = copy.get(key);
                if (value == null) throw new IllegalArgumentException(key.path() + " is not a key of this config");
                return (T) value;
            }
        };
    }
}
