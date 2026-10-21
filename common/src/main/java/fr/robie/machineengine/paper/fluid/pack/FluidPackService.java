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
            // Now, so CraftEngine sends it as usual; nothing from the network here (the main thread).
            build(message -> Logger.info("[fluids]", "%message%", Placeholder.of("message", message)), true);
            if (sodiumMissing()) {
                rebuild(message -> Logger.info("[fluids]", "Sodium's shader downloaded. %message% Send it with /ce reload pack.",
                        Placeholder.of("message", message)));
            }
            return;
        }
        Logger.info("[fluids]", "Downloading the official Minecraft %version% client jar (once) for the world fluids pack...",
                Placeholder.of("version", Bukkit.getMinecraftVersion()));
        rebuild(message -> Logger.info("[fluids]", "%message% Send it with /ce reload pack.", Placeholder.of("message", message)));
    }

    /** Builds off the main thread; {@code done} gets a line to show. */
    public @NotNull CompletableFuture<Void> rebuild(@NotNull Consumer<String> done) {
        CompletableFuture<Void> build = CompletableFuture.runAsync(() -> build(done, false), command -> MachineEngine.scheduler(this.plugin).runAsync(command));
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

    /** Whether Sodium's shader should be added but no Sodium for this version is in the cache yet. */
    private boolean sodiumMissing() {
        if (!this.config.get(WorldFluidConfig.SODIUM) || !this.config.get(WorldFluidConfig.SODIUM_JAR).equalsIgnoreCase("download")) return false;
        if (!atLeast(Bukkit.getMinecraftVersion(), 26, 2)) return false; // no Sodium takes pack shaders there
        try {
            return SodiumShaders.cached(Bukkit.getMinecraftVersion(), this.cache) == null;
        } catch (IOException e) {
            return true;
        }
    }

    /** One build at a time: two would write the same folders and download the same files. */
    private synchronized void build(Consumer<String> done, boolean offline) {
        try {
            String version = Bukkit.getMinecraftVersion();
            Path jar = VanillaClient.locate(this.config.get(WorldFluidConfig.CLIENT_JAR), version, this.cache);
            try (VanillaClient client = VanillaClient.open(jar)) {
                int buckets = this.config.get(WorldFluidConfig.BUCKET_TEXTURES) ? FluidPackBuilder.buckets(client, this.keys.fluids(), this.pack) : 0;
                if (!this.config.get(WorldFluidConfig.SHADER)) {
                    FluidPackBuilder.remove(this.pack);
                    done.accept("world-fluids.shader is off: no fluid shader in the pack (fluids show their key colors); " + buckets + " bucket textures.");
                    return;
                }
                boolean grid = grid(this.config.get(WorldFluidConfig.TEXTURES), version);
                SodiumShaders sodium = this.config.get(WorldFluidConfig.SODIUM) ? sodium(version, grid, offline) : null;
                FluidPackBuilder.Result result = FluidPackBuilder.build(client, this.keys, WorldFluids::look, new FluidPackBuilder.Settings(
                        this.config.get(WorldFluidConfig.NEUTRAL_COLOR), this.config.get(WorldFluidConfig.UNIFORM_WATER),
                        this.config.get(WorldFluidConfig.EDGE) == WorldFluidConfig.Edge.SHARP, this.config.get(WorldFluidConfig.TEXTURE_GRID),
                        grid, this.config.get(WorldFluidConfig.WATER_MODELS),
                        this.config.get(WorldFluidConfig.DEBUG_COLORS)), this.resources, this.pack, sodium);
                result.notes().forEach(note -> Logger.warn("[fluids]", "%note%", Placeholder.of("note", note)));
                String textures = grid ? result.textures() + " textures (" + result.columns() + "×" + result.columns() + " grid)" : "tint only (no texture grid)";
                done.accept("World fluids pack written: " + result.looks() + " looks, " + textures + ", " + result.shader() + " shader, "
                        + result.sodium() + ", pack format " + result.format() + ", " + buckets + " bucket textures"
                        + (grid ? ", " + result.waterModels() + " models redirected to plain water." : "."));
            }
        } catch (IOException | ShaderPatch.MismatchException | RuntimeException e) {
            Logger.warn("[fluids]", "The world fluids pack could not be written; fluids show their key colors: %error%",
                    Placeholder.of("error", String.valueOf(e.getMessage())));
            done.accept("The world fluids pack could not be written (see the console).");
        }
    }

    /**
     * Whether the pack uses the texture grid ({@code world-fluids.textures}): {@code auto} uses it from Minecraft
     * 26.2, where Sodium (0.9) takes the pack's shader; before, Sodium players would see the grid on all water.
     */
    static boolean grid(WorldFluidConfig.Textures setting, String version) {
        return switch (setting) {
            case GRID -> true;
            case TINT -> false;
            case AUTO -> atLeast(version, 26, 2);
        };
    }

    /** Sodium's shader to patch, or {@code null} (and a line saying what Sodium players see). */
    private @Nullable SodiumShaders sodium(String version, boolean grid, boolean offline) {
        String setting = this.config.get(WorldFluidConfig.SODIUM_JAR);
        try {
            // Below 26.2 no Sodium takes pack shaders (they load from its jar): nothing to download.
            SodiumShaders sodium = !setting.equalsIgnoreCase("download") ? SodiumShaders.fromJar(Path.of(setting))
                    : !atLeast(version, 26, 2) ? null
                    : offline ? SodiumShaders.cached(version, this.cache) : SodiumShaders.load(version, this.cache);
            if (offline && sodium == null && sodiumMissing()) return null; // downloaded right after, then the pack is built again
            if (sodium == null) {
                Logger.info("[fluids]", grid
                        ? "No Sodium for Minecraft %version% takes shaders from resource packs (Sodium 0.9, 26.2+): Sodium players see the texture grid on water (world-fluids.textures: tint avoids it)"
                        : "No Sodium for Minecraft %version% takes shaders from resource packs (Sodium 0.9, 26.2+): Sodium players see water, fluids in their code color",
                        Placeholder.of("version", version));
            }
            return sodium;
        } catch (IOException e) {
            Logger.warn("[fluids]", "Sodium's shader could not be read; Sodium players see " + (grid ? "the texture grid on water" : "fluids in their code color")
                    + ": %error%", Placeholder.of("error", String.valueOf(e.getMessage())));
            return null;
        }
    }

    /** Whether a Minecraft version ({@code 1.21.4}, {@code 26.2}) is {@code major.minor} or later. */
    static boolean atLeast(String version, int major, int minor) {
        String[] parts = version.split("[.-]");
        try {
            int first = Integer.parseInt(parts[0]);
            int second = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("\\D.*", "")) : 0;
            return first != major ? first > major : second >= minor;
        } catch (NumberFormatException e) {
            return false;
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
