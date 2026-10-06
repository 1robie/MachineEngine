package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue.DataList;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.StorageCodec;
import fr.robie.machineengine.api.error.MachineError.SerializationError;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.handoff.DedupeEntry;
import fr.robie.machineengine.core.machine.handoff.Escrow;
import fr.robie.machineengine.core.machine.handoff.Route;
import fr.robie.machineengine.core.machine.handoff.TransferId;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The persisted form of a machine (ARCHITECTURE.md §20.3). Anything that cannot be decoded is kept
 * verbatim and written back in place (D3): unknown machine types become placeholders, unknown
 * resource types lock their slot, undeclared data and storages are carried along.
 *
 * <p>Time is stored relative to the save, so a machine resumes exactly where it stopped however long
 * its chunk stayed unloaded (D4).</p>
 */
final class MachineCodec {
    static final int FORMAT = 1;
    static final String FORMAT_FIELD = "format";
    static final String TYPE = "type";
    static final String FACING = "facing";
    static final String SAVED_AT = "savedAt";
    static final String SINCE_TICK = "sinceTick";
    static final String SCHEDULER = "scheduler";
    static final String STORAGES = "storages";
    static final String DATA = "data";
    static final String PROCESS = "process";
    static final String UPGRADES = "upgrades";
    static final String ESCROW = "escrow";
    static final String SEQUENCE = "sequence";
    static final String DEDUPE = "dedupe";
    /** Side configuration: port id to the sides it is on, for ports moved from the type's sides. */
    static final String SIDES = "sides";
    /** Runtime storage capacities (multiblocks): storage id to capacity per slot. */
    static final String CAPACITIES = "capacities";

    private MachineCodec() {
    }

    // ------------------------------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------------------------------

    static DataMap encode(MachineInstanceImpl machine, long gameTime) {
        StorageCodec<ResourceStack> stacks = machine.services.resourceCodecs().stack();
        DataMap.Builder builder = DataMap.builder()
                .put(FORMAT_FIELD, FORMAT)
                .put(TYPE, machine.type.key().asString())
                .put(FACING, machine.facing().name().toLowerCase(Locale.ROOT))
                .put(SAVED_AT, gameTime)
                .put(SINCE_TICK, Math.max(0, gameTime - machine.lastTick))
                .put("active", machine.active)
                .put(SCHEDULER, DataMap.builder()
                        .put("state", machine.state.name())
                        .put("wakeIn", machine.wakeAt < 0 ? -1 : Math.max(0, machine.wakeAt - gameTime))
                        .build());

        DataMap.Builder storages = DataMap.builder();
        machine.storages.forEach((id, storage) -> {
            List<DataValue> slots = new ArrayList<>();
            for (MachineSlot slot : storage.slots()) slots.add(encodeSlot(slot, stacks));
            DataValue extra = machine.unknownStorages.get(id);
            if (extra instanceof DataList overflow) slots.addAll(overflow.values());
            storages.put(id, DataValue.list(slots));
        });
        machine.unknownStorages.forEach((id, raw) -> {
            if (!machine.storages.containsKey(id)) storages.put(id, raw);
        });
        builder.put(STORAGES, storages.build());

        DataMap.Builder data = DataMap.builder();
        machine.data.forEach((key, value) -> data.put(key.id(), encodeData(key, value)));
        machine.unknownData.forEach(data::put);
        builder.put(DATA, data.build());

        ProcessState process = machine.process.get();
        if (process != null) {
            builder.put(PROCESS, encodeProcess(process, stacks));
        } else if (machine.unknownProcess != null) {
            builder.put(PROCESS, machine.unknownProcess);
        }

        List<DataValue> upgrades = new ArrayList<>();
        machine.upgrades.forEach((upgrade, count) -> upgrades.add(DataMap.builder()
                .put(TYPE, upgrade.key().asString()).put("count", count).build()));
        upgrades.addAll(machine.unknownUpgrades);
        builder.put(UPGRADES, DataValue.list(upgrades));

        List<DataValue> escrow = new ArrayList<>();
        for (Escrow pending : Objects.requireNonNull(machine.escrow.get()).values()) escrow.add(encodeEscrow(pending, stacks));
        escrow.addAll(machine.unknownEscrow);
        builder.put(ESCROW, DataValue.list(escrow));
        builder.put(SEQUENCE, machine.sequence);

        List<DataValue> dedupe = new ArrayList<>();
        for (DedupeEntry entry : Objects.requireNonNull(machine.dedupe.get())) {
            dedupe.add(DataMap.builder()
                    .put("transfer", encodeTransfer(entry.id()))
                    .put("accepted", Codecs.list(Codecs.LONG).encode(entry.accepted()))
                    .build());
        }
        builder.put(DEDUPE, DataValue.list(dedupe));

        if (!machine.capacities.isEmpty()) {
            DataMap.Builder capacities = DataMap.builder();
            machine.capacities.forEach(capacities::put);
            builder.put(CAPACITIES, capacities.build());
        }
        var configured = machine.configuredSides();
        if (!configured.isEmpty()) {
            DataMap.Builder sides = DataMap.builder();
            configured.forEach((port, set) -> sides.put(port, DataValue.list(set.stream()
                    .map(side -> (DataValue) DataValue.of(side.name().toLowerCase(Locale.ROOT))).toList())));
            builder.put(SIDES, sides.build());
        }
        return builder.build();
    }

