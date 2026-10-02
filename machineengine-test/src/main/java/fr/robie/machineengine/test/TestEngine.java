package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.addon.RegistrationContext;
import fr.robie.machineengine.api.version.Version;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.lifecycle.MachineEngineBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Starts a headless MachineEngine from inline addons, for unit tests of MachineEngine and addons
 * (ARCHITECTURE.md §28).
 *
 * <pre>{@code
 * CoreMachineEngine engine = TestEngine.builder()
 *         .addon("energyplugin", ctx -> ctx.resourceCategories().register(ENERGY))
 *         .start();
 * }</pre>
 */
public final class TestEngine {
    private final MachineEngineBuilder builder = new MachineEngineBuilder();

    private TestEngine() {
        Logger logger = Logger.getLogger("MachineEngine-Test");
        logger.setLevel(Level.OFF);
        this.builder.logger(logger);
    }

    public static @NotNull TestEngine builder() {
        return new TestEngine();
    }

    /** Adds an addon at version 1.0.0 with no dependencies. */
    public @NotNull TestEngine addon(@NotNull String name, @NotNull Consumer<RegistrationContext> callback) {
        return addon(name, "1.0.0", builder -> builder.register(callback));
    }

    /** Adds an addon, configuring its registration (dependencies, API range, callback). */
    public @NotNull TestEngine addon(@NotNull String name, @NotNull String version,
                                     @NotNull UnaryOperator<AddonRegistration.Builder> configure) {
        this.builder.addon(configure.apply(AddonRegistration.builder(name, version)).build());
        return this;
    }

    public @NotNull TestEngine addon(@NotNull AddonRegistration registration) {
        this.builder.addon(registration);
        return this;
    }

    /** Adds data-driven definition files (format-neutral trees, as the YAML loader produces). */
    public @NotNull TestEngine definitions(@NotNull java.util.List<fr.robie.machineengine.api.definition.DefinitionFile> files) {
        this.builder.definitions(() -> files, fr.robie.machineengine.core.definition.DefinitionCompiler.ItemResolver.NONE);
        return this;
    }

    public @NotNull TestEngine apiVersion(@NotNull String version) {
        this.builder.apiVersion(Version.parse(version));
        return this;
    }

    /** Logs to the console instead of discarding engine logs. */
    public @NotNull TestEngine verbose() {
        this.builder.logger(Logger.getLogger("MachineEngine-Test-Verbose"));
        return this;
    }

    public @NotNull CoreMachineEngine start() {
        return this.builder.start();
    }
}
