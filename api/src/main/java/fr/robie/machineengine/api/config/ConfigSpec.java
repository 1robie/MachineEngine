package fr.robie.machineengine.api.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a config file holds: a header, comments of sections, and the keys in the order they
 * are written. The file is generated from it: defaults and comments live here, not in a bundled
 * YAML file.
 */
public final class ConfigSpec {
    private final List<String> header;
    private final Map<String, List<String>> sections;
    private final List<ConfigKey<?>> keys;

    private ConfigSpec(Builder builder) {
        this.header = List.copyOf(builder.header);
        this.sections = Collections.unmodifiableMap(new LinkedHashMap<>(builder.sections));
        this.keys = List.copyOf(builder.keys.values());
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** Comment lines at the top of a new file. */
    public @NotNull List<String> header() {
        return this.header;
    }

    /** The comment above the section {@code path}, written when the section is added; empty if none. */
    public @NotNull List<String> sectionComment(@NotNull String path) {
        return this.sections.getOrDefault(path, List.of());
    }

    public @NotNull List<ConfigKey<?>> keys() {
        return this.keys;
    }

    /** Every key at its default, e.g. for headless tests. */
    public @NotNull ConfigValues defaults() {
        Map<ConfigKey<?>, Object> values = new IdentityHashMap<>();
        for (ConfigKey<?> key : this.keys) values.put(key, key.defaultValue());
        return ConfigFile.values(values);
    }

    public static final class Builder {
        private final List<String> header = new ArrayList<>();
        private final Map<String, List<String>> sections = new LinkedHashMap<>();
        private final Map<String, ConfigKey<?>> keys = new LinkedHashMap<>();

        private Builder() {
        }

        public @NotNull Builder header(@NotNull String... lines) {
            this.header.addAll(List.of(lines));
            return this;
        }

        /** A comment above the section {@code path} (e.g. {@code resources.energy}). */
        public @NotNull Builder section(@NotNull String path, @NotNull String... comment) {
            this.sections.put(path, List.of(comment));
            return this;
        }

        public @NotNull Builder key(@NotNull ConfigKey<?> key) {
            if (this.keys.putIfAbsent(key.path(), key) != null) {
                throw new IllegalArgumentException("two config keys use the path " + key.path());
            }
            return this;
        }

        public @NotNull Builder keys(@NotNull Collection<? extends ConfigKey<?>> keys) {
            keys.forEach(this::key);
            return this;
        }

        /** @throws IllegalArgumentException if a key's path lies inside another key, or names a section */
        public @NotNull ConfigSpec build() {
            for (String path : this.keys.keySet()) {
                String parent = parentKey(path);
                if (parent != null) throw new IllegalArgumentException("config key " + path + " lies inside the key " + parent);
                if (this.sections.containsKey(path)) throw new IllegalArgumentException("config path " + path + " is both a key and a section");
            }
            return new ConfigSpec(this);
        }

        private @Nullable String parentKey(String path) {
            for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', dot + 1)) {
                String prefix = path.substring(0, dot);
                if (this.keys.containsKey(prefix)) return prefix;
            }
            return null;
        }
    }
}
