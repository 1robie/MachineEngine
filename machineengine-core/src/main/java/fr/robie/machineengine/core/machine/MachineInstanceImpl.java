package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.capability.CapabilityType;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.machine.CapabilityBinding;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.MachineEvents;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PushResult;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.process.ProcessControl;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeLookup;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.api.transfer.Transfers;
import fr.robie.machineengine.api.transfer.storage.CombinedResourceHandler;
import fr.robie.machineengine.api.upgrade.InstalledUpgrade;
import fr.robie.machineengine.api.upgrade.Modifier;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.upgrade.Modifiers;
import fr.robie.machineengine.api.upgrade.StandardModifiers;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.handoff.DedupeEntry;
import fr.robie.machineengine.core.machine.handoff.Escrow;
import fr.robie.machineengine.core.machine.handoff.Route;
import fr.robie.machineengine.core.machine.handoff.TransferId;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.random.RandomGenerator;

/**
 * A live machine (ARCHITECTURE.md §12): runtime state, scheduling, ports, process slot, upgrades and
 * handoff escrow. Owned by one thread; every method except {@link #inspect()} must run on it.
 */
public final class MachineInstanceImpl implements MachineInstance, LoadedMachine {
    final MachineServices services;
    final MachineHost host;
    final MachineType type;
    final WorldPosition position;
    private Direction facing;
    final Map<String, MachineStorage> storages = new LinkedHashMap<>();
    private final List<PortHandler> ports = new ArrayList<>();
    private final Map<Direction, ResourceHandler> sideHandlers = new EnumMap<>(Direction.class);
    private final Map<CapabilityBinding<?>, Object> capabilities = new IdentityHashMap<>();
    final Map<MachineDataKey<?>, Object> data = new LinkedHashMap<>();
    final Map<String, DataValue> unknownData = new LinkedHashMap<>();
    final Map<String, DataValue> unknownStorages = new LinkedHashMap<>();
    final List<DataValue> unknownUpgrades = new ArrayList<>();
    final List<DataValue> unknownEscrow = new ArrayList<>();
    @Nullable DataValue unknownProcess;
    final TransactionalValue<ProcessState> process;
    final TransactionalValue<Map<Route, Escrow>> escrow;
    final TransactionalValue<List<DedupeEntry>> dedupe;
    long sequence;
    /** Routes resting after a handoff came back with nothing accepted (not persisted). */
    private final Map<Route, Backoff> backoff = new LinkedHashMap<>();
    final Map<UpgradeType, Integer> upgrades = new LinkedHashMap<>();
    private List<Modifier> modifiers = List.of();
    private final MachineBehavior behavior;
    private final RandomGenerator random;
    private final Context context = new Context();
    private final Processes processes = new Processes();

    SchedulerState state = SchedulerState.ACTIVE;
    long wakeAt = -1;
    long lastTick;
    private boolean countSleptTicks;
    private @Nullable WakeReason pendingWake;
    private @Nullable WakeReason lastWakeReason;
    private @Nullable MachineError lastError;
    private boolean ticking;
    private @Nullable SchedulerState requestedState;
    private long requestedWakeAt = -1;
    private long elapsed = 1;
    private boolean dirty;
    private boolean valid = true;

