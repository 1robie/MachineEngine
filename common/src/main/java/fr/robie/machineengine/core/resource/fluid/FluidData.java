package fr.robie.machineengine.core.resource.fluid;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.resource.ResourceType;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/** Reading fluid specs: numbers, colors and keys, with messages that name the field. */
final class FluidData {
    private FluidData() {
    }

    static Result<Double> number(DataMap map, String field, double fallback) {
        DataValue raw = map.get(field).orElse(null);
        if (raw == null) return Result.ok(fallback);
        return switch (raw) {
            case DataValue.DataLong value -> Result.ok((double) value.value());
            case DataValue.DataDouble value -> Result.ok(value.value());
            case DataValue.DataString value -> {
                try {
                    yield Result.ok(Double.parseDouble(value.value().trim()));
                } catch (NumberFormatException e) {
                    yield fail(field + " must be a number but is " + value.value());
                }
            }
            default -> fail(field + " must be a number but is a " + raw.kind());
        };
    }

    static Result<Integer> whole(DataMap map, String field, int fallback) {
        return number(map, field, fallback).flatMap(value -> value != Math.rint(value) ? fail(field + " must be a whole number but is " + value)
                : value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fail(field + " is too large: " + value)
                : Result.ok((int) Math.round(value)));
    }

    static Result<Boolean> bool(DataMap map, String field, boolean fallback) {
        if (!map.has(field)) return Result.ok(fallback);
        return map.getBool(field).mapError(error -> MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED, field + " must be true or false"));
    }

    /** A required {@code "#RRGGBB"} field. */
    static Result<Integer> color(DataMap map, String field) {
        if (!map.has(field)) return fail(field + " is missing");
        String text = map.getString(field).toOptional().orElse(null);
        Integer color = text == null ? null : ResourceType.parseColor(text.trim());
        return color == null ? fail(field + " must be \"#RRGGBB\" but is " + text) : Result.ok(color);
    }

    static Result<Integer> color(DataMap map, String field, int fallback) {
        return map.has(field) ? color(map, field) : Result.ok(fallback);
    }

    /** A namespaced key; a key without namespace is MachineEngine's ({@code recolor} = {@code machineengine:recolor}). */
    static Result<Key> typeKey(@NotNull String text) {
        return Codecs.parseKey(text.contains(":") ? text : MachineEngineKeys.key(text).asString());
    }

    /** A required namespaced key field (a key without namespace is Minecraft's). */
    static Result<Key> key(DataMap map, String field) {
        if (!map.has(field)) return fail(field + " is missing");
        return map.getString(field).flatMap(Codecs::parseKey)
                .mapError(error -> MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED, field + " must be a key: " + error.message()));
    }

    static <T> Result<T> fail(String message) {
        return Result.err(MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED, message));
    }
}
