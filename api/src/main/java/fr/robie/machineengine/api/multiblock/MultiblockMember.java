package fr.robie.machineengine.api.multiblock;

import fr.robie.machineengine.api.capability.Capabilities;
import fr.robie.machineengine.api.config.BlockOptions;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.transfer.TransactionContext;
import fr.robie.machineengine.api.transfer.Transfers;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.MachineEngineKeys;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * A block of a multiblock (D33): a frame block, valve or port, as a machine behavior. Each finds its
 * structure when it loads (or is placed), and unforms it when removed. The anchor sizes its storages for
 * the structure and checks it again every few seconds (blocks placed inside, cells added...). A member
 * that passes through (storages given) shows the anchor's storages on all its sides.
 *
 * <p>Built with {@link #of(List)}; data-driven machines use the behavior type
 * {@code machineengine:multiblock_member}.</p>
 */
public final class MultiblockMember implements MachineBehavior {
    /** A port's mode: {@code input} (default) or {@code output}, pushing to its neighbors. Builders may use another key. */
    public static final MachineDataKey<String> PORT_MODE = MachineDataKey.of("port_mode",
            VersionedCodec.builder(MachineEngineKeys.key("port_mode"), Codecs.STRING).build(), "input");
    private static final long RECHECK_TICKS = 100;

    /** Work an anchor does while its structure is formed (e.g. evaporating), every {@code everyTicks}. */
    public interface AnchorTask {
        void run(@NotNull MachineContext anchor, @NotNull MultiblockStructure structure);
    }

    /** What an output port pushes per tick, from the structure and the port block's options (D34). */
    @FunctionalInterface
    public interface PortRate {
        long of(@NotNull MultiblockStructure structure, @NotNull BlockOptions port);
    }

    private final List<MultiblockType> types;
    private final List<String> storages;
    private final List<String> insertInto;
    private final List<String> extractFrom;
    private final boolean exclusive;
    private final @Nullable PortRate outputRate;
    private final @Nullable AnchorTask task;
    private final long taskTicks;
    private final MachineDataKey<String> portMode;
    /** Modal valves and ports: by mode ({@link #PORT_MODE}), the anchor's storages given out; null for others. */
    private @Nullable Map<String, List<String>> pushes;

    private MultiblockMember(List<MultiblockType> types, List<String> insertInto, List<String> extractFrom, boolean exclusive,
                             @Nullable PortRate outputRate, @Nullable AnchorTask task, long taskTicks,
                             MachineDataKey<String> portMode) {
        this.types = List.copyOf(types);
        this.insertInto = List.copyOf(insertInto);
        this.extractFrom = List.copyOf(extractFrom);
        LinkedHashSet<String> all = new LinkedHashSet<>(insertInto);
        all.addAll(extractFrom);
        this.storages = List.copyOf(all);
        this.exclusive = exclusive;
        this.outputRate = outputRate;
        this.task = task;
        this.taskTicks = taskTicks;
        this.portMode = portMode;
    }

    /** Members of a structure of one of {@code types} (tried in order). */
    public static @NotNull Builder of(@NotNull List<MultiblockType> types) {
        return new Builder(List.copyOf(types));
    }

    /** Makes the behavior factories of members. */
    public static final class Builder {
        private final List<MultiblockType> types;
        private MachineDataKey<String> portMode = PORT_MODE;

        private Builder(List<MultiblockType> types) {
            this.types = types;
        }

        /** The machine data key ports keep their mode in (an addon keeping its own saved key). */
        public @NotNull Builder portMode(@NotNull MachineDataKey<String> key) {
            this.portMode = key;
            return this;
        }

        /** A casing or glass: finds the structure; anchors it when it is the anchor block. */
        public @NotNull MachineBehavior.Factory frame() {
            return type -> new MultiblockMember(this.types, List.of(), List.of(), false, null, null, RECHECK_TICKS, this.portMode);
        }

        /** A frame block whose anchor also runs {@code task} every {@code everyTicks} while the structure is formed. */
        public @NotNull MachineBehavior.Factory frame(@NotNull AnchorTask task, long everyTicks) {
            return type -> new MultiblockMember(this.types, List.of(), List.of(), false, null, task, everyTicks, this.portMode);
        }

        /**
         * A valve: passes through to the anchor's {@code storages}. With {@code exclusive}, the structure
         * holds one of them at a time (a tank holds a fluid or a chemical).
         */
        public @NotNull MachineBehavior.Factory valve(@NotNull List<String> storages, boolean exclusive) {
            return type -> new MultiblockMember(this.types, storages, storages, exclusive, null, null, RECHECK_TICKS, this.portMode);
        }

        /** A valve filling the anchor's {@code insertInto} storages and emptying its {@code extractFrom} ones. */
        public @NotNull MachineBehavior.Factory valve(@NotNull List<String> insertInto, @NotNull List<String> extractFrom) {
            return type -> new MultiblockMember(this.types, insertInto, extractFrom, false, null, null, RECHECK_TICKS, this.portMode);
        }

        /**
         * A valve or port with modes (a configurator cycles them): it fills the anchor's {@code insertInto}
         * storages, and in each mode gives out the storages {@code pushes} lists for it (pushed to its
         * neighbors, and taken by pipes). The block shows the mode ({@code mode} property, or
         * {@code active} when not in {@code input}).
         */
        public @NotNull MachineBehavior.Factory modal(@NotNull List<String> insertInto, @NotNull Map<String, List<String>> pushes) {
            return type -> {
                List<String> all = new ArrayList<>();
                pushes.values().forEach(all::addAll);
                MultiblockMember behavior = new MultiblockMember(this.types, insertInto, all, false, null, null, RECHECK_TICKS, this.portMode);
                behavior.pushes = Map.copyOf(pushes);
                return behavior;
            };
        }

        /** A port: passes {@code storage} in, or (in {@code output} mode) pushes it out at {@code outputRate} per tick. */
        public @NotNull MachineBehavior.Factory port(@NotNull String storage, @NotNull ToLongFunction<MultiblockStructure> outputRate) {
            return port(storage, (structure, options) -> outputRate.applyAsLong(structure));
        }

        /** A port whose output rate also depends on the port block's options (D34). */
        public @NotNull MachineBehavior.Factory port(@NotNull String storage, @NotNull PortRate outputRate) {
            return type -> new MultiblockMember(this.types, List.of(storage), List.of(storage), false, outputRate, null, RECHECK_TICKS,
                    this.portMode);
        }
    }

    @Override
    public void onLoad(@NotNull MachineContext context) {
        if (!this.storages.isEmpty()) context.exposeHandler(new PassThrough(context));
        context.wakeIn(1); // find the structure once the neighbors' chunks are there
    }

    @Override
    public void tick(@NotNull MachineContext context) {
        WorldPosition here = context.machine().position();
        MultiblockStructure structure = Multiblocks.containing(here);
        if (structure == null) structure = detect(context, here);
        if (structure != null && structure.anchor().equals(here)) {
            // The anchor: still valid? Then size the storages for it.
            MultiblockStructure again = detect(context, here);
            if (again == null) {
                Multiblocks.unform(structure);
                structure = null;
            } else {
                structure = again;
                Multiblocks.form(again);
                again.type().sizes().of(again, context.options()).forEach(context::setStorageCapacity);
                if (this.task != null) this.task.run(context, again);
            }
            context.wakeIn(this.taskTicks);
        }
        if (this.pushes != null) {
            String mode = context.get(this.portMode);
            context.world().setBlockProperty("mode", mode);
            context.setActive(!"input".equals(mode));
            List<String> given = this.pushes.getOrDefault(mode, List.of());
            if (structure != null && !given.isEmpty()) {
                for (String storage : given) pushOut(context, structure, storage, Long.MAX_VALUE);
                context.wakeIn(5);
            } else if (structure == null || !structure.anchor().equals(here)) {
                context.sleep();
            }
            return;
        }
        boolean output = this.outputRate != null && "output".equals(context.get(this.portMode));
        context.setActive(structure != null && (this.outputRate == null || output));
        if (output && structure != null) {
            push(context, structure);
            context.wakeIn(1);
        } else if (structure == null || !structure.anchor().equals(here)) {
            context.sleep();
        }
    }

    private @Nullable MultiblockStructure detect(MachineContext context, WorldPosition here) {
        MultiblockStructure found = null;
        for (MultiblockType type : this.types) {
            found = Multiblocks.detect(context, here, type);
            if (found != null) break;
        }
        if (found == null) return null;
        Multiblocks.form(found);
        if (!found.anchor().equals(here)) context.machines().wake(found.anchor()); // it sizes its storages
        return found;
    }

    /** Output port: the structure's energy into every neighbor, up to the providers' rate. */
    private void push(MachineContext context, MultiblockStructure structure) {
        pushOut(context, structure, this.storages.getFirst(), this.outputRate.of(structure, context.options()));
    }

    /** The anchor's {@code storage} into every neighbor outside the structure, up to {@code budget}. */
    private static void pushOut(MachineContext context, MultiblockStructure structure, String storage, long budget) {
        ResourceHandler source = context.machines().storage(structure.anchor(), storage).orElse(null);
        if (source == null) return;
        for (RelativeSide side : RelativeSide.values()) {
            if (budget <= 0) return;
            ResourceHandler target = context.neighbor(side, Capabilities.RESOURCE_HANDLER).orElse(null);
            if (target == null || Multiblocks.containing(neighborPosition(context, side)) == structure) continue;
            try (Transaction transaction = context.transactions().openOuter()) {
                budget -= Transfers.move(source, target, variant -> true, budget, transaction).moved();
                transaction.commit();
            }
        }
    }

    private static WorldPosition neighborPosition(MachineContext context, RelativeSide side) {
        return context.machine().position().relative(side.toAbsolute(context.machine().facing()));
    }

    @Override
    public void onRemove(@NotNull MachineContext context) {
        MultiblockStructure structure = Multiblocks.containing(context.machine().position());
        if (structure == null) return;
        Multiblocks.unform(structure);
        context.machines().wake(structure.anchor()); // keeps its contents, unformed
    }

    @Override
    public void onWake(@NotNull MachineContext context, @NotNull WakeReason reason) {
    }

    /** What a valve or port shows: the anchor's storages while the structure is formed, nothing otherwise. */
    private final class PassThrough implements ResourceHandler {
        private final MachineContext context;

        private PassThrough(MachineContext context) {
            this.context = context;
        }

        private List<ResourceHandler> targets() {
            return targets(MultiblockMember.this.storages);
        }

        private List<ResourceHandler> targets(List<String> storages) {
            MultiblockStructure structure = Multiblocks.containing(this.context.machine().position());
            if (structure == null) return List.of();
            List<ResourceHandler> targets = new ArrayList<>();
            for (String storage : storages) {
                this.context.machines().storage(structure.anchor(), storage).ifPresent(targets::add);
            }
            return targets;
        }

        private boolean output() {
            return MultiblockMember.this.outputRate != null && "output".equals(this.context.get(MultiblockMember.this.portMode));
        }

        @Override
        public long insert(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
            if (output()) return 0;
            List<ResourceHandler> targets = targets(MultiblockMember.this.insertInto);
            for (ResourceHandler target : targets) {
                if (MultiblockMember.this.exclusive && othersHold(targets, target)) continue;
                long inserted = target.insert(variant, maxAmount, transaction);
                if (inserted > 0) return inserted;
            }
            return 0;
        }

        /** A dynamic tank holds a fluid or a chemical: not both. */
        private boolean othersHold(List<ResourceHandler> targets, ResourceHandler target) {
            for (ResourceHandler other : targets) {
                if (other != target && other.views().stream().anyMatch(view -> !view.isEmpty())) return true;
            }
            return false;
        }

        @Override
        public long extract(@NotNull ResourceVariant variant, long maxAmount, @NotNull TransactionContext transaction) {
            if (MultiblockMember.this.outputRate != null && !output()) return 0; // an input port gives nothing
            List<String> from = MultiblockMember.this.pushes == null ? MultiblockMember.this.extractFrom
                    : MultiblockMember.this.pushes.getOrDefault(this.context.get(MultiblockMember.this.portMode), List.of());
            long extracted = 0;
            for (ResourceHandler target : targets(from)) {
                extracted += target.extract(variant, maxAmount - extracted, transaction);
                if (extracted >= maxAmount) break;
            }
            return extracted;
        }

        @Override
        public @NotNull List<StorageView> views() {
            List<StorageView> views = new ArrayList<>();
            for (ResourceHandler target : targets()) views.addAll(target.views());
            return views;
        }
    }
}
