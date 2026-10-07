package fr.robie.machineengine.api.multiblock;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.definition.DefinitionContext;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.registry.Registries;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Multiblocks in data-driven definitions (D33): the {@code multiblocks:} section of a definitions file,
 * and the behavior type {@code machineengine:multiblock_member}.
 *
 * <pre>{@code
 * multiblocks:
 *   mypack:altar:                       # a fixed shape, matched in 4 rotations
 *     pattern:
 *       layers:                         # bottom up; rows north to south; characters west to east
 *         - ["CCC", "C#C", "CCC"]
 *         - ["   ", " G ", "   "]
 *       key: {C: mypack:altar_casing, "#": mypack:altar_core, G: [minecraft:glass, minecraft:tinted_glass]}
 *       anchor: "#"                     # holds the storages; must be a member machine
 *       rotate: true                    # optional, default true
 *     capacities: {mana: 64000}         # optional: the anchor's storages while formed
 *   mypack:vat:                         # a hollow box
 *     box:
 *       casing: mypack:vat_casing       # edges and corners
 *       faces: [mypack:vat_casing, mypack:vat_valve, minecraft:glass]
 *       interior: [minecraft:air]       # optional, default any block
 *       min-size: 3                     # optional (3..), max-size 18, width 0 (any), open-top false
 *       controller: mypack:vat_valve    # optional: exactly one in the walls
 *     capacities: {fluid: {per-block: 16000}}   # a number, or per interior block
 *
 * machines:
 *   mypack:altar_core:
 *     behavior:
 *       type: machineengine:multiblock_member
 *       multiblocks: [mypack:altar]
 *       role: frame                     # frame (default), valve or port
 *       storages: [mana]                # valve and port: the anchor's storages it passes through
 *       exclusive: false                # valve: holds one of its storages at a time
 *       output-rate: 1000               # port: per tick pushed out in output mode
 * }</pre>
 */
public final class MultiblockDefinitions {
    private MultiblockDefinitions() {
    }

    /** One entry of the {@code multiblocks:} section. */
    public static @NotNull Result<MultiblockType> type(@NotNull Key key, @NotNull DataMap spec) {
        Result<MultiblockShape> shape;
        if (spec.get("pattern").orElse(null) instanceof DataMap pattern) {
            shape = pattern(pattern);
        } else if (spec.get("box").orElse(null) instanceof DataMap box) {
            shape = box(box);
        } else {
            return invalid("a multiblock needs a pattern: or a box: map");
        }
        if (shape.isErr()) return Result.err(shape.error());
        Result<Function<MultiblockStructure, Map<String, Long>>> capacities = capacities(spec.get("capacities").orElse(DataMap.EMPTY));
        if (capacities.isErr()) return Result.err(capacities.error());
        return Result.ok(new MultiblockType(key, shape.value(), capacities.value()));
    }

    /** The behavior type {@code machineengine:multiblock_member}'s parser. */
    public static @NotNull Result<MachineBehavior.Factory> member(@NotNull DataMap config, @NotNull DefinitionContext context) {
        Result<List<Key>> keys = config.require("multiblocks").flatMap(Codecs.list(Codecs.KEY)::decode);
        if (keys.isErr()) return invalid("multiblocks: a list of multiblock type keys is required");
        List<MultiblockType> types = new ArrayList<>();
        for (Key key : keys.value()) {
            MultiblockType type = context.registries().registry(Registries.MULTIBLOCK_TYPE).get(key).orElse(null);
            if (type == null) return invalid("multiblocks: " + key.asString() + " is not a registered multiblock type");
            types.add(type);
        }
        MultiblockMember.Builder builder = MultiblockMember.of(types);
        List<String> storages = config.get("storages").map(value -> Codecs.list(Codecs.STRING).decode(value).toOptional()
                .orElse(List.of())).orElse(List.of());
        String role = config.getString("role").toOptional().orElse("frame");
        return switch (role) {
            case "frame" -> Result.ok(builder.frame());
            case "valve" -> storages.isEmpty() ? invalid("a valve needs storages")
                    : Result.ok(builder.valve(storages, config.getBool("exclusive").toOptional().orElse(false)));
            case "port" -> {
                if (storages.size() != 1) yield invalid("a port needs exactly one storage");
                long rate = config.getLong("output-rate").toOptional().orElse(Long.MAX_VALUE);
                yield Result.ok(builder.port(storages.getFirst(), structure -> rate));
            }
            default -> invalid("role must be frame, valve or port");
        };
    }

