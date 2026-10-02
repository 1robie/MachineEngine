package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineHost;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * A headless world for tests (ARCHITECTURE.md §28): one game clock, machines, and simulated regions
 * with their own task queues and timers, run in a deterministic order on the test thread.
 *
 * <p>Each {@link #tick()} advances the clock and then runs every region in name order: first the
 * tasks posted to it before this tick, then due timers, then its active machines. While a region
 * runs, only machines in that region count as owned, so transfers to other regions go through the
 * handoff protocol exactly as on Folia.</p>
 */
public final class FakeWorld implements MachineHost {
    public static final Key WORLD = Key.key("test", "world");

    private final CoreMachineEngine engine;
    private final Function<BlockPosition, String> regionOf;
    private final Map<String, Region> regions = new TreeMap<>();
    private final Map<WorldPosition, LoadedMachine> machines = new LinkedHashMap<>();
    private final Set<MachineInstanceImpl> dirty = new LinkedHashSet<>();
    private final List<ResourceStack> dropped = new ArrayList<>();
    private long time;
    private @Nullable String currentRegion;

    private FakeWorld(CoreMachineEngine engine, Function<BlockPosition, String> regionOf) {
        this.engine = engine;
        this.regionOf = regionOf;
    }

    /** Everything in one region: every transfer is direct (Paper). */
    public static @NotNull FakeWorld singleRegion(@NotNull CoreMachineEngine engine) {
        return new FakeWorld(engine, position -> "main");
    }

    /** Machines are split into regions by {@code regionOf} (Folia). */
    public static @NotNull FakeWorld regions(@NotNull CoreMachineEngine engine, @NotNull Function<BlockPosition, String> regionOf) {
        return new FakeWorld(engine, regionOf);
    }

    public static @NotNull WorldPosition at(int x, int y, int z) {
        return WorldPosition.of(WORLD, x, y, z);
    }

    // ------------------------------------------------------------------------------------------
    // Test API
    // ------------------------------------------------------------------------------------------

    public @NotNull CoreMachineEngine engine() {
        return this.engine;
    }

    private fr.robie.machineengine.api.machine.MachineEnvironment environment = fr.robie.machineengine.api.machine.MachineEnvironment.NONE;

    /** What every machine in this world sees of the sky (E3). */
    public void setEnvironment(@NotNull fr.robie.machineengine.api.machine.MachineEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public @NotNull fr.robie.machineengine.api.machine.MachineEnvironment environment(@NotNull WorldPosition position) {
        return this.environment;
    }

    public long time() {
        return this.time;
    }

    public @NotNull MachineInstanceImpl place(@NotNull MachineType type, @NotNull WorldPosition position, @NotNull Direction facing) {
        return inRegionOf(position, () -> {
            MachineInstanceImpl machine = this.engine.machines().create(type, position, facing, this);
            this.machines.put(position, machine);
            activityChanged(machine);
            return machine;
        });
    }

    /** Places a transport block (cable, pipe …); it joins neighboring networks. */
    public void placeTransport(@NotNull fr.robie.machineengine.api.network.TransportType type, @NotNull WorldPosition position) {
        runInRegionOf(position, () -> this.engine.machines().networks().addTransport(type, position, this));
    }

    /** Removes (or unloads) a transport block. */
    public void removeTransport(@NotNull WorldPosition position) {
        runInRegionOf(position, () -> this.engine.machines().networks().removeTransport(position));
    }

    /** @throws IllegalStateException if no live machine is at {@code position} */
    public @NotNull MachineInstanceImpl machine(@NotNull WorldPosition position) {
        LoadedMachine loaded = this.machines.get(position);
        if (loaded == null || loaded.instance() == null) throw new IllegalStateException("No live machine at " + position);
        return loaded.instance();
    }

    public @Nullable LoadedMachine loaded(@NotNull WorldPosition position) {
        return this.machines.get(position);
    }

    public @NotNull Map<WorldPosition, LoadedMachine> machines() {
        return Collections.unmodifiableMap(this.machines);
    }

    public @NotNull MachineInspection inspect(@NotNull WorldPosition position) {
        LoadedMachine loaded = this.machines.get(position);
        if (loaded == null) throw new IllegalStateException("Nothing loaded at " + position);
        return loaded.inspect();
    }

    public void tick() {
        advance();
        for (Region region : List.copyOf(this.regions.values())) run(region);
    }

    /** Moves the clock forward without running anything, e.g. to simulate time spent unloaded. */
    public void skipTime(long ticks) {
        this.time += ticks;
    }

    /** Advances the clock without running anything (use with {@link #step(String)}). */
    public void advance() {
        this.time++;
    }

    /** Runs one region's work for the current tick: posted tasks, due timers, active machines. */
    public void step(@NotNull String regionId) {
        run(this.regions.computeIfAbsent(regionId, Region::new));
    }

    private void run(Region region) {
        this.currentRegion = region.id;
        try {
            int queued = region.tasks.size();
            for (int i = 0; i < queued; i++) region.tasks.pollFirst().run();
            while (!region.timers.isEmpty() && region.timers.firstKey() <= this.time) {
                for (MachineInstanceImpl machine : region.timers.pollFirstEntry().getValue()) machine.timerFired(this.time);
            }
            for (MachineInstanceImpl machine : List.copyOf(region.active)) machine.tick();
        } finally {
            this.currentRegion = null;
        }
    }

    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) tick();
    }

    /** Runs {@code action} as if on the thread owning {@code position}. */
    public <T> T inRegionOf(@NotNull WorldPosition position, @NotNull java.util.function.Supplier<T> action) {
        String previous = this.currentRegion;
        this.currentRegion = region(position).id;
        try {
            return action.get();
        } finally {
            this.currentRegion = previous;
        }
    }

    public void runInRegionOf(@NotNull WorldPosition position, @NotNull Runnable action) {
        inRegionOf(position, () -> {
            action.run();
            return null;
        });
    }

    /** Saves every loaded machine (the data a server writes to disk). */
    public @NotNull Map<WorldPosition, DataMap> save() {
        Map<WorldPosition, DataMap> saved = new LinkedHashMap<>();
        this.machines.forEach((position, machine) -> saved.put(position, machine.save(this.time)));
        this.dirty.clear();
        return saved;
    }

    public @NotNull DataMap save(@NotNull WorldPosition position) {
        LoadedMachine machine = this.machines.get(position);
        if (machine == null) throw new IllegalStateException("Nothing loaded at " + position);
        return machine.save(this.time);
    }

    /** Loads saved machines (e.g. into a new world, or after {@link #crash()}). */
    public void load(@NotNull Map<WorldPosition, DataMap> saved) {
        saved.forEach(this::load);
    }

    public @NotNull LoadedMachine load(@NotNull WorldPosition position, @NotNull DataMap data) {
        return inRegionOf(position, () -> {
            LoadedMachine machine = this.engine.machines().load(data, position, this);
            this.machines.put(position, machine);
            if (machine.instance() != null) activityChanged(machine.instance());
            return machine;
        });
    }

    /** Unloads a machine normally (its chunk unloads). */
    public @NotNull DataMap unload(@NotNull WorldPosition position) {
        LoadedMachine machine = this.machines.get(position);
        if (machine == null) throw new IllegalStateException("Nothing loaded at " + position);
        DataMap data = machine.save(this.time);
        runInRegionOf(position, () -> this.engine.machines().unload(machine));
        forget(position);
        return data;
    }

    /** Removes a machine (broken); returns its drops. */
    public @NotNull List<ResourceStack> remove(@NotNull WorldPosition position) {
        LoadedMachine machine = this.machines.get(position);
        if (machine == null) throw new IllegalStateException("Nothing loaded at " + position);
        List<ResourceStack> drops = inRegionOf(position, () -> this.engine.machines().remove(machine));
        forget(position);
        return drops;
    }

    /** Breaks a machine keeping its contents for an item (§20.5); empty if it must be removed normally. */
    public @NotNull java.util.Optional<fr.robie.machineengine.core.machine.CoreMachines.Packed> pack(@NotNull WorldPosition position) {
        LoadedMachine machine = this.machines.get(position);
        if (machine == null) throw new IllegalStateException("Nothing loaded at " + position);
        var packed = inRegionOf(position, () -> this.engine.machines().pack(machine));
        if (packed.isPresent()) forget(position);
        return packed;
    }

    /** Places a machine from an item holding packed contents, as the CraftEngine host does. */
    public @NotNull LoadedMachine placeWithContents(@NotNull MachineType type, @NotNull WorldPosition position,
                                                    @NotNull Direction facing, @NotNull DataMap state) {
        place(type, position, facing);
        return inRegionOf(position, () -> {
            LoadedMachine restored = this.engine.machines().restore(this.machines.get(position), state, this);
            this.machines.put(position, restored);
            if (restored.instance() != null) activityChanged(restored.instance());
            return restored;
        });
    }

    /** Simulates a hard crash: every machine and every queued message is gone, nothing is saved. */
    public void crash() {
        this.machines.clear();
        this.regions.clear();
        this.dirty.clear();
    }

    /** Messages waiting in all regions. */
    public int pendingTasks() {
        return this.regions.values().stream().mapToInt(region -> region.tasks.size()).sum();
    }

    public @NotNull Set<MachineInstanceImpl> dirtyMachines() {
        return Collections.unmodifiableSet(this.dirty);
    }

    /** Resources the world received through {@link #dropResources}. */
    public @NotNull List<ResourceStack> dropped() {
        return Collections.unmodifiableList(this.dropped);
    }

    public @NotNull SchedulerState state(@NotNull WorldPosition position) {
        return machine(position).schedulerState();
    }

    private void forget(WorldPosition position) {
        LoadedMachine machine = this.machines.remove(position);
        if (machine != null && machine.instance() != null) {
            Region region = region(position);
            region.active.remove(machine.instance());
            region.timers.values().forEach(list -> list.remove(machine.instance()));
            this.dirty.remove(machine.instance());
        }
    }

    private Region region(WorldPosition position) {
        return this.regions.computeIfAbsent(this.regionOf.apply(position.position()), Region::new);
    }

    // ------------------------------------------------------------------------------------------
    // MachineHost
    // ------------------------------------------------------------------------------------------

    @Override
    public long gameTime(@NotNull Key world) {
        return this.time;
    }

    @Override
    public boolean isOwnedByCurrentThread(@NotNull WorldPosition position) {
        return this.currentRegion == null || this.currentRegion.equals(this.regionOf.apply(position.position()));
    }

    @Override
    public @Nullable MachineInstanceImpl machineAt(@NotNull WorldPosition position) {
        LoadedMachine machine = this.machines.get(position);
        return machine == null ? null : machine.instance();
    }

    @Override
    public void scheduleWake(@NotNull MachineInstanceImpl machine, long gameTime) {
        List<MachineInstanceImpl> due = region(machine.position()).timers.computeIfAbsent(gameTime, time -> new ArrayList<>());
        if (!due.contains(machine)) due.add(machine);
    }

    @Override
    public void markDirty(@NotNull MachineInstanceImpl machine) {
        this.dirty.add(machine);
    }

    @Override
    public void activityChanged(@NotNull MachineInstanceImpl machine) {
        Region region = region(machine.position());
        if (machine.isValid() && machine.schedulerState() == SchedulerState.ACTIVE) region.active.add(machine);
        else region.active.remove(machine);
    }

    @Override
    public void post(@NotNull WorldPosition target, @NotNull Runnable task) {
        region(target).tasks.addLast(task);
    }

    @Override
    public void dropResources(@NotNull WorldPosition position, @NotNull List<ResourceStack> resources) {
        this.dropped.addAll(resources);
    }

    private static final class Region {
        private final String id;
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private final TreeMap<Long, List<MachineInstanceImpl>> timers = new TreeMap<>();
        private final Set<MachineInstanceImpl> active = new LinkedHashSet<>();

        private Region(String id) {
            this.id = id;
        }
    }
}
