package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.MachineEngineApi;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.version.Version;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Collects addon registrations and starts an engine: dependency resolution, registration,
 * validation, freezing and the first reloadable snapshots (§8.2). Platforms call {@link #start()}
 * at the right point of their own lifecycle.
 */
public final class MachineEngineBuilder {
    private final List<AddonRegistration> registrations = new ArrayList<>();
    private Logger logger = Logger.getLogger("MachineEngine");
    private Version apiVersion = Version.parse(MachineEngineApi.version());

    public @NotNull MachineEngineBuilder addon(@NotNull AddonRegistration registration) {
        this.registrations.add(Objects.requireNonNull(registration));
        return this;
    }

    public @NotNull MachineEngineBuilder logger(@NotNull Logger logger) {
        this.logger = Objects.requireNonNull(logger);
        return this;
    }

    /** Overrides the API version addons are checked against. For tests. */
    public @NotNull MachineEngineBuilder apiVersion(@NotNull Version apiVersion) {
        this.apiVersion = Objects.requireNonNull(apiVersion);
        return this;
    }

    public @NotNull CoreMachineEngine start() {
        return CoreMachineEngine.start(List.copyOf(this.registrations), this.apiVersion, this.logger);
    }
}
