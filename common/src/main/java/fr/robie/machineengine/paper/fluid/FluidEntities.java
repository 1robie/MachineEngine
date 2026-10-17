package fr.robie.machineengine.paper.fluid;

import fr.robie.machineengine.api.paper.WorldFluids;
import fr.robie.machineengine.api.paper.event.EntityFluidEvent;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.platform.Task;
import fr.robie.machineengine.api.platform.WorldFluidNms;
import fr.robie.machineengine.api.resource.fluid.FluidEffect;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.Viscosity;
import fr.robie.machineengine.paper.fluid.client.Cells;
import fr.robie.messageflow.logger.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What placeable fluids do to the entities inside them (D46): viscosity (attribute modifiers and
 * damping for players, drag for other entities), effects every 10 ticks, and {@link EntityFluidEvent}s.
 * Players are checked every 2 ticks on their own thread; other entities every
 * {@code world-fluids.entity-ticks}, in the chunks that hold fluids ({@code world-fluids.entity-effects}).
 */
final class FluidEntities implements Listener {
    /** FluidEffect runs every 10 ticks. */
    private static final int EFFECT_TICKS = 10;
    private final WorldFluidRuntime runtime;
    private final Scheduler scheduler;
    private final int scanTicks;
    private final Map<UUID, Task> players = new ConcurrentHashMap<>();
    private final Map<UUID, Inside> inside = new ConcurrentHashMap<>();

    /** An entity inside a fluid: since how many ticks, and the chunk it was last seen in. */
    private static final class Inside {
        final int number;
        final WeakReference<Entity> entity;
        int ticks;
        /** When the effects next run (every {@link #EFFECT_TICKS}). */
        int nextEffect;
        volatile long chunk;

        Inside(int number, Entity entity, long chunk) {
            this.number = number;
            this.entity = new WeakReference<>(entity);
            this.chunk = chunk;
        }
    }

    FluidEntities(WorldFluidRuntime runtime, Scheduler scheduler, int scanTicks) {
        this.runtime = runtime;
        this.scheduler = scheduler;
        this.scanTicks = scanTicks;
    }

    void start(Player player) {
        Task task = this.scheduler.runTimer(player, () -> tickPlayer(player), () -> this.players.remove(player.getUniqueId()), 1L, 2L);
        if (task != null) {
            Task previous = this.players.put(player.getUniqueId(), task);
            if (previous != null) previous.cancel();
        }
    }

