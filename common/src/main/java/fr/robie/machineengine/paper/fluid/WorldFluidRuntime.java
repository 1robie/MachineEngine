package fr.robie.machineengine.paper.fluid;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.paper.ResourceNames;
import fr.robie.machineengine.api.paper.WorldFluids;
import fr.robie.machineengine.api.paper.event.WorldFluidPlaceEvent;
import fr.robie.machineengine.api.paper.event.WorldFluidRemoveEvent;
import fr.robie.machineengine.api.platform.Nms;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.platform.Task;
import fr.robie.machineengine.api.platform.WorldFluidNms;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidEffect;
import fr.robie.machineengine.api.resource.fluid.FluidItems;
import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidReaction;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.WorldFluidConfig;
import fr.robie.machineengine.nms.ReflectiveWorldFluids;
import fr.robie.machineengine.paper.fluid.client.BiomeZoom;
import fr.robie.machineengine.paper.fluid.client.Cells;
import fr.robie.machineengine.paper.fluid.client.FluidBiomeRemapper;
import fr.robie.machineengine.paper.fluid.client.FluidCells;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.machineengine.paper.fluid.pack.FluidPackService;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.craftengine.bukkit.plugin.network.listener.game.LevelChunkWithLightListener;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Placeable fluids in the world (D46, docs/FLUID_TYPES_PLAN.md): a placeable fluid is vanilla water
 * that MachineEngine tracks. The client sees it through key biomes (a water color per look, which the
 * shader of the resource pack turns into the fluid's look); its physics, mixing and effects come from
 * its {@link FluidType} with {@code fluids.yml} on top ({@link WorldFluids}).
 *
 * <p>Started at enable when {@code world-fluids.enabled} is on and the server version has what it needs
 * ({@link WorldFluidNms}). Answers the block part of {@link WorldFluids}.</p>
 */
public final class WorldFluidRuntime implements Listener, WorldFluids.Access {
    private static final NamespacedKey SAVED = new NamespacedKey("machineengine", "fluids");
    static final NamespacedKey BUCKET = new NamespacedKey("machineengine", "fluid_bucket");
    private static final Key BASE_BIOME = Key.key("minecraft", "plains");
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final Plugin plugin;
    private final MachineEngine engine;
    private final WorldFluidNms nms;
    private final ConfigValues config;
    private final Scheduler scheduler;
    private final FluidKeys keys;
    private final FluidCells cells;
    private final AtomicInteger base = new AtomicInteger(-1);
    private final Map<UUID, Long> seeds = new ConcurrentHashMap<>();
    private final Set<String> pendingChunks = ConcurrentHashMap.newKeySet();
    /** Saved blocks of fluids that are not registered (addon missing), kept as they are (D3): world → chunk → position → key. */
    private final Map<UUID, Map<Long, Map<Long, Key>>> unknown = new ConcurrentHashMap<>();
    /** Chunks whose fluid data is of a newer format: never written. */
    private final Map<UUID, Set<Long>> unreadable = new ConcurrentHashMap<>();
    private final AtomicLong updatesSent = new AtomicLong();
    private final List<Task> tasks = new ArrayList<>();
    private final FluidEntities entities;
    private final FluidPackService pack;
    private @Nullable FluidBiomeRemapper remapper;
    private volatile boolean biomeUpdates = true;

    private WorldFluidRuntime(Plugin plugin, MachineEngine engine, WorldFluidNms nms, ConfigValues config, FluidKeys keys) {
        this.plugin = plugin;
        this.engine = engine;
        this.nms = nms;
        this.config = config;
        this.scheduler = MachineEngine.scheduler(plugin);
        this.keys = keys;
        boolean uniform = config.get(WorldFluidConfig.UNIFORM_WATER);
        this.cells = new FluidCells(this::zoomSeed, keys, uniform ? 0 : config.get(WorldFluidConfig.NEUTRAL_RINGS));
        this.entities = new FluidEntities(this, this.scheduler, config.get(WorldFluidConfig.ENTITY_TICKS));
        this.pack = new FluidPackService(plugin, config, keys);
    }

    /**
     * Starts world fluids, or reports why they can't run here.
     *
     * @return the running world fluids, or {@code null}
     */
    public static @Nullable WorldFluidRuntime install(@NotNull Plugin plugin, @NotNull MachineEngine engine,
                                                      @NotNull ConfigValues config, @NotNull List<Diagnostic> report) {
        // Dev check of the reflective version (1.21.x) on servers that have a module: -Dmachineengine.reflectiveWorldFluids=true
        WorldFluidNms nms = Boolean.getBoolean("machineengine.reflectiveWorldFluids")
                ? ReflectiveWorldFluids.create(line -> Logger.warn("[fluids]", "%line%", Placeholder.of("line", line)))
                : Nms.get().worldFluids();
        if (nms == null) {
            report.add(Diagnostic.warning(DiagnosticCodes.DEFINITION_SKIPPED, "World fluids don't run on Minecraft "
                    + Bukkit.getMinecraftVersion() + " yet: fluids stay in tanks and pipes").location("world-fluids").build());
            return null;
        }
        List<FluidType> placeable = new ArrayList<>();
        for (ResourceType type : engine.registries().registry(Registries.RESOURCE_TYPE).values()) {
            if (type instanceof FluidType fluid && fluid.placeable()) placeable.add(fluid);
        }
        boolean uniform = config.get(WorldFluidConfig.UNIFORM_WATER);
        int neutral = config.get(WorldFluidConfig.NEUTRAL_COLOR);
        FluidKeys keys = FluidKeys.of(placeable, WorldFluids::look, neutral, uniform);
        if (!keys.withoutKey().isEmpty()) {
            report.add(Diagnostic.warning(DiagnosticCodes.DEFINITION_SKIPPED, keys.keyCount() + " different fluid looks are the most "
                    + (uniform ? "the shader tells apart" : "there is room for without world-fluids.uniform-water")
                    + "; these fluids can't be placed: " + keys.withoutKey().stream().map(fluid -> fluid.key().asString()).toList())
                    .location("world-fluids").build());
        }
        WorldFluidRuntime runtime = new WorldFluidRuntime(plugin, engine, nms, config, keys);
        try {
            runtime.start(uniform ? neutral : null, report);
        } catch (RuntimeException | LinkageError e) {
            // Never stop MachineEngine from enabling: fluids stay in tanks and pipes.
            Logger.warn("[fluids]", "World fluids could not start", e);
            report.add(Diagnostic.error(DiagnosticCodes.DEFINITION_SKIPPED, "World fluids could not start: " + e
                    + "; fluids stay in tanks and pipes").location("world-fluids").build());
            try {
                runtime.uninstall();
            } catch (RuntimeException | LinkageError ignored) {
                // already reported
            }
            return null;
        }
        return runtime;
    }

    private void start(@Nullable Integer uniformWater, List<Diagnostic> report) {
        for (World world : Bukkit.getWorlds()) zoomSeed(world.getUID());
        this.nms.install(BASE_BIOME, this.keys.keyBiomes(), uniformWater, count -> {
            if (this.base.getAndSet(count) != count) Logger.info("[fluids]", "Server biomes: %count%", Placeholder.of("count", String.valueOf(count)));
        }, line -> Logger.info("[fluids]", "%line%", Placeholder.of("line", line)));
        try {
            FluidBiomeRemapper created = new FluidBiomeRemapper(this.cells, this.base::get);
            LevelChunkWithLightListener.addBiomeRemapper(created);
            this.remapper = created;
        } catch (LinkageError e) {
            // An older CraftEngine: fluids are sent as biome-only updates when players load chunks instead.
            Bukkit.getPluginManager().registerEvents(new ChunkSends(this), this.plugin);
            Logger.info("[fluids]", "This CraftEngine has no biome hook: fluid colors follow each chunk a moment later");
        }
        int sample = 100 + this.keys.entryCount();
        String check = this.nms.checkBiomeWriter(sample);
        if (!check.equals("200/200")) {
            this.biomeUpdates = false;
            report.add(Diagnostic.warning(DiagnosticCodes.DEFINITION_SKIPPED, "Biome-only chunk updates failed their check ("
                    + check + "): changed fluids resend whole chunks").location("world-fluids").build());
        }
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        Bukkit.getPluginManager().registerEvents(this.entities, this.plugin);
        this.tasks.add(this.scheduler.runTimer(this::sweep, 20L, 20L));
        if (this.config.get(WorldFluidConfig.ENTITY_EFFECTS)) {
            long every = this.config.get(WorldFluidConfig.ENTITY_TICKS);
            this.tasks.add(this.scheduler.runTimer(this.entities::scanChunks, every, every));
        }
        for (Player player : Bukkit.getOnlinePlayers()) this.entities.start(player);
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) this.scheduler.run(world, chunk.getX(), chunk.getZ(), () -> load(chunk));
        }
        this.pack.start();
        Logger.info("[fluids]", "World fluids: %fluids% placeable fluids, %looks% looks", Placeholder.of(
                "fluids", String.valueOf(this.keys.fluidCount()), "looks", String.valueOf(this.keys.keyCount())));
    }

    /** Stops answering and saves what changed (MachineEngine disables). */
    public void uninstall() {
        this.pack.stop();
        this.nms.uninstall();
        if (this.remapper != null) this.remapper.stop();
        this.tasks.forEach(Task::cancel);
        HandlerList.unregisterAll(this);
        this.entities.stopAll();
        for (World world : Bukkit.getWorlds()) {
            for (long chunk : this.cells.takeUnsavedChunks(world.getUID())) {
                try {
                    save(world, Cells.chunkX(chunk), Cells.chunkZ(chunk));
                } catch (RuntimeException e) {
                    Logger.warn("[fluids]", "Could not save the fluids of a chunk at shutdown", e);
                }
            }
        }
    }

    public @NotNull FluidKeys keys() {
        return this.keys;
    }

    /** The resource pack side (stage 5): textures and the shader. */
    public @NotNull FluidPackService pack() {
        return this.pack;
    }

    @NotNull WorldFluidNms nms() {
        return this.nms;
    }

    @NotNull FluidCells cells() {
        return this.cells;
    }

    /** Numbers for {@code /machineengine fluid}: {base, updates sent}. */
    long @NotNull [] counters() {
        return new long[]{this.base.get(), this.updatesSent.get()};
    }

    private long zoomSeed(UUID world) {
        return this.seeds.computeIfAbsent(world, id -> {
            World loaded = Bukkit.getWorld(id);
            return BiomeZoom.zoomSeed(loaded == null ? 0 : loaded.getSeed());
        });
    }

    FluidRules.Settings settings() {
        return new FluidRules.Settings(this.config.get(WorldFluidConfig.CONFLICT) == WorldFluidConfig.Conflict.STOP,
                this.config.get(WorldFluidConfig.FLUID_GAP), this.config.get(WorldFluidConfig.PADDING), this.config.get(WorldFluidConfig.DISPERSION));
    }

    FluidRules.Blocks blocks(World world) {
        return new FluidRules.Blocks() {
            @Override
            public boolean isWater(int x, int y, int z) {
                return WorldFluidRuntime.isWater(world.getBlockAt(x, y, z));
            }

            @Override
            public boolean readable(int x, int z) {
                return world.isChunkLoaded(x >> 4, z >> 4) && WorldFluidRuntime.this.scheduler.isOwnedByCurrentThread(world, x >> 4, z >> 4);
            }
        };
    }

    // ---------------------------------------------------------------- WorldFluids

    @Override
    public @Nullable ResourceType at(@NotNull Block block) {
        int number = number(block);
        if (number != 0 && isWater(block)) {
            FluidType fluid = this.keys.fluid(number);
            if (fluid != null) return fluid;
        }
        return WorldFluids.vanillaOnly().at(block);
    }

    @Override
    public WorldFluids.@NotNull Placement canPlace(@NotNull Block block, @NotNull FluidType fluid) {
        Refusal refusal = refusal(block, fluid);
        return refusal == null ? WorldFluids.Placement.ALLOWED
                : WorldFluids.Placement.refused(EngineMessages.component(refusal.message(), null, refusal.placeholders()));
    }

    @Override
    public @NotNull FluidPhysics physics(@NotNull FluidType fluid) {
        return fluid.physics();
    }

    @Override
    public @NotNull FluidLook look(@NotNull FluidType fluid) {
        return fluid.look();
    }

    @Override
    public @NotNull FluidMixing mixing(@NotNull FluidType fluid) {
        return fluid.mixing();
    }

    @Override
    public @NotNull List<FluidEffect> effects(@NotNull FluidType fluid) {
        return fluid.effects();
    }

    /** Why a fluid may not go into a block, as a message for players. */
    record Refusal(@NotNull EngineMessage message, @NotNull Placeholder placeholders) {
    }

    @Nullable Refusal refusal(@NotNull Block block, @NotNull FluidType fluid) {
        Placeholder name = Placeholder.of("fluid", name(fluid));
        if (fluid == Fluids.lava()) return block.getType().isAir() ? null : new Refusal(EngineMessage.FLUID_NOT_HERE, name);
        int number = fluid == Fluids.water() ? 0 : this.keys.number(fluid);
        if (fluid != Fluids.water() && number == 0) return new Refusal(EngineMessage.FLUID_NOT_PLACEABLE, name);
        if (!block.getType().isAir() && !isWater(block)) return new Refusal(EngineMessage.FLUID_NOT_HERE, name);
        if (number != 0 && number(block) == number) return null;
        FluidRules.Refusal refusal = FluidRules.check(this.cells, block.getWorld().getUID(), blocks(block.getWorld()),
                block.getX(), block.getY(), block.getZ(), number, settings());
        return refusal == null ? null : message(refusal, fluid);
    }

    Refusal message(FluidRules.Refusal refusal, FluidType fluid) {
        FluidType other = this.keys.fluid(refusal.other());
        Placeholder placeholders = Placeholder.builder()
                .register("fluid", name(fluid))
                .register("other", other == null ? Component.empty() : name(other))
                .register("blocks", String.valueOf(this.config.get(WorldFluidConfig.FLUID_GAP) * 4))
                .build();
        EngineMessage message = switch (refusal.reason()) {
            case NO_KEY -> EngineMessage.FLUID_NO_KEY;
            case OTHER_FLUID_NEAR -> EngineMessage.FLUID_TOO_CLOSE;
            case AREA_OF_FLUID -> EngineMessage.FLUID_AREA_OF_FLUID;
            case AREA_READ_BY_OTHER_FLUID -> EngineMessage.FLUID_AREA_OF_OTHER;
            case AREA_READ_BY_WATER -> EngineMessage.FLUID_AREA_OF_WATER;
        };
        return new Refusal(message, placeholders);
    }

    static @NotNull Component name(@NotNull ResourceType type) {
        return MiniMessage.miniMessage().deserialize(ResourceNames.of(type.variant()));
    }

    /** Places a source of {@code fluid}, firing {@link WorldFluidPlaceEvent}. */
    @Override
    public boolean place(@NotNull Block block, @NotNull FluidType fluid, WorldFluidPlaceEvent.@NotNull Cause cause) {
        if (refusal(block, fluid) != null) return false;
        if (!new WorldFluidPlaceEvent(block, fluid, cause).callEvent()) return false;
        if (fluid == Fluids.lava()) {
            block.setType(Material.LAVA);
            return true;
        }
        if (!isSource(block)) block.setType(Material.WATER);
        setFluid(block, this.keys.number(fluid));
        return true;
    }

    /** Removes a source; fires {@link WorldFluidRemoveEvent} for placeable fluids. */
    @Override
    public @Nullable ResourceType take(@NotNull Block block, WorldFluidRemoveEvent.@NotNull Cause cause) {
        if (block.getType() == Material.LAVA && block.getBlockData() instanceof Levelled lava && lava.getLevel() == 0) {
            block.setType(Material.AIR);
            return Fluids.lava();
        }
        if (!isSource(block)) return null;
        FluidType fluid = this.keys.fluid(number(block));
        if (fluid != null) {
            setFluid(block, 0);
            new WorldFluidRemoveEvent(block, fluid, cause).callEvent();
        }
        if (block.getBlockData() instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(false);
            block.setBlockData(waterlogged);
        } else {
            block.setType(Material.AIR);
        }
        return fluid == null ? Fluids.water() : fluid;
    }

    // ---------------------------------------------------------------- tracking

    int number(@NotNull Block block) {
        return this.cells.get(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    /** Sets which fluid a water block holds (0: plain water), and updates what clients see. */
    void setFluid(@NotNull Block block, int number) {
        Long cell = this.cells.set(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), number);
        if (cell != null) markCellDirty(block.getWorld(), cell, this.keys.uniform() ? 0 : this.config.get(WorldFluidConfig.NEUTRAL_RINGS));
    }

    private void forget(Block block, WorldFluidRemoveEvent.Cause cause) {
        FluidType fluid = this.keys.fluid(number(block));
        if (fluid == null) return;
        setFluid(block, 0);
        new WorldFluidRemoveEvent(block, fluid, cause).callEvent();
    }

    private void markCellDirty(World world, long cell, int rings) {
        int minX = (Cells.x(cell) - rings) * 4, maxX = (Cells.x(cell) + rings) * 4 + 3;
        int minZ = (Cells.z(cell) - rings) * 4, maxZ = (Cells.z(cell) + rings) * 4 + 3;
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++)
                markDirty(world, cx, cz);
    }

    /** Sends a chunk's biomes again to the players who see it, once per {@code world-fluids.update-delay}. */
    void markDirty(World world, int chunkX, int chunkZ) {
        String id = world.getUID() + ":" + chunkX + ":" + chunkZ;
        if (!this.pendingChunks.add(id)) return;
        this.scheduler.runLater(world, chunkX, chunkZ, () -> {
            this.pendingChunks.remove(id);
            if (world.isChunkLoaded(chunkX, chunkZ)) sendBiomes(world, chunkX, chunkZ, world.getChunkAt(chunkX, chunkZ).getPlayersSeeingChunk());
        }, this.config.get(WorldFluidConfig.UPDATE_DELAY));
    }

    void sendBiomes(World world, int chunkX, int chunkZ, Collection<? extends Player> viewers) {
        if (viewers.isEmpty()) return;
        int base = this.base.get();
        if (!this.biomeUpdates || base < 0) {
            world.refreshChunk(chunkX, chunkZ);
            return;
        }
        UUID id = world.getUID();
        this.nms.sendBiomes(viewers, world, chunkX, chunkZ, cell -> {
            int entry = this.cells.key(id, cell);
            return entry == 0 ? -1 : base + entry - 1;
        }, base + this.keys.entryCount());
        this.updatesSent.incrementAndGet();
    }

    /** Padding and dispersion of the cells around changed areas, each on its own region thread. */
    private void updatePadding(World world) {
        Set<Long> candidates = new HashSet<>();
        int rings = FluidKeys.MAX_RINGS;
        for (long cell : this.cells.takeDirtyCells(world.getUID())) {
            int cx = Cells.x(cell), cy = Cells.y(cell), cz = Cells.z(cell);
            for (int dx = -rings; dx <= rings; dx++)
                for (int dy = -rings; dy <= rings; dy++)
                    for (int dz = -rings; dz <= rings; dz++)
                        candidates.add(Cells.pack(cx + dx, cy + dy, cz + dz));
        }
        if (candidates.isEmpty()) return;
        FluidRules.Settings settings = settings();
        UUID id = world.getUID();
        for (long cell : candidates) {
            int blockX = Cells.x(cell) * 4, blockZ = Cells.z(cell) * 4;
            if (!world.isChunkLoaded(blockX >> 4, blockZ >> 4)) continue;
            this.scheduler.run(world, blockX >> 4, blockZ >> 4, () -> {
                int entry = FluidRules.surroundingEntry(this.cells, id, blocks(world), cell, settings);
                int before = this.cells.key(id, cell);
                this.cells.setPadding(id, cell, entry);
                if (this.cells.key(id, cell) != before) markCellDirty(world, cell, 0);
            });
        }
    }

    /** Settings of {@code config.yml} changed: padding and dispersion are computed again. */
    public void settingsChanged() {
        this.cells.clearPadding();
        for (World world : Bukkit.getWorlds()) {
            this.cells.markAllDirty(world.getUID());
            for (long chunk : this.cells.trackedChunks(world.getUID())) markDirty(world, Cells.chunkX(chunk), Cells.chunkZ(chunk));
        }
    }

    /** Every second: padding, saving, and forgetting tracked blocks that are no longer water. */
    private void sweep() {
        for (World world : Bukkit.getWorlds()) {
            UUID id = world.getUID();
            updatePadding(world);
            for (long chunk : this.cells.takeUnsavedChunks(id)) {
                int chunkX = Cells.chunkX(chunk), chunkZ = Cells.chunkZ(chunk);
                this.scheduler.run(world, chunkX, chunkZ, () -> save(world, chunkX, chunkZ));
            }
            for (long chunk : this.cells.trackedChunks(id)) {
                int chunkX = Cells.chunkX(chunk), chunkZ = Cells.chunkZ(chunk);
                this.scheduler.run(world, chunkX, chunkZ, () -> {
                    if (!world.isChunkLoaded(chunkX, chunkZ)) return;
                    for (long packed : this.cells.blocksIn(id, chunkX, chunkZ)) {
                        Block block = world.getBlockAt(Cells.x(packed), Cells.y(packed), Cells.z(packed));
                        if (!isWater(block)) forget(block, WorldFluidRemoveEvent.Cause.REPLACED);
                    }
                });
            }
        }
    }

    /** Turns every placeable fluid of a world back into plain water (saved data too). */
    void clear(@NotNull World world) {
        UUID id = world.getUID();
        for (long chunk : this.cells.trackedChunks(id)) {
            int chunkX = Cells.chunkX(chunk), chunkZ = Cells.chunkZ(chunk);
            this.scheduler.run(world, chunkX, chunkZ, () -> {
                for (long packed : this.cells.blocksIn(id, chunkX, chunkZ)) {
                    forget(world.getBlockAt(Cells.x(packed), Cells.y(packed), Cells.z(packed)), WorldFluidRemoveEvent.Cause.COMMAND);
                }
            });
        }
    }

    // ---------------------------------------------------------------- persistence

    private void load(Chunk chunk) {
        PersistentDataContainer data = chunk.getPersistentDataContainer();
        byte[] saved = data.get(SAVED, PersistentDataType.BYTE_ARRAY);
        if (saved == null) return;
        World world = chunk.getWorld();
        UUID id = world.getUID();
        long chunkKey = Cells.chunk(chunk.getX(), chunk.getZ());
        Map<Long, Key> blocks;
        try {
            blocks = FluidChunkCodec.decode(saved);
        } catch (FluidChunkCodec.NewerFormatException | IOException e) {
            this.unreadable.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).add(chunkKey);
            Logger.warn("[fluids]", "Fluids of chunk %x% %z% in %world% left as saved: %error%", Placeholder.builder()
                    .register("x", String.valueOf(chunk.getX())).register("z", String.valueOf(chunk.getZ()))
                    .register("world", world.getName()).register("error", String.valueOf(e.getMessage())).build());
            return;
        }
        var registry = this.engine.registries().registry(Registries.RESOURCE_TYPE);
        Map<Long, Key> missing = new HashMap<>();
        for (Map.Entry<Long, Key> entry : blocks.entrySet()) {
            int number = this.keys.number(registry.get(entry.getValue()).orElse(null) instanceof FluidType fluid ? fluid : null);
            long packed = entry.getKey();
            if (number == 0) missing.put(packed, entry.getValue());
            else this.cells.set(id, Cells.x(packed), Cells.y(packed), Cells.z(packed), number);
        }
        if (!missing.isEmpty()) this.unknown.computeIfAbsent(id, ignored -> new ConcurrentHashMap<>()).put(chunkKey, missing);
    }

    private void save(World world, int chunkX, int chunkZ) {
        UUID id = world.getUID();
        long chunkKey = Cells.chunk(chunkX, chunkZ);
        Set<Long> skipped = this.unreadable.get(id);
        if (skipped != null && skipped.contains(chunkKey)) return;
        if (!world.isChunkLoaded(chunkX, chunkZ)) return;
        PersistentDataContainer data = world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer();
        Map<Long, Key> blocks = new HashMap<>();
        Map<Long, Map<Long, Key>> unknownHere = this.unknown.get(id);
        if (unknownHere != null && unknownHere.get(chunkKey) != null) blocks.putAll(unknownHere.get(chunkKey));
        for (long packed : this.cells.blocksIn(id, chunkX, chunkZ)) {
            FluidType fluid = this.keys.fluid(this.cells.get(id, Cells.x(packed), Cells.y(packed), Cells.z(packed)));
            if (fluid != null) blocks.put(packed, fluid.key());
        }
        if (blocks.isEmpty()) data.remove(SAVED);
        else data.set(SAVED, PersistentDataType.BYTE_ARRAY, FluidChunkCodec.encode(blocks));
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        load(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        UUID id = chunk.getWorld().getUID();
        save(chunk.getWorld(), chunk.getX(), chunk.getZ());
        this.cells.forgetChunk(id, chunk.getX(), chunk.getZ());
        long chunkKey = Cells.chunk(chunk.getX(), chunk.getZ());
        Map<Long, Map<Long, Key>> unknownHere = this.unknown.get(id);
        if (unknownHere != null) unknownHere.remove(chunkKey);
        Set<Long> skipped = this.unreadable.get(id);
        if (skipped != null) skipped.remove(chunkKey);
    }

    // ---------------------------------------------------------------- flow

    /**
     * Water changing level by itself: drying up, or becoming a source. A new source needs two source
     * neighbours; their fluids decide (a fluid's {@code infinite-source}, {@code mixed-source}).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLevelChange(FluidLevelChangeEvent event) {
        Block block = event.getBlock();
        BlockData next = event.getNewData();
        if (next.getMaterial() != Material.WATER) {
            forget(block, WorldFluidRemoveEvent.Cause.DRIED);
            return;
        }
        if (!(next instanceof Levelled levelled) || levelled.getLevel() != 0) return;
        if (block.getBlockData() instanceof Levelled current && current.getLevel() == 0) return;
        Set<Integer> fluids = new HashSet<>();
        for (BlockFace face : SIDES) {
            Block neighbour = block.getRelative(face);
            if (isSource(neighbour)) fluids.add(number(neighbour));
        }
        if (fluids.size() == 1) {
            int number = fluids.iterator().next();
            FluidType fluid = this.keys.fluid(number);
            if (fluid == null) {
                setFluid(block, 0);
            } else if (WorldFluids.physics(fluid).infiniteSource()) {
                if (new WorldFluidPlaceEvent(block, fluid, WorldFluidPlaceEvent.Cause.SPREAD).callEvent()) setFluid(block, number);
                else event.setCancelled(true);
            } else {
                event.setCancelled(true);
            }
        } else if (fluids.size() > 1) {
            boolean water = true;
            for (int number : fluids) {
                FluidType fluid = this.keys.fluid(number);
                if (fluid != null && WorldFluids.physics(fluid).mixedSource() != FluidPhysics.MixedSource.WATER) water = false;
            }
            if (water) setFluid(block, 0);
            else event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (number(block) != 0 && !isWater(block)) forget(block, WorldFluidRemoveEvent.Cause.REPLACED);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (from.getType() != Material.WATER && !isWater(from)) return;
        int number = number(from);
        int present = number(to);
        if (present != 0 && !isWater(to)) {
            forget(to, WorldFluidRemoveEvent.Cause.REPLACED);
            present = 0;
        }
        FluidRules.Settings settings = settings();
        UUID id = to.getWorld().getUID();
        if (number == 0) {
            // Plain water stops at a fluid, and at an area a fluid owns (conflict stop).
            if (present != 0 || FluidRules.check(this.cells, id, blocks(to.getWorld()), to.getX(), to.getY(), to.getZ(), 0, settings) != null) {
                event.setCancelled(true);
            }
            return;
        }
        FluidType fluid = this.keys.fluid(number);
        if (fluid == null) return;
        ResourceType there = present != 0 ? this.keys.fluid(present) : isWater(to) ? Fluids.water() : to.getType() == Material.LAVA ? Fluids.lava() : null;
        if (there != null && there != fluid) {
            FluidReaction reaction = WorldFluids.mixing(fluid).reactionWith(there, this.engine.tags());
            if (reaction.react(to, fluid, there) == FluidReaction.Outcome.STOP || to.getType() == Material.LAVA) {
                event.setCancelled(true);
                return;
            }
        }
        if (FluidRules.check(this.cells, id, blocks(to.getWorld()), to.getX(), to.getY(), to.getZ(), number, settings) != null) {
            event.setCancelled(true);
            return;
        }
        FluidPhysics physics = WorldFluids.physics(fluid);
        BlockData data = flowingState(from, event.getFace());
        if (data == null || (event.getFace() != BlockFace.DOWN && ((Levelled) data).getLevel() > physics.spread().range())) {
            event.setCancelled(true);
            return;
        }
        if (!new WorldFluidPlaceEvent(to, fluid, WorldFluidPlaceEvent.Cause.SPREAD).callEvent()) {
            event.setCancelled(true);
            return;
        }
        int delay = physics.spread().ticks();
        if (delay > 5) {
            // Slower than water: cancelled, and the same flowing state is placed later.
            event.setCancelled(true);
            this.scheduler.runLater(to.getLocation(), () -> {
                if (number(from) != number || !isWater(from)) return;
                if (!to.getType().isAir() && to.getType() != Material.WATER) return;
                if (FluidRules.check(this.cells, id, blocks(to.getWorld()), to.getX(), to.getY(), to.getZ(), number, settings()) != null) return;
                to.setBlockData(data, true);
                setFluid(to, number);
            }, delay - 5L);
            return;
        }
        setFluid(to, number);
    }

    /** The flowing water vanilla puts on the block {@code from} spreads to, or {@code null} past the last level. */
    private static @Nullable BlockData flowingState(Block from, BlockFace face) {
        if (!(Material.WATER.createBlockData() instanceof Levelled levelled)) return null;
        int fromLevel = from.getBlockData() instanceof Levelled l ? l.getLevel() : 0;
        int level = face == BlockFace.DOWN ? 8 : fromLevel >= 8 ? 1 : fromLevel + 1;
        if (level > 7 && face != BlockFace.DOWN) return null;
        levelled.setLevel(level);
        return levelled;
    }

    // ---------------------------------------------------------------- buckets

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET) return;
        ItemStack item = event.getPlayer().getInventory().getItem(event.getHand());
        FluidType fluid = bucketFluid(item);
        Block block = event.getBlock();
        Refusal refusal = refusal(block, fluid == null ? Fluids.water() : fluid);
        if (refusal != null) {
            event.setCancelled(true);
            EngineMessages.send(event.getPlayer(), refusal.message(), refusal.placeholders());
            return;
        }
        if (fluid == null) return;
        if (!new WorldFluidPlaceEvent(block, fluid, WorldFluidPlaceEvent.Cause.BUCKET).callEvent()) {
            event.setCancelled(true);
            return;
        }
        int number = this.keys.number(fluid);
        this.scheduler.run(block.getLocation(), () -> {
            if (isWater(block)) setFluid(block, number);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        FluidType fluid = this.keys.fluid(number(block));
        if (fluid == null) return;
        ItemStack bucket = bucket(fluid);
        if (bucket == null) {
            event.setCancelled(true);
            return;
        }
        event.setItemStack(bucket);
        setFluid(block, 0);
        new WorldFluidRemoveEvent(block, fluid, WorldFluidRemoveEvent.Cause.BUCKET).callEvent();
    }

    /** The fluid a bucket places, or {@code null} for plain water (or not a fluid bucket). */
    @Nullable FluidType bucketFluid(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        Key id = PaperItems.idOf(item);
        String tagged = item.hasItemMeta() ? item.getItemMeta().getPersistentDataContainer().get(BUCKET, PersistentDataType.STRING) : null;
        for (FluidType fluid : this.keys.fluids()) {
            FluidItems.Bucket bucket = fluid.items().bucket();
            if (bucket instanceof FluidItems.Bucket.Item own && own.item().equals(id)) return fluid;
            if (bucket instanceof FluidItems.Bucket.Auto && fluid.key().asString().equals(tagged)) return fluid;
        }
        return null;
    }

    /**
     * A bucket of {@code fluid}: its own item ({@code bucket: <item>}, a CraftEngine item on a water bucket),
     * or a water bucket named and tagged for it ({@code auto}); {@code null} with {@code bucket: none}.
     */
    @Nullable ItemStack bucket(@NotNull FluidType fluid) {
        FluidItems.Bucket kind = fluid.items().bucket();
        if (kind instanceof FluidItems.Bucket.Item own) {
            ItemStack item = PaperItems.itemOf(own.item());
            if (item == null) Logger.warn("[fluids]", "The bucket item %item% of %fluid% does not exist", Placeholder.of(
                    "item", own.item().asString(), "fluid", fluid.key().asString()));
            return item;
        }
        if (!(kind instanceof FluidItems.Bucket.Auto)) return null;
        ItemStack bucket = new ItemStack(Material.WATER_BUCKET);
        ItemMeta meta = bucket.getItemMeta();
        meta.displayName(EngineMessages.component(EngineMessage.FLUID_BUCKET_NAME, null, Placeholder.builder().register("fluid", name(fluid)).build()));
        meta.getPersistentDataContainer().set(BUCKET, PersistentDataType.STRING, fluid.key().asString());
        bucket.setItemMeta(meta);
        return bucket;
    }

    // ---------------------------------------------------------------- helpers

    /** A water source, or a waterlogged block (vanilla counts both as sources). */
    static boolean isSource(@NotNull Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Waterlogged waterlogged) return waterlogged.isWaterlogged();
        return block.getType() == Material.WATER && data instanceof Levelled levelled && levelled.getLevel() == 0;
    }

    static boolean isWater(@NotNull Block block) {
        if (block.getType() == Material.WATER) return true;
        return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    /** With an older CraftEngine (no biome hook): a chunk a player loads gets its fluid colors right after. */
    private record ChunkSends(WorldFluidRuntime runtime) implements Listener {
        @EventHandler
        public void onChunkSent(PlayerChunkLoadEvent event) {
            Chunk chunk = event.getChunk();
            World world = chunk.getWorld();
            if (this.runtime.cells.blocksIn(world.getUID(), chunk.getX(), chunk.getZ()).isEmpty()
                    && !hasKeyedSection(world.getUID(), chunk)) return;
            Player player = event.getPlayer();
            this.runtime.scheduler.runLater(world, chunk.getX(), chunk.getZ(),
                    () -> this.runtime.sendBiomes(world, chunk.getX(), chunk.getZ(), List.of(player)), 2L);
        }

        private boolean hasKeyedSection(UUID world, Chunk chunk) {
            for (int sy = -4; sy < 20; sy++) {
                if (this.runtime.cells.hasSection(world, chunk.getX(), sy, chunk.getZ())) return true;
            }
            return false;
        }
    }
}