    MachineInstanceImpl(@NotNull MachineServices services, @NotNull MachineHost host, @NotNull MachineType type,
                        @NotNull WorldPosition position, @NotNull Direction facing) {
        this.services = services;
        this.host = host;
        this.type = type;
        this.position = position;
        this.facing = facing;
        this.lastTick = host.gameTime(position.world());
        this.process = new TransactionalValue<>(null, this::markDirty);
        this.escrow = new TransactionalValue<>(Map.of(), this::markDirty);
        this.dedupe = new TransactionalValue<>(List.of(), this::markDirty);
        this.random = new SplittableRandom(seed(position, type));
        MachineSlot.Listener listener = new MachineSlot.Listener() {
            @Override
            public void inserted() {
                markDirty();
                wake(WakeReason.RESOURCE_INSERTED);
            }

            @Override
            public void extracted() {
                markDirty();
                wake(WakeReason.RESOURCE_EXTRACTED);
            }
        };
        for (StorageDefinition definition : type.storages()) {
            List<MachineSlot> slots = new ArrayList<>(definition.slots());
            for (int i = 0; i < definition.slots(); i++) {
                slots.add(new MachineSlot(definition.capacity(), definition.filter().bind(services.tags()), listener));
            }
            this.storages.put(definition.id(), new MachineStorage(definition, slots));
        }
        for (PortDefinition port : type.ports()) {
            MachineStorage storage = this.storages.get(port.storage());
            this.ports.add(new PortHandler(port, storage, services::tags, () -> host.gameTime(position.world()),
                    () -> port.ratePerTick() == Long.MAX_VALUE ? Long.MAX_VALUE
                            : Modifiers.apply(StandardModifiers.TRANSFER_RATE, port.ratePerTick(), this.modifiers)));
        }
        for (MachineDataKey<?> key : type.data()) {
            this.data.put(key, key.defaultValue());
        }
        this.behavior = type.behavior().create(type);
        rebuildSides();
    }

    private static long seed(WorldPosition position, MachineType type) {
        long seed = position.world().asString().hashCode();
        seed = seed * 31 + position.position().x();
        seed = seed * 31 + position.position().y();
        seed = seed * 31 + position.position().z();
        return seed * 31 + type.key().asString().hashCode();
    }