    private static DataValue encodeSlot(MachineSlot slot, StorageCodec<ResourceStack> stacks) {
        if (slot.lockedData() != null) return DataMap.builder().put("locked", slot.lockedData()).build();
        StorageView view = slot.view();
        if (view.isEmpty()) return DataMap.EMPTY;
        return DataMap.builder().put("stack", stacks.encode(view.variant().stack(view.amount()))).build();
    }

    @SuppressWarnings("unchecked")
    private static <T> DataValue encodeData(MachineDataKey<T> key, Object value) {
        return key.codec().encode((T) value);
    }

    private static DataMap encodeProcess(ProcessState process, StorageCodec<ResourceStack> stacks) {
        StorageCodec<List<ResourceStack>> list = Codecs.list(stacks);
        DataMap.Builder binding = DataMap.builder()
                .put("inputs", Codecs.list(Codecs.STRING).encode(process.binding().inputs()))
                .put("output", process.binding().output());
        if (!process.binding().costs().isEmpty()) binding.put("costs", Codecs.list(Codecs.STRING).encode(process.binding().costs()));
        if (!process.binding().extraOutputs().isEmpty()) {
            binding.put("extraOutputs", Codecs.list(Codecs.STRING).encode(process.binding().extraOutputs()));
        }
        return DataMap.builder()
                .put("recipe", process.recipe().asString())
                .put("status", process.status().name())
                .put("progress", process.progress())
                .put("duration", process.duration())
                .put("inFlight", list.encode(process.inFlight()))
                .put("outputs", list.encode(process.outputs()))
                .put("costs", list.encode(process.costPerTick()))
                .put("binding", binding.build())
                .build();
    }

    private static DataMap encodeEscrow(Escrow escrow, StorageCodec<ResourceStack> stacks) {
        return DataMap.builder()
                .put("transfer", encodeTransfer(escrow.id()))
                .put("target", encodePosition(escrow.route().target()))
                .put("side", escrow.route().side().name().toLowerCase(Locale.ROOT))
                .put("storage", escrow.storage())
                .put("stacks", Codecs.list(stacks).encode(escrow.stacks()))
                .put("returning", escrow.returning())
                .build();
    }

    private static DataMap encodeTransfer(TransferId id) {
        return encodePosition(id.source()).with("sequence", DataValue.of(id.sequence()));
    }

    static DataMap encodePosition(WorldPosition position) {
        return DataMap.builder()
                .put("world", position.world().asString())
                .put("x", position.position().x())
                .put("y", position.position().y())
                .put("z", position.position().z())
                .build();
    }

    // ------------------------------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------------------------------