    void stopAll() {
        this.players.values().forEach(Task::cancel);
        this.players.clear();
        WorldFluidNms nms = this.runtime.nms();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.inside.containsKey(player.getUniqueId())) {
                this.scheduler.run(player, () -> nms.viscosity(player, null), null);
            }
        }
        this.inside.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Task task = this.players.remove(player.getUniqueId());
        if (task != null) task.cancel();
        Inside was = this.inside.remove(player.getUniqueId());
        if (was != null) leave(player, was.number);
    }

    private int fluidAt(Entity entity) {
        Block feet = entity.getLocation().getBlock();
        int number = WorldFluidRuntime.isWater(feet) ? this.runtime.number(feet) : 0;
        if (number == 0 && entity instanceof LivingEntity living) {
            Block eyes = living.getEyeLocation().getBlock();
            if (WorldFluidRuntime.isWater(eyes)) number = this.runtime.number(eyes);
        }
        return this.runtime.keys().fluid(number) == null ? 0 : number;
    }

    private void tickPlayer(Player player) {
        int number = fluidAt(player);
        Inside state = update(player, number, 2);
        if (state == null) return;
        FluidType fluid = this.runtime.keys().fluid(number);
        if (fluid == null) return;
        Viscosity viscosity = WorldFluids.physics(fluid).viscosity();
        if (viscosity.damping() > 0) player.setVelocity(player.getVelocity().multiply(1 - viscosity.damping()));
        effectsIfDue(player, fluid, state);
    }

    /** Every 4 ticks: the other entities of each chunk that holds fluids, on that chunk's thread. */
    void scanChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (long chunk : this.runtime.cells().trackedChunks(world.getUID())) {
                int chunkX = Cells.chunkX(chunk), chunkZ = Cells.chunkZ(chunk);
                this.scheduler.run(world, chunkX, chunkZ, () -> scanChunk(world, chunkX, chunkZ, chunk));
            }
        }
    }

    private void scanChunk(World world, int chunkX, int chunkZ, long chunkKey) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) return;
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        Set<UUID> seen = new HashSet<>();
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Player || !(entity instanceof LivingEntity || entity instanceof Item)) continue;
            int number = fluidAt(entity);
            if (number == 0 && !this.inside.containsKey(entity.getUniqueId())) continue;
            seen.add(entity.getUniqueId());
            Inside state = update(entity, number, this.scanTicks);
            if (state == null) continue;
            state.chunk = chunkKey;
            FluidType fluid = this.runtime.keys().fluid(number);
            if (fluid == null) continue;
            double drag = WorldFluids.physics(fluid).viscosity().entityDrag();
            if (drag > 0) entity.setVelocity(entity.getVelocity().multiply(1 - drag));
            effectsIfDue(entity, fluid, state);
        }
        // Entities that were inside here and are gone: checked on their own thread (they moved or left).
        for (Map.Entry<UUID, Inside> entry : this.inside.entrySet()) {
            if (entry.getValue().chunk != chunkKey || seen.contains(entry.getKey())) continue;
            Entity entity = entry.getValue().entity.get();
            if (entity == null) {
                this.inside.remove(entry.getKey());
                continue;
            }
            if (entity instanceof Player) continue;
            this.scheduler.run(entity, () -> {
                if (fluidAt(entity) == 0) update(entity, 0, 0);
            }, () -> this.inside.remove(entry.getKey()));
        }
    }

    /**
     * Records which fluid an entity is in, with the enter and leave work when it changed.
     *
     * @return the entity's state inside a fluid, or {@code null} when it is in none
     */
    private @Nullable Inside update(Entity entity, int number, int ticks) {
        UUID id = entity.getUniqueId();
        Inside previous = this.inside.get(id);
        int was = previous == null ? 0 : previous.number;
        if (was == number) {
            if (previous != null) previous.ticks += ticks;
            return previous;
        }
        if (previous != null) {
            this.inside.remove(id);
            leave(entity, was);
        }
        if (number == 0) return null;
        FluidType fluid = this.runtime.keys().fluid(number);
        Inside state = new Inside(number, entity, Cells.chunk(entity.getLocation().getBlockX() >> 4, entity.getLocation().getBlockZ() >> 4));
        this.inside.put(id, state);
        if (fluid != null) {
            if (entity instanceof Player player) viscosity(player, WorldFluids.physics(fluid).viscosity());
            new EntityFluidEvent.Enter(entity, fluid).callEvent();
        }
        return state;
    }

    private void leave(Entity entity, int number) {
        FluidType fluid = this.runtime.keys().fluid(number);
        if (entity instanceof Player player) viscosity(player, null);
        if (fluid == null) return;
        for (FluidEffect effect : WorldFluids.effects(fluid)) {
            try {
                effect.leave(entity, fluid);
            } catch (RuntimeException e) {
                Logger.warn("[fluids]", "A fluid effect failed", e);
            }
        }
        new EntityFluidEvent.Leave(entity, fluid).callEvent();
    }

    private void effectsIfDue(Entity entity, FluidType fluid, Inside state) {
        if (state.ticks < state.nextEffect) return;
        state.nextEffect = state.ticks + EFFECT_TICKS;
        effects(entity, fluid, state.ticks);
    }

    private void effects(Entity entity, FluidType fluid, int ticks) {
        for (FluidEffect effect : WorldFluids.effects(fluid)) {
            try {
                effect.tick(entity, fluid, ticks);
            } catch (RuntimeException e) {
                Logger.warn("[fluids]", "A fluid effect failed", e);
            }
        }
    }

    private void viscosity(Player player, @Nullable Viscosity viscosity) {
        this.runtime.nms().viscosity(player, viscosity);
    }
}