    private void rebuildSides() {
        this.sideHandlers.clear();
        Map<Direction, List<ResourceHandler>> bySide = new EnumMap<>(Direction.class);
        for (PortHandler port : this.ports) {
            if (port.definition().direction() == fr.robie.machineengine.api.machine.PortDirection.DISABLED) continue;
            for (RelativeSide side : port.definition().sides()) {
                bySide.computeIfAbsent(side.toAbsolute(this.facing), direction -> new ArrayList<>()).add(port);
            }
        }
        bySide.forEach((direction, handlers) ->
                this.sideHandlers.put(direction, handlers.size() == 1 ? handlers.getFirst() : new CombinedResourceHandler(handlers)));
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle (called by CoreMachines and hosts)
    // ------------------------------------------------------------------------------------------

    void loaded() {
        runBehavior(() -> this.behavior.onLoad(this.context));
        this.services.handoff().resume(this);
        postLifecycle(MachineEvents.MACHINE_LOAD);
    }

    void unloaded() {
        runBehavior(() -> this.behavior.onUnload(this.context));
        this.valid = false;
        postLifecycle(MachineEvents.MACHINE_UNLOAD);
    }

    /** @return everything the machine held, to be dropped by the host */
    List<ResourceStack> removed() {
        runBehavior(() -> this.behavior.onRemove(this.context));
        this.valid = false;
        this.services.handoff().removed(this);
        List<ResourceStack> drops = new ArrayList<>();
        for (MachineStorage storage : this.storages.values()) {
            for (StorageView view : storage.views()) {
                if (!view.isEmpty()) drops.add(view.variant().stack(view.amount()));
            }
        }
        ProcessState running = this.process.get();
        if (running != null) {
            drops.addAll(running.status() == ProcessStatus.OUTPUT_BLOCKED ? running.outputs() : running.inFlight());
        }
        for (Escrow pending : Objects.requireNonNull(this.escrow.get()).values()) {
            // In-flight handoffs are answered later; their remainder is dropped then (HandoffService).
            if (pending.returning()) drops.addAll(pending.stacks());
        }
        postLifecycle(MachineEvents.MACHINE_REMOVE);
        return drops;
    }

    void postLifecycle(fr.robie.machineengine.api.event.EventType<MachineEvents.Lifecycle> type) {
        if (this.services.events().hasListeners(type, EventPhase.POST)) {
            this.services.events().post(type, EventPhase.POST, new MachineEvents.Lifecycle(this));
        }
    }

    public boolean isValid() {
        return this.valid;
    }

    /** Runs one tick if the machine is active. Called by the host every game tick while active. */
    public void tick() {
        if (!this.valid || this.state != SchedulerState.ACTIVE) return;
        long now = gameTimeNow();
        this.elapsed = this.countSleptTicks ? Math.max(0, now - this.lastTick) : (now > this.lastTick ? 1 : 0);
        this.lastTick = now;
        this.countSleptTicks = false;
        this.requestedState = null;
        this.ticking = true;
        try {
            WakeReason wake = this.pendingWake;
            this.pendingWake = null;
            if (wake != null) this.behavior.onWake(this.context, wake);
            this.behavior.tick(this.context);
        } catch (RuntimeException e) {
            this.services.logger().log(Level.SEVERE, "Machine " + this.type.key().asString() + " at " + this.position + " failed", e);
            this.lastError = new MachineError.Generic(MachineEngineKeys.key("behavior_failed"),
                    "The machine behavior threw " + e.getClass().getSimpleName() + ": " + e.getMessage(), Map.of());
            this.requestedState = SchedulerState.SLEEPING;
            this.requestedWakeAt = -1;
        } finally {
            this.ticking = false;
        }
        if (this.requestedState != null) applyState(this.requestedState, this.requestedWakeAt);
    }

    /** Called by the host when a timer set through {@link MachineHost#scheduleWake} fires. */
    public void timerFired(long gameTime) {
        if (!this.valid || this.wakeAt < 0 || this.state == SchedulerState.ACTIVE) return;
        if (gameTime < this.wakeAt) {
            // Early (host schedulers may run a tick ahead of the world clock): wait for the rest.
            this.host.scheduleWake(this, this.wakeAt);
            return;
        }
        wake(WakeReason.TIMER);
    }

    /**
     * Called by a host that paused this machine without unloading it (e.g. its chunk stopped
     * ticking): the paused time does not count as elapsed (D4).
     */
    public void resumeClock() {
        this.lastTick = gameTimeNow() - (this.state == SchedulerState.ACTIVE ? 1 : 0);
        if (this.wakeAt >= 0) {
            // Keep the remaining delay of a timer that could not fire while paused.
            this.host.scheduleWake(this, Math.max(this.wakeAt, gameTimeNow()));
        }
    }

    private void applyState(SchedulerState newState, long newWakeAt) {
        boolean wasActive = this.state == SchedulerState.ACTIVE;
        this.state = newState;
        this.wakeAt = newState == SchedulerState.ACTIVE ? -1 : newWakeAt;
        this.countSleptTicks = newState == SchedulerState.SCHEDULED;
        if (this.wakeAt >= 0) this.host.scheduleWake(this, this.wakeAt);
        markDirty();
        if (wasActive != (newState == SchedulerState.ACTIVE)) this.host.activityChanged(this);
    }

    /** Restores scheduling state from persisted data (relative to {@code now}). */
    void restoreSchedule(SchedulerState state, long wakeIn, long sinceLastTick) {
        long now = gameTimeNow();
        this.state = state;
        this.lastTick = now - sinceLastTick;
        this.countSleptTicks = state == SchedulerState.SCHEDULED;
        this.wakeAt = wakeIn >= 0 ? now + wakeIn : -1;
        if (this.wakeAt >= 0) this.host.scheduleWake(this, this.wakeAt);
    }

    @Override
    public void wake(@NotNull WakeReason reason) {
        if (!this.valid || !this.state.wakesOn(reason)) return;
        this.state = SchedulerState.ACTIVE;
        this.wakeAt = -1;
        this.pendingWake = reason;
        this.lastWakeReason = reason;
        markDirty();
        this.host.activityChanged(this);
    }

    void markDirty() {
        if (this.dirty) return;
        this.dirty = true;
        this.host.markDirty(this);
    }

    boolean isDirty() {
        return this.dirty;
    }

    private long gameTimeNow() {
        return this.host.gameTime(this.position.world());
    }

    private void runBehavior(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            this.services.logger().log(Level.SEVERE, "Machine " + this.type.key().asString() + " at " + this.position + " failed", e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // MachineInstance
    // ------------------------------------------------------------------------------------------

    @Override
    public @NotNull MachineType type() {
        return this.type;
    }

    @Override
    public @NotNull WorldPosition position() {
        return this.position;
    }

    @Override
    public @Nullable MachineInstanceImpl instance() {
        return this;
    }

    @Override
    public @NotNull Direction facing() {
        return this.facing;
    }

    /** Called by the host when the block is rotated. */
    public void setFacing(@NotNull Direction facing) {
        if (this.facing == facing) return;
        this.facing = facing;
        rebuildSides();
        markDirty();
        wake(WakeReason.CONFIG_CHANGED);
    }

    @Override
    public @NotNull SchedulerState schedulerState() {
        return this.state;
    }

    @Override
    public @Nullable WakeReason lastWakeReason() {
        return this.lastWakeReason;
    }

    @Override
    public @Nullable MachineError lastError() {
        return this.lastError;
    }

    public @NotNull MachineContext context() {
        return this.context;
    }

    public @NotNull MachineServices services() {
        return this.services;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> @NotNull Optional<T> capability(@NotNull CapabilityType<T> type, @Nullable Direction side) {
        if (!this.valid) return Optional.empty();
        if (type.equals(Capabilities.RESOURCE_HANDLER)) {
            return side == null ? Optional.empty() : Optional.ofNullable((T) this.sideHandlers.get(side));
        }
        for (CapabilityBinding<?> binding : this.type.capabilities()) {
            if (!binding.type().equals(type)) continue;
            boolean exposed = binding.sides().isEmpty()
                    || (side != null && binding.sides().stream().anyMatch(relative -> relative.toAbsolute(this.facing) == side));
            if (exposed) {
                Object value = this.capabilities.computeIfAbsent(binding, b -> b.factory().apply(this.context));
                return Optional.of(type.type().cast(value));
            }
        }
        return Optional.empty();
    }

    @Override
    public @NotNull List<InstalledUpgrade> upgrades() {
        List<InstalledUpgrade> installed = new ArrayList<>();
        this.upgrades.forEach((upgrade, count) -> installed.add(new InstalledUpgrade(upgrade, count)));
        return installed;
    }

    @Override
    public @NotNull Result<InstalledUpgrade> installUpgrade(@NotNull UpgradeType upgrade, int count) {
        if (count < 1) throw new IllegalArgumentException("count must be at least 1");
        if (!this.services.registries().registry(Registries.UPGRADE_TYPE).containsValue(upgrade)) {
            return Result.err(new MachineError.MissingResource(Registries.UPGRADE_TYPE.key(), upgrade.key()));
        }
        if (!this.type.acceptsUpgrade(upgrade)) {
            return Result.err(new MachineError.InvalidConfiguration(this.type.key().asString() + " does not accept "
                    + upgrade.key().asString(), Map.of()));
        }
        int current = this.upgrades.getOrDefault(upgrade, 0);
        if (current == 0 && this.upgrades.size() >= this.type.upgradeSlots()) {
            return Result.err(new MachineError.InvalidConfiguration("All " + this.type.upgradeSlots() + " upgrade slots are used", Map.of()));
        }
        if (current + count > upgrade.maxStack()) {
            return Result.err(new MachineError.InvalidConfiguration("At most " + upgrade.maxStack() + " "
                    + upgrade.key().asString() + " can be installed", Map.of()));
        }
        this.upgrades.put(upgrade, current + count);
        upgradesChanged();
        return Result.ok(new InstalledUpgrade(upgrade, current + count));
    }

    @Override
    public int removeUpgrade(@NotNull UpgradeType upgrade, int count) {
        int current = this.upgrades.getOrDefault(upgrade, 0);
        int removed = Math.min(current, Math.max(0, count));
        if (removed == 0) return 0;
        if (current == removed) this.upgrades.remove(upgrade); else this.upgrades.put(upgrade, current - removed);
        upgradesChanged();
        return removed;
    }

    void upgradesChanged() {
        refreshModifiers();
        markDirty();
        wake(WakeReason.UPGRADE_CHANGED);
    }

    void refreshModifiers() {
        List<Modifier> all = new ArrayList<>();
        this.upgrades.forEach((upgrade, count) -> {
            for (int i = 0; i < count; i++) all.addAll(upgrade.modifiers());
        });
        this.modifiers = List.copyOf(all);
    }

    @Override
    public @NotNull MachineInspection inspect() {
        Map<String, List<StorageView>> contents = new LinkedHashMap<>();
        Map<String, Integer> locked = new LinkedHashMap<>();
        this.storages.forEach((id, storage) -> {
            contents.put(id, storage.views());
            if (storage.lockedSlots() > 0) locked.put(id, storage.lockedSlots());
        });
        List<ResourceStack> escrowed = new ArrayList<>();
        Objects.requireNonNull(this.escrow.get()).values().forEach(pending -> escrowed.addAll(pending.stacks()));
        ProcessState running = this.process.get();
        List<String> unknown = new ArrayList<>(this.unknownData.keySet());
        this.unknownStorages.keySet().forEach(id -> unknown.add("storage:" + id));
        if (this.unknownProcess != null) unknown.add("process");
        if (!this.unknownUpgrades.isEmpty()) unknown.add("upgrades");
        if (!this.unknownEscrow.isEmpty()) unknown.add("escrow");
        return new MachineInspection(this.type.key(), this.position, this.facing, false, this.state, this.lastWakeReason,
                this.lastError, contents, running == null ? null : running.view(), upgrades(), escrowed, locked, unknown);
    }

    @Override
    public @NotNull DataMap save(long gameTime) {
        this.dirty = false;
        return MachineCodec.encode(this, gameTime);
    }

    // -- accessors for the handoff service (another package) ------------------------------------

    public @NotNull MachineHost hostForHandoff() {
        return this.host;
    }

    public @NotNull Transaction openHandoffTransaction() {
        return openTransaction();
    }

    public @NotNull Map<Route, Escrow> escrowForHandoff() {
        return Objects.requireNonNull(this.escrow.get());
    }

    public void setEscrowForHandoff(@NotNull Map<Route, Escrow> value, @NotNull TransactionContext transaction) {
        this.escrow.set(value, transaction);
    }

    public @NotNull List<DedupeEntry> dedupeForHandoff() {
        return Objects.requireNonNull(this.dedupe.get());
    }

    public void setDedupeForHandoff(@NotNull List<DedupeEntry> value, @NotNull TransactionContext transaction) {
        this.dedupe.set(value, transaction);
    }

    public void backOff(@NotNull Route route, boolean targetMissing, long ticks) {
        this.backoff.put(route, new Backoff(gameTimeNow() + ticks, targetMissing));
    }

    private record Backoff(long until, boolean targetMissing) {
    }

    public long nextSequenceForHandoff() {
        markDirty();
        return this.sequence++;
    }

    Direction absolute(RelativeSide side) {
        return side.toAbsolute(this.facing);
    }

    @Override
    public String toString() {
        return "Machine[" + this.type.key().asString() + " at " + this.position + ", " + this.state + "]";
    }

    // ------------------------------------------------------------------------------------------
    // MachineContext
    // ------------------------------------------------------------------------------------------

    private final class Context implements MachineContext {

        @Override
        public @NotNull MachineInstance machine() {
            return MachineInstanceImpl.this;
        }

        @Override
        public @NotNull ResourceHandler storage(@NotNull String id) {
            MachineStorage storage = MachineInstanceImpl.this.storages.get(id);
            if (storage == null) throw new IllegalArgumentException(type.key().asString() + " has no storage '" + id + "'");
            return storage;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @NotNull T get(@NotNull MachineDataKey<T> key) {
            requireDeclared(key);
            return (T) data.get(key);
        }

        @Override
        public <T> void set(@NotNull MachineDataKey<T> key, @NotNull T value) {
            requireDeclared(key);
            boolean keptRaw = unknownData.remove(key.id()) != null;
            if (!Objects.equals(data.put(key, Objects.requireNonNull(value)), value) || keptRaw) markDirty();
        }

        private void requireDeclared(MachineDataKey<?> key) {
            if (!data.containsKey(key)) {
                throw new IllegalArgumentException(type.key().asString() + " does not declare data '" + key.id() + "'");
            }
        }

        @Override
        public long gameTime() {
            return gameTimeNow();
        }

        @Override
        public long elapsedTicks() {
            return elapsed;
        }

        @Override
        public @NotNull TransactionManager transactions() {
            return services.transactions();
        }

        @Override
        public @NotNull ResourceTags tags() {
            return services.tags();
        }

        @Override
        public @NotNull RegistryAccess registries() {
            return services.registries();
        }

        @Override
        public @NotNull RecipeLookup recipes() {
            return services.recipes();
        }

        @Override
        public @NotNull ProcessControl process() {
            return processes;
        }

        @Override
        public long modified(@NotNull ModifierType modifier, long base) {
            return Modifiers.apply(modifier, base, modifiers);
        }

        @Override
        public @NotNull RandomGenerator random() {
            return random;
        }

        @Override
        public void sleep() {
            request(SchedulerState.SLEEPING, -1);
        }

        @Override
        public void waitForInput() {
            request(SchedulerState.WAITING, -1);
        }

        @Override
        public void block(@NotNull MachineError reason, long retryTicks) {
            lastError = reason;
            request(SchedulerState.BLOCKED, retryTicks > 0 ? gameTimeNow() + retryTicks : -1);
        }

        @Override
        public void wakeIn(long ticks) {
            if (ticks <= 0) {
                requestedState = null;
                return;
            }
            request(SchedulerState.SCHEDULED, gameTimeNow() + ticks);
        }

        private void request(SchedulerState requested, long wake) {
            if (ticking) {
                requestedState = requested;
                requestedWakeAt = wake;
            } else {
                applyState(requested, wake);
            }
        }

        @Override
        public void error(@Nullable MachineError error) {
            lastError = error;
        }

        @Override
        public <T> @NotNull Optional<T> neighbor(@NotNull RelativeSide side, @NotNull CapabilityType<T> type) {
            Direction direction = absolute(side);
            WorldPosition target = position.relative(direction);
            if (!host.isOwnedByCurrentThread(target)) return Optional.empty();
            MachineInstanceImpl neighbor = host.machineAt(target);
            return neighbor == null ? Optional.empty() : neighbor.capability(type, direction.opposite());
        }

        @Override
        public @NotNull PushResult push(@NotNull RelativeSide side, @NotNull String storageId,
                                        @NotNull Predicate<ResourceVariant> filter, long maxAmount) {
            ResourceHandler source = storage(storageId);
            Direction direction = absolute(side);
            Route route = new Route(position.relative(direction), direction.opposite());
            if (Objects.requireNonNull(escrow.get()).containsKey(route)) {
                services.handoff().retryReturn(MachineInstanceImpl.this, route);
                return PushResult.BUSY;
            }
            Backoff resting = backoff.get(route);
            if (resting != null) {
                if (gameTimeNow() < resting.until()) return resting.targetMissing() ? PushResult.NO_TARGET : PushResult.BUSY;
                backoff.remove(route);
            }
            if (host.isOwnedByCurrentThread(route.target())) {
                MachineInstanceImpl neighbor = host.machineAt(route.target());
                if (neighbor == null) return PushResult.NO_TARGET;
                ResourceHandler target = neighbor.capability(Capabilities.RESOURCE_HANDLER, route.side()).orElse(null);
                if (target == null) return PushResult.NO_TARGET;
                try (Transaction transaction = openTransaction()) {
                    long moved = Transfers.move(source, target, filter, maxAmount, transaction).moved();
                    transaction.commit();
                    return new PushResult(PushResult.Kind.DELIVERED, moved);
                }
            }
            return services.handoff().send(MachineInstanceImpl.this, route, storageId, filter, maxAmount);
        }
    }

    Transaction openTransaction() {
        TransactionContext current = this.services.transactions().current();
        return current == null ? this.services.transactions().openOuter() : current.openNested();
    }

    // ------------------------------------------------------------------------------------------
    // ProcessControl (D15)
    // ------------------------------------------------------------------------------------------

    private final class Processes implements ProcessControl {

        @Override
        public @Nullable ProcessView current() {
            ProcessState running = process.get();
            return running == null ? null : running.view();
        }

        @Override
        public @NotNull Result<ProcessView> start(@NotNull ProcessingRecipe recipe, @NotNull ProcessBinding binding) {
            if (process.get() != null) {
                return Result.err(new MachineError.InvalidConfiguration("A process is already running", Map.of()));
            }
            if (unknownProcess != null) {
                return Result.err(new MachineError.InvalidConfiguration("The saved process of this machine could not be loaded", Map.of()));
            }
            List<MachineStorage> inputs = binding.inputs().stream().map(MachineInstanceImpl.this::requireStorage).toList();
            requireStorage(binding.output());
            try (Transaction transaction = openTransaction()) {
                List<ResourceStack> consumed = new ArrayList<>();
                for (ResourceMatcher input : recipe.inputs()) {
                    long missing = extractMatching(inputs, input, transaction, consumed);
                    if (missing > 0) {
                        return Result.err(new MachineError.InsufficientResource(recipe.key(), input.amount(), input.amount() - missing));
                    }
                }
                long duration = context.modified(StandardModifiers.PROCESS_DURATION, recipe.duration());
                List<ResourceStack> costs = recipe.costPerTick().stream()
                        .map(cost -> cost.withAmount(context.modified(StandardModifiers.PROCESS_COST, cost.amount())))
                        .filter(cost -> cost.amount() > 0)
                        .toList();
                ProcessState started = new ProcessState(recipe.key(), ProcessStatus.RUNNING, 0, duration, consumed,
                        recipe.outputs(), costs, binding);
                process.set(started, transaction);
                transaction.onCommit(() -> postProcess(MachineEvents.PROCESS_START, started));
                transaction.commit();
                return Result.ok(started.view());
            }
        }

        @Override
        public @NotNull ProcessStatus advance() {
            ProcessState running = process.get();
            if (running == null) return ProcessStatus.IDLE;
            return switch (running.status()) {
                case RUNNING, WAITING_FOR_COST -> progress(running);
                case OUTPUT_BLOCKED -> deliver(running);
                case RETURNING -> giveBack(running);
                case IDLE, COMPLETED -> running.status();
            };
        }

        @Override
        public @NotNull ProcessStatus cancel() {
            ProcessState running = process.get();
            if (running == null) return ProcessStatus.IDLE;
            if (running.status() == ProcessStatus.OUTPUT_BLOCKED) return deliver(running);
            return giveBack(running.withInFlight(ProcessStatus.RETURNING, running.inFlight()));
        }

        private ProcessStatus progress(ProcessState running) {
            long steps = Math.min(elapsed, running.duration() - running.progress());
            long progress = running.progress();
            ProcessStatus status = ProcessStatus.RUNNING;
            if (running.costPerTick().isEmpty()) {
                progress += steps;
            } else {
                ResourceHandler costs = requireStorage(Objects.requireNonNull(running.binding().costStorage(), "cost storage"));
                for (long i = 0; i < steps; i++) {
                    if (!pay(costs, running.costPerTick())) {
                        status = ProcessStatus.WAITING_FOR_COST;
                        break;
                    }
                    progress++;
                }
            }
            ProcessState updated = running.with(status, progress);
            if (progress >= running.duration()) return deliver(updated);
            if (!updated.equals(running)) {
                try (Transaction transaction = openTransaction()) {
                    process.set(updated, transaction);
                    transaction.commit();
                }
            }
            return status;
        }

        private boolean pay(ResourceHandler storage, List<ResourceStack> costs) {
            try (Transaction transaction = openTransaction()) {
                for (ResourceStack cost : costs) {
                    if (storage.extract(cost.variant(), cost.amount(), transaction) != cost.amount()) return false;
                }
                transaction.commit();
                return true;
            }
        }

        private ProcessStatus deliver(ProcessState finished) {
            MachineStorage output = requireStorage(finished.binding().output());
            try (Transaction transaction = openTransaction()) {
                boolean fits = true;
                for (ResourceStack stack : finished.outputs()) {
                    if (output.insert(stack.variant(), stack.amount(), transaction) != stack.amount()) {
                        fits = false;
                        break;
                    }
                }
                if (!fits) {
                    transaction.abort();
                    try (Transaction blocked = openTransaction()) {
                        process.set(finished.with(ProcessStatus.OUTPUT_BLOCKED, finished.duration()), blocked);
                        blocked.commit();
                    }
                    return ProcessStatus.OUTPUT_BLOCKED;
                }
                process.set(null, transaction);
                ProcessState completed = finished.with(ProcessStatus.COMPLETED, finished.duration());
                transaction.onCommit(() -> postProcess(MachineEvents.PROCESS_COMPLETE, completed));
                transaction.commit();
                return ProcessStatus.COMPLETED;
            }
        }

        private ProcessStatus giveBack(ProcessState returning) {
            List<MachineStorage> inputs = returning.binding().inputs().stream().map(MachineInstanceImpl.this::requireStorage).toList();
            try (Transaction transaction = openTransaction()) {
                List<ResourceStack> left = new ArrayList<>();
                for (ResourceStack stack : returning.inFlight()) {
                    long remaining = stack.amount();
                    for (MachineStorage input : inputs) {
                        if (remaining == 0) break;
                        remaining -= input.insert(stack.variant(), remaining, transaction);
                    }
                    if (remaining > 0) left.add(stack.withAmount(remaining));
                }
                process.set(left.isEmpty() ? null : returning.withInFlight(ProcessStatus.RETURNING, left), transaction);
                transaction.commit();
                return left.isEmpty() ? ProcessStatus.IDLE : ProcessStatus.RETURNING;
            }
        }

        private void postProcess(fr.robie.machineengine.api.event.EventType<MachineEvents.Process> type, ProcessState state) {
            if (services.events().hasListeners(type, EventPhase.POST)) {
                services.events().post(type, EventPhase.POST, new MachineEvents.Process(MachineInstanceImpl.this, state.view()));
            }
        }
    }

    MachineStorage requireStorage(String id) {
        MachineStorage storage = this.storages.get(id);
        if (storage == null) throw new IllegalArgumentException(this.type.key().asString() + " has no storage '" + id + "'");
        return storage;
    }

    /**
     * Extracts {@code matcher.amount()} of variants matching it from {@code storages}, in order.
     *
     * @return how much is still missing
     */
    private long extractMatching(List<MachineStorage> storages, ResourceMatcher matcher, TransactionContext transaction,
                                 List<ResourceStack> consumed) {
        long missing = matcher.amount();
        ResourceTags tags = this.services.tags();
        for (MachineStorage storage : storages) {
            for (StorageView view : storage.views()) {
                if (missing == 0) return 0;
                if (view.isEmpty() || !matcher.matches(view.variant(), tags)) continue;
                long extracted = storage.extract(view.variant(), Math.min(missing, view.amount()), transaction);
                if (extracted > 0) {
                    consumed.add(view.variant().stack(extracted));
                    missing -= extracted;
                }
            }
        }
        return missing;
    }
}