    private static Result<MultiblockShape> pattern(DataMap spec) {
        Result<DataValue> layersSpec = spec.require("layers");
        if (layersSpec.isErr() || !(layersSpec.value() instanceof DataValue.DataList layerList)) return invalid("pattern.layers must be a list of layers");
        List<List<String>> layers = new ArrayList<>();
        for (DataValue layer : layerList.values()) {
            Result<List<String>> rows = Codecs.list(Codecs.STRING).decode(layer);
            if (rows.isErr()) return invalid("each pattern layer is a list of rows (strings)");
            layers.add(rows.value());
        }
        if (!(spec.get("key").orElse(null) instanceof DataMap keySpec)) return invalid("pattern.key must be a map of character: block(s)");
        Map<Character, Set<Key>> key = new LinkedHashMap<>();
        for (Map.Entry<String, DataValue> entry : keySpec.entries().entrySet()) {
            if (entry.getKey().length() != 1 || entry.getKey().equals(" ")) return invalid("pattern.key: '" + entry.getKey() + "' is not one character");
            Result<List<Key>> blocks = entry.getValue() instanceof DataValue.DataString single
                    ? Codecs.parseKey(single.value()).map(List::of) : Codecs.list(Codecs.KEY).decode(entry.getValue());
            if (blocks.isErr() || blocks.value().isEmpty()) return invalid("pattern.key." + entry.getKey() + " must be a block id or a list of them");
            key.put(entry.getKey().charAt(0), Set.copyOf(blocks.value()));
        }
        String anchor = spec.getString("anchor").toOptional().orElse("");
        if (anchor.length() != 1) return invalid("pattern.anchor must be one character");
        try {
            return Result.ok(new MultiblockShape.Pattern(layers, key, anchor.charAt(0), spec.getBool("rotate").toOptional().orElse(true)));
        } catch (IllegalArgumentException e) {
            return invalid("pattern: " + e.getMessage());
        }
    }

    private static Result<MultiblockShape> box(DataMap spec) {
        Result<Key> casing = spec.getString("casing").flatMap(Codecs::parseKey);
        if (casing.isErr()) return invalid("box.casing must be a block id");
        Result<List<Key>> faces = spec.require("faces").flatMap(Codecs.list(Codecs.KEY)::decode);
        if (faces.isErr()) return invalid("box.faces must be a list of block ids");
        Predicate<Key> interior = block -> true;
        if (spec.has("interior")) {
            Result<List<Key>> allowed = spec.require("interior").flatMap(Codecs.list(Codecs.KEY)::decode);
            if (allowed.isErr()) return invalid("box.interior must be a list of block ids");
            Set<Key> set = Set.copyOf(allowed.value());
            interior = set::contains;
        }
        Key controller = spec.has("controller") ? spec.getString("controller").flatMap(Codecs::parseKey).toOptional().orElse(null) : null;
        try {
            return Result.ok(new MultiblockShape.Box(casing.value(), Set.copyOf(faces.value()), interior,
                    spec.getInt("min-size").toOptional().orElse(MultiblockShape.Box.MIN_SIZE),
                    spec.getInt("max-size").toOptional().orElse(MultiblockShape.Box.MAX_SIZE),
                    spec.getInt("width").toOptional().orElse(0), spec.getBool("open-top").toOptional().orElse(false), controller));
        } catch (IllegalArgumentException e) {
            return invalid("box: " + e.getMessage());
        }
    }

    /** {@code storage: amount} or {@code storage: {per-block: amount}} (per interior block, or per block of a pattern). */
    private static Result<Function<MultiblockStructure, Map<String, Long>>> capacities(DataValue spec) {
        if (!(spec instanceof DataMap map)) return invalid("capacities must be a map of storage: amount");
        Map<String, Long> fixed = new HashMap<>();
        Map<String, Long> perBlock = new HashMap<>();
        for (Map.Entry<String, DataValue> entry : map.entries().entrySet()) {
            if (entry.getValue() instanceof DataValue.DataLong amount) {
                fixed.put(entry.getKey(), amount.value());
            } else if (entry.getValue() instanceof DataMap per && per.getLong("per-block").isOk()) {
                perBlock.put(entry.getKey(), per.getLong("per-block").value());
            } else {
                return invalid("capacities." + entry.getKey() + " must be a number or {per-block: number}");
            }
        }
        return Result.ok(structure -> {
            Map<String, Long> capacities = new HashMap<>(fixed);
            perBlock.forEach((storage, amount) -> capacities.put(storage, Math.multiplyExact(amount, (long) Math.max(1, structure.volume()))));
            return capacities;
        });
    }

    private static <T> Result<T> invalid(String message) {
        return Result.err(MachineError.of(DiagnosticCodes.INVALID_DEFINITION, message));
    }
}
