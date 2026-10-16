package fr.robie.machineengine.paper.fluid.pack;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.paper.WorldFluids;
import fr.robie.machineengine.api.resource.fluid.WorldFluidConfig;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * When the world fluids pack is written (docs/FLUID_TYPES_PLAN.md §7.2): at enable, when the client
 * jar is already there (CraftEngine then sends it as usual), else once it is downloaded; and on
 * {@code /machineengine reload fluids}. CraftEngine sends a pack written later after {@code /ce reload pack}.
 */
public final class FluidPackService {
    private final Plugin plugin;
    private final ConfigValues config;
    private final FluidKeys keys;
    private final Path pack;
    private final Path resources;
    private final Path cache;
    private volatile @Nullable CompletableFuture<Void> running;

    public FluidPackService(@NotNull Plugin plugin, @NotNull ConfigValues config, @NotNull FluidKeys keys) {
        this.plugin = plugin;
        this.config = config;
        this.keys = keys;
        Path plugins = plugin.getDataFolder().toPath().getParent();
        this.resources = plugins.resolve("CraftEngine/resources");
        this.pack = this.resources.resolve("machineengine");
        this.cache = plugin.getDataFolder().toPath().resolve("cache");
    }

    /** At enable: written now when the client jar is at hand, otherwise after it is downloaded. */
    public void start() {
        warnIfCraftEngineDropsShaders();
        Path cached = this.cache.resolve("minecraft-client-" + Bukkit.getMinecraftVersion() + ".jar");
        String setting = this.config.get(WorldFluidConfig.CLIENT_JAR);
        if (!setting.equalsIgnoreCase("download") || Files.isRegularFile(cached)) {
            build(message -> Logger.info("[fluids]", "%message%", Placeholder.of("message", message)));
            return;
        }
        Logger.info("[fluids]", "Downloading the official Minecraft %version% client jar (once) for the world fluids pack...",
                Placeholder.of("version", Bukkit.getMinecraftVersion()));
        rebuild(message -> Logger.info("[fluids]", "%message% Send it with /ce reload pack.", Placeholder.of("message", message)));
    }

    /** Builds off the main thread; {@code done} gets a line to show. */
    public @NotNull CompletableFuture<Void> rebuild(@NotNull Consumer<String> done) {
        CompletableFuture<Void> build = CompletableFuture.runAsync(() -> build(done), command -> MachineEngine.scheduler(this.plugin).runAsync(command));
        this.running = build;
        return build;
    }

    /** MachineEngine disables: a build still running (a download) gets a few seconds to finish. */
    public void stop() {
        CompletableFuture<Void> build = this.running;
        if (build == null || build.isDone()) return;
        try {
            build.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            Logger.warn("[fluids]", "The world fluids pack was still being written at shutdown; it is written at the next start");
        }
    }

    private void build(Consumer<String> done) {
        try {
            Path jar = VanillaClient.locate(this.config.get(WorldFluidConfig.CLIENT_JAR), Bukkit.getMinecraftVersion(), this.cache);
            try (VanillaClient client = VanillaClient.open(jar)) {
                int buckets = FluidPackBuilder.buckets(client, this.keys.fluids(), this.pack);
                if (!this.config.get(WorldFluidConfig.SHADER)) {
                    FluidPackBuilder.remove(this.pack);
                    done.accept("world-fluids.shader is off: no fluid shader in the pack (fluids show their key colors); " + buckets + " bucket textures.");
                    return;
                }
                FluidPackBuilder.Result result = FluidPackBuilder.build(client, this.keys, WorldFluids::look, new FluidPackBuilder.Settings(
                        this.config.get(WorldFluidConfig.NEUTRAL_COLOR), this.config.get(WorldFluidConfig.UNIFORM_WATER),
                        this.config.get(WorldFluidConfig.EDGE) == WorldFluidConfig.Edge.SHARP, this.config.get(WorldFluidConfig.TEXTURE_GRID)),
                        this.resources, this.pack);
                result.notes().forEach(note -> Logger.warn("[fluids]", "%note%", Placeholder.of("note", note)));
                done.accept("World fluids pack written: " + result.looks() + " looks, " + result.textures() + " textures ("
                        + result.columns() + "×" + result.columns() + " grid), " + result.shader() + " shader, pack format " + result.format()
                        + ", " + buckets + " bucket textures.");
            }
        } catch (IOException | ShaderPatch.MismatchException | RuntimeException e) {
            Logger.warn("[fluids]", "The world fluids pack could not be written; fluids show their key colors: %error%",
                    Placeholder.of("error", String.valueOf(e.getMessage())));
            done.accept("The world fluids pack could not be written (see the console).");
        }
    }

    /** CraftEngine's {@code resource-pack.exclude-core-shaders} removes every core shader, ours too. */
    private void warnIfCraftEngineDropsShaders() {
        Path config = this.resources.getParent().resolve("config.yml");
        if (!Files.isRegularFile(config)) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config.toFile());
        if (yaml.getBoolean("resource-pack.exclude-core-shaders", false)) {
            Logger.warn("[fluids]", "CraftEngine's resource-pack.exclude-core-shaders is on: the world fluids shader is left out of the pack");
        }
    }
}