    /** Decodes a machine, or a placeholder if its type is not registered or the envelope is unreadable. */
    static LoadedMachine decode(DataMap data, WorldPosition position, MachineServices services, MachineHost host) {
        Result<Key> typeKey = data.getString(TYPE).flatMap(Codecs::parseKey);
        if (typeKey.isErr()) {
            return new PlaceholderMachine(position, Key.key("machineengine", "unknown"), data, typeKey.error());
        }
        Result<Integer> format = data.getInt(FORMAT_FIELD);
        if (format.isErr() || format.value() > FORMAT) {
            return new PlaceholderMachine(position, typeKey.value(), data, SerializationError.at(FORMAT_FIELD,
                    format.isErr() ? format.error().message() : "data format " + format.value() + " is newer than " + FORMAT));
        }
        MachineType type = services.registries().registry(Registries.MACHINE_TYPE).get(typeKey.value()).orElse(null);
        if (type == null) {
            return new PlaceholderMachine(position, typeKey.value(), data,
                    new MachineError.MissingResource(Registries.MACHINE_TYPE.key(), typeKey.value()));
        }
        Direction facing = data.getString(FACING).flatMap(MachineCodec::parseDirection).orElse(Direction.NORTH);
        MachineInstanceImpl machine = new MachineInstanceImpl(services, host, type, position, facing);
        List<MachineError> problems = new ArrayList<>();
        StorageCodec<ResourceStack> stacks = services.resourceCodecs().stack();

        // Upgrades and runtime capacities first: they size storages, which must fit what was saved in them.
        data.getList(UPGRADES).ifOk(upgrades -> decodeUpgrades(machine, upgrades, services));
        data.getMap(CAPACITIES).ifOk(capacities -> capacities.entries().forEach((storage, value) -> {
            if (value instanceof DataValue.DataLong number && machine.storages.containsKey(storage)) machine.capacities.put(storage, number.value());
        }));
        data.getMap(STORAGES).ifOk(storages -> decodeStorages(machine, storages, stacks));
        data.getMap(DATA).ifOk(values -> decodeData(machine, values, problems));
        data.get(PROCESS).ifPresent(raw -> decodeProcess(raw, stacks).ifOk(machine.process::load).ifErr(error -> {
            machine.unknownProcess = raw;
            problems.add(error);
        }));
        data.getList(ESCROW).ifOk(escrow -> decodeEscrow(machine, escrow, stacks, problems));
        machine.sequence = data.getLong(SEQUENCE).orElse(0L);
        machine.active = data.getBool("active").toOptional().orElse(false);
        data.getList(DEDUPE).ifOk(dedupe -> decodeDedupe(machine, dedupe));
        data.getMap(SIDES).ifOk(sides -> sides.entries().forEach((port, raw) -> {
            if (!(raw instanceof DataList list)) return;
            Set<RelativeSide> set = EnumSet.noneOf(RelativeSide.class);
            for (DataValue value : list.values()) {
                if (value instanceof DataValue.DataString text) {
                    try {
                        set.add(RelativeSide.valueOf(text.value().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                        // a side name from a newer version: skip it
                    }
                }
            }
            machine.loadSides(port, set);
        }));

        Result<DataMap> scheduler = data.getMap(SCHEDULER);
        SchedulerState state = scheduler.flatMap(map -> map.getString("state")).flatMap(MachineCodec::parseState).orElse(SchedulerState.ACTIVE);
        long wakeIn = scheduler.flatMap(map -> map.getLong("wakeIn")).orElse(-1L);
        machine.restoreSchedule(state, wakeIn, data.getLong(SINCE_TICK).orElse(0L));
        if (type.catchUp().catchesUp()) {
            long unloaded = host.gameTime(position.world()) - data.getLong(SAVED_AT).orElse(Long.MAX_VALUE);
            if (unloaded > 0) machine.pendingCatchUp = Math.min(unloaded, type.catchUp().maxTicks());
        }
        if (!problems.isEmpty()) machine.context().error(problems.getFirst());
        return machine;
    }

    private static void decodeStorages(MachineInstanceImpl machine, DataMap storages, StorageCodec<ResourceStack> stacks) {
        storages.entries().forEach((id, raw) -> {
            MachineStorage storage = machine.storages.get(id);
            if (storage == null || !(raw instanceof DataList list)) {
                machine.unknownStorages.put(id, raw);
                return;
            }
            List<DataValue> overflow = new ArrayList<>();
            for (int i = 0; i < list.values().size(); i++) {
                DataValue slotData = list.values().get(i);
                if (i >= storage.slots().size()) {
                    overflow.add(slotData);
                    continue;
                }
                loadSlot(storage.slots().get(i), slotData, stacks);
            }
            if (!overflow.isEmpty()) machine.unknownStorages.put(id, DataValue.list(overflow));
        });
    }

    private static void loadSlot(MachineSlot slot, DataValue data, StorageCodec<ResourceStack> stacks) {
        if (!(data instanceof DataMap map) || map.size() == 0) return;
        if (map.has("locked")) {
            DataValue locked = map.get("locked").orElseThrow();
            // The resource may be registered again: try to rehydrate it.
            Result<ResourceStack> retry = locked instanceof DataMap lockedMap ? lockedMap.getMap("stack").flatMap(stacks::decode) : null;
            if (retry != null && retry.isOk() && retry.value().amount() <= slot.capacity()) {
                slot.load(retry.value().variant(), retry.value().amount());
            } else {
                slot.lock(locked);
            }
            return;
        }
        Result<ResourceStack> stack = map.getMap("stack").flatMap(stacks::decode);
        if (stack.isOk() && stack.value().amount() <= slot.capacity()) {
            ResourceStack value = stack.value();
            slot.load(value.isEmpty() ? null : value.variant(), value.amount());
        } else {
            // Unregistered resource type (or a slot that shrank): keep the data, lock the slot.
            slot.lock(map);
        }
    }

    private static void decodeData(MachineInstanceImpl machine, DataMap values, List<MachineError> problems) {
        Map<String, MachineDataKey<?>> declared = new LinkedHashMap<>();
        machine.data.keySet().forEach(key -> declared.put(key.id(), key));
        values.entries().forEach((id, raw) -> {
            MachineDataKey<?> key = declared.get(id);
            if (key == null) {
                machine.unknownData.put(id, raw);
                return;
            }
            Result<?> decoded = key.codec().decode(raw);
            if (decoded.isOk()) {
                machine.data.put(key, decoded.value());
            } else {
                // The machine runs with the default value, but the raw value is written back until the
                // behavior sets a new one, so nothing is lost if a fix ships later.
                machine.unknownData.put(id, raw);
                problems.add(decoded.error());
            }
        });
    }

    private static Result<ProcessState> decodeProcess(DataValue raw, StorageCodec<ResourceStack> stacks) {
        if (!(raw instanceof DataMap map)) return Result.err(SerializationError.at(PROCESS, "expected a map"));
        StorageCodec<List<ResourceStack>> list = Codecs.list(stacks);
        Result<Key> recipe = map.getString("recipe").flatMap(Codecs::parseKey);
        Result<ProcessStatus> status = map.getString("status").flatMap(text -> parseEnum(ProcessStatus.class, text));
        Result<Long> progress = map.getLong("progress");
        Result<Long> duration = map.getLong("duration");
        Result<List<ResourceStack>> inFlight = map.require("inFlight").flatMap(list::decode);
        Result<List<ResourceStack>> outputs = map.require("outputs").flatMap(list::decode);
        Result<List<ResourceStack>> costs = map.require("costs").flatMap(list::decode);
        Result<ProcessBinding> binding = map.getMap("binding").flatMap(MachineCodec::decodeBinding);
        for (Result<?> part : List.of(recipe, status, progress, duration, inFlight, outputs, costs, binding)) {
            if (part.isErr()) return Result.err(part.error());
        }
        return Result.ok(new ProcessState(recipe.value(), status.value(), progress.value(), duration.value(),
                inFlight.value(), outputs.value(), costs.value(), binding.value()));
    }

    private static Result<ProcessBinding> decodeBinding(DataMap map) {
        Result<List<String>> inputs = map.require("inputs").flatMap(Codecs.list(Codecs.STRING)::decode);
        Result<String> output = map.getString("output");
        if (inputs.isErr()) return Result.err(inputs.error());
        if (output.isErr()) return Result.err(output.error());
        // Saved before cost lists existed: a single storage name.
        List<String> costs = map.getString("costs").map(List::of).toOptional()
                .or(() -> map.getList("costs").flatMap(Codecs.list(Codecs.STRING)::decode).toOptional())
                .orElse(List.of());
        List<String> extraOutputs = map.getList("extraOutputs").flatMap(Codecs.list(Codecs.STRING)::decode).toOptional().orElse(List.of());
        return Result.ok(new ProcessBinding(inputs.value(), output.value(), costs, extraOutputs));
    }

    private static void decodeUpgrades(MachineInstanceImpl machine, DataList upgrades, MachineServices services) {
        Registry<UpgradeType> types = services.registries().registry(Registries.UPGRADE_TYPE);
        for (DataValue raw : upgrades.values()) {
            if (!(raw instanceof DataMap map)) {
                machine.unknownUpgrades.add(raw);
                continue;
            }
            UpgradeType type = map.getString(TYPE).flatMap(Codecs::parseKey).toOptional().flatMap(types::get).orElse(null);
            Result<Integer> count = map.getInt("count");
            if (type == null || count.isErr() || count.value() < 1) {
                machine.unknownUpgrades.add(raw);
            } else {
                machine.upgrades.merge(type, count.value(), Integer::sum);
            }
        }
        machine.refreshModifiers();
    }

    private static void decodeEscrow(MachineInstanceImpl machine, DataList escrow, StorageCodec<ResourceStack> stacks,
                                     List<MachineError> problems) {
        Map<Route, Escrow> pending = new LinkedHashMap<>();
        for (DataValue raw : escrow.values()) {
            Result<Escrow> decoded = raw instanceof DataMap map ? decodeEscrowEntry(map, stacks)
                    : Result.err(SerializationError.at(ESCROW, "expected a map"));
            if (decoded.isOk()) {
                pending.put(decoded.value().route(), decoded.value());
            } else {
                machine.unknownEscrow.add(raw);
                problems.add(decoded.error());
            }
        }
        machine.escrow.load(Map.copyOf(pending));
    }

    private static Result<Escrow> decodeEscrowEntry(DataMap map, StorageCodec<ResourceStack> stacks) {
        Result<TransferId> id = map.getMap("transfer").flatMap(MachineCodec::decodeTransfer);
        Result<WorldPosition> target = map.getMap("target").flatMap(MachineCodec::decodePosition);
        Result<Direction> side = map.getString("side").flatMap(MachineCodec::parseDirection);
        Result<String> storage = map.getString("storage");
        Result<List<ResourceStack>> contents = map.require("stacks").flatMap(Codecs.list(stacks)::decode);
        Result<Boolean> returning = map.getBool("returning");
        for (Result<?> part : List.of(id, target, side, storage, contents, returning)) {
            if (part.isErr()) return Result.err(part.error());
        }
        return Result.ok(new Escrow(id.value(), new Route(target.value(), side.value()), storage.value(), contents.value(),
                returning.value()));
    }

    private static void decodeDedupe(MachineInstanceImpl machine, DataList dedupe) {
        List<DedupeEntry> entries = new ArrayList<>();
        for (DataValue raw : dedupe.values()) {
            if (!(raw instanceof DataMap map)) continue;
            Result<TransferId> id = map.getMap("transfer").flatMap(MachineCodec::decodeTransfer);
            Result<List<Long>> accepted = map.require("accepted").flatMap(Codecs.list(Codecs.LONG)::decode);
            if (id.isOk() && accepted.isOk()) entries.add(new DedupeEntry(id.value(), accepted.value()));
        }
        machine.dedupe.load(List.copyOf(entries));
    }

    private static Result<TransferId> decodeTransfer(DataMap map) {
        return decodePosition(map).flatMap(source -> map.getLong("sequence").map(sequence -> new TransferId(source, sequence)));
    }

    static Result<WorldPosition> decodePosition(DataMap map) {
        Result<Key> world = map.getString("world").flatMap(Codecs::parseKey);
        Result<Integer> x = map.getInt("x");
        Result<Integer> y = map.getInt("y");
        Result<Integer> z = map.getInt("z");
        for (Result<?> part : List.of(world, x, y, z)) {
            if (part.isErr()) return Result.err(part.error());
        }
        return Result.ok(new WorldPosition(world.value(), new BlockPosition(x.value(), y.value(), z.value())));
    }

    static Result<Direction> parseDirection(String text) {
        return parseEnum(Direction.class, text);
    }

    private static Result<SchedulerState> parseState(String text) {
        return parseEnum(SchedulerState.class, text);
    }

    private static <E extends Enum<E>> Result<E> parseEnum(Class<E> type, String text) {
        try {
            return Result.ok(Enum.valueOf(type, text.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Result.err(SerializationError.at("", "unknown " + type.getSimpleName() + " '" + text + "'"));
        }
    }
}
