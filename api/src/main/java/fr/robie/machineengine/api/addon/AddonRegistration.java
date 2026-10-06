package fr.robie.machineengine.api.addon;

import fr.robie.machineengine.api.version.Version;
import fr.robie.machineengine.api.version.VersionRange;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What an addon submits to MachineEngine before registration (ARCHITECTURE.md §8.3): its identity,
 * the API range and other addons it needs, and the callback that registers its content.
 */
public final class AddonRegistration {
    private final AddonId addon;
    private final Version version;
    private final VersionRange apiRange;
    private final List<AddonDependency> dependencies;
    private final Consumer<RegistrationContext> callback;

    private AddonRegistration(Builder builder) {
        this.addon = builder.addon;
        this.version = builder.version;
        this.apiRange = builder.apiRange;
        this.dependencies = List.copyOf(builder.dependencies);
        this.callback = Objects.requireNonNull(builder.callback, "register(...) callback is required");
    }

    public static @NotNull Builder builder(@NotNull AddonId addon, @NotNull Version version) {
        return new Builder(addon, version);
    }

    public static @NotNull Builder builder(@NotNull String addon, @NotNull String version) {
        return new Builder(new AddonId(addon), Version.parse(version));
    }

    public @NotNull AddonId addon() {
        return this.addon;
    }

    public @NotNull Version version() {
        return this.version;
    }

    public @NotNull VersionRange apiRange() {
        return this.apiRange;
    }

    public @NotNull List<AddonDependency> dependencies() {
        return this.dependencies;
    }

    public @NotNull Consumer<RegistrationContext> callback() {
        return this.callback;
    }

    public static final class Builder {
        private final AddonId addon;
        private final Version version;
        private VersionRange apiRange = VersionRange.ANY;
        private final List<AddonDependency> dependencies = new ArrayList<>();
        private Consumer<RegistrationContext> callback;

        private Builder(AddonId addon, Version version) {
            this.addon = Objects.requireNonNull(addon);
            this.version = Objects.requireNonNull(version);
        }

        /** @param range a {@link VersionRange} of the MachineEngine API this addon works with */
        public @NotNull Builder requiresApi(@NotNull String range) {
            this.apiRange = VersionRange.parse(range);
            return this;
        }

        /** Requires another addon in {@code range}; this addon's callback runs after it. */
        public @NotNull Builder requires(@NotNull String addon, @NotNull String range) {
            this.dependencies.add(new AddonDependency(new AddonId(addon), VersionRange.parse(range), false));
            return this;
        }

        /** Runs after {@code addon} when it is present, without requiring it. */
        public @NotNull Builder after(@NotNull String addon) {
            this.dependencies.add(new AddonDependency(new AddonId(addon), VersionRange.ANY, true));
            return this;
        }

        public @NotNull Builder register(@NotNull Consumer<RegistrationContext> callback) {
            this.callback = callback;
            return this;
        }

        public @NotNull AddonRegistration build() {
            return new AddonRegistration(this);
        }
    }
}
