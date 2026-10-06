package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.MachineEngineApi;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.version.Version;
import fr.robie.machineengine.core.definition.DefinitionCompiler;
import fr.robie.machineengine.core.resource.ItemTagSource;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
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
    private Supplier<List<DefinitionFile>> definitions;
    private DefinitionCompiler.ItemResolver items =
            DefinitionCompiler.ItemResolver.NONE;
    private ItemTagSource itemTags = ItemTagSource.NONE;

    /** The platform's item tags (vanilla and CraftEngine tags on Paper), consulted after addon item tags. */
    public @NotNull MachineEngineBuilder itemTags(@NotNull ItemTagSource source) {
        this.itemTags = Objects.requireNonNull(source);
        return this;
    }

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

    /**
     * Adds data-driven definitions (§29), compiled after every addon so they can use addon content.
     *
     * @param files read at startup and again on every recipe reload
     * @param items resolves {@code item:} ids (platform-specific)
     */
    public @NotNull MachineEngineBuilder definitions(
            @NotNull Supplier<List<DefinitionFile>> files,
            @NotNull DefinitionCompiler.ItemResolver items) {
        this.definitions = Objects.requireNonNull(files);
        this.items = Objects.requireNonNull(items);
        return this;
    }

    public @NotNull CoreMachineEngine start() {
        List<AddonRegistration> all = new ArrayList<>(this.registrations);
        if (this.definitions != null) {
            all.add(DefinitionCompiler.registration(this.definitions, this.items,
                    this.registrations.stream().map(AddonRegistration::addon).toList(), this.apiVersion));
        }
        return CoreMachineEngine.start(List.copyOf(all), this.apiVersion, this.logger, this.itemTags);
    }
}
