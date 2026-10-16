package fr.robie.machineengine.core.resource.fluid;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.fluid.FluidEffectType;
import fr.robie.machineengine.api.resource.fluid.FluidItems;
import fr.robie.machineengine.api.resource.fluid.FluidMixing;
import fr.robie.machineengine.api.resource.fluid.FluidPhysics;
import fr.robie.machineengine.api.resource.fluid.FluidReaction;
import fr.robie.machineengine.api.resource.fluid.FluidReactionType;
import fr.robie.machineengine.api.resource.fluid.FluidTexture;
import fr.robie.machineengine.api.resource.fluid.FluidTextureType;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.fluid.Spread;
import fr.robie.machineengine.api.resource.fluid.Viscosity;
import fr.robie.machineengine.api.resource.fluid.WorldMode;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Reads a fluid's spec (docs/FLUID_TYPES_PLAN.md §3) onto a {@link FluidType.Builder}: the
 * {@code fluid:} section of a definition, or a fluid's section in {@code fluids.yml}.
 *
 * <pre>{@code
 * world: placeable                 # none | placeable          (definitions only)
 * bucket: auto                     # auto | none | <item key>  (definitions only)
 * look:
 *   still: { source: recolor, pattern: lava, from: "#0A080E", to: "#4E3E60" }  # or a file key
 *   flow: mymod:block/oil_flow
 *   texture: ...                   # still and flow at once
 *   tint: "#FFFFFF"
 *   opacity: 0.96
 *   glow: 0.0
 *   fog: "#0B0806"
 *   frame-time: 2
 * physics:
 *   viscosity: { speed: 0.5, gravity: 1.6, jump: 0.5, swim: 0, damping: 0.35, entity-drag: 0.3 }
 *   spread: { ticks: 15, range: 5 }
 *   infinite-source: true
 *   mixed-source: none             # none | water
 * mixing:                          # first match decides; else both stop
 *   - { with: water, result: stop }                    # water | any | "#<tag>" | <fluid key>
 *   - { with: mymod:fluid/acid, result: block, block: minecraft:calcite }
 * effects:
 *   - { type: potion, effect: minecraft:slowness, amplifier: 0 }
 * }</pre>
 *
 * A key without namespace in {@code source}, {@code result} and {@code type} is MachineEngine's.
 * Unknown fields are refused, so typos show.
 */
public final class FluidSpecParser {
    private static final Set<String> TOP = Set.of("world", "bucket", "look", "physics", "mixing", "effects");
    private static final Set<String> LOOK = Set.of("still", "flow", "texture", "tint", "opacity", "glow", "fog", "frame-time");
    private static final Set<String> PHYSICS = Set.of("viscosity", "spread", "infinite-source", "mixed-source");
    private static final Set<String> VISCOSITY = Set.of("speed", "gravity", "jump", "swim", "damping", "entity-drag");
    private static final Set<String> SPREAD = Set.of("ticks", "range");

    private FluidSpecParser() {
    }

    /**
     * Applies {@code spec} onto {@code builder}.
     *
     * @param base     the fluid being overridden ({@code fluids.yml}): its values are kept where the spec
     *                 says nothing; {@code null} for a definition (water's values are the defaults)
     * @return the builder, or what is wrong with the spec
     */
    public static @NotNull Result<FluidType.Builder> apply(@NotNull FluidType.Builder builder, @NotNull DataMap spec,
                                                           @NotNull RegistryAccess registries, @Nullable FluidType base) {
        try {
            String problem = unknown(spec, TOP, "");
            if (problem != null) return FluidData.fail(problem);
            boolean override = base != null;
            Result<Boolean> result = world(builder, spec, override)
                    .flatMap(ok -> bucket(builder, spec, override))
                    .flatMap(ok -> look(builder, spec, registries))
                    .flatMap(ok -> physics(builder, spec, base))
                    .flatMap(ok -> mixing(builder, spec, registries))
                    .flatMap(ok -> effects(builder, spec, registries));
            return result.map(ok -> builder);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return FluidData.fail(e.getMessage());
        }
    }

    private static Result<Boolean> world(FluidType.Builder builder, DataMap spec, boolean override) {
        if (!spec.has("world")) return done();
        if (override) return FluidData.fail("world can't be changed in fluids.yml: set it in the fluid's definition (restart)");
        String text = spec.getString("world").toOptional().orElse("");
        return switch (text.trim().toLowerCase()) {
            case "none" -> {
                builder.world(WorldMode.NONE);
                yield done();
            }
            case "placeable" -> {
                builder.placeable();
                yield done();
            }
            default -> FluidData.fail("world must be none or placeable but is " + text);
        };
    }

    private static Result<Boolean> bucket(FluidType.Builder builder, DataMap spec, boolean override) {
        if (!spec.has("bucket")) return done();
        if (override) return FluidData.fail("bucket can't be changed in fluids.yml: set it in the fluid's definition (restart)");
        String text = spec.getString("bucket").toOptional().orElse("").trim();
        switch (text.toLowerCase()) {
            case "auto" -> builder.bucket(FluidItems.Bucket.Auto.INSTANCE);
            case "none" -> builder.bucket(FluidItems.Bucket.None.INSTANCE);
            default -> {
                Result<Key> item = Codecs.parseKey(text);
                if (item.isErr() || !text.contains(":")) return FluidData.fail("bucket must be auto, none or an item key but is " + text);
                builder.bucket(new FluidItems.Bucket.Item(item.value()));
            }
        }
        return done();
    }

    private static Result<Boolean> look(FluidType.Builder builder, DataMap spec, RegistryAccess registries) {
        if (!spec.has("look")) return done();
        Result<DataMap> look = section(spec, "look", LOOK, "look");
        if (look.isErr()) return prefix(look, "");
        DataMap map = look.value();
        for (String field : new String[]{"texture", "still", "flow"}) {
            if (!map.has(field)) continue;
            Result<FluidTexture> texture = texture(map.get(field).orElseThrow(), "look." + field, registries);
            if (texture.isErr()) return prefix(texture, "");
            switch (field) {
                case "texture" -> builder.texture(texture.value());
                case "still" -> builder.still(texture.value());
                default -> builder.flow(texture.value());
            }
        }
        if (map.has("tint")) {
            Result<Integer> tint = FluidData.color(map, "tint");
            if (tint.isErr()) return prefix(tint, "look.");
            builder.tint(tint.value());
        }
        if (map.has("fog")) {
            Result<Integer> fog = FluidData.color(map, "fog");
            if (fog.isErr()) return prefix(fog, "look.");
            builder.fog(fog.value());
        }
        if (map.has("opacity")) {
            Result<Double> opacity = FluidData.number(map, "opacity", 0);
            if (opacity.isErr()) return prefix(opacity, "look.");
            builder.opacity(opacity.value());
        }
        if (map.has("glow")) {
            Result<Double> glow = FluidData.number(map, "glow", 0);
            if (glow.isErr()) return prefix(glow, "look.");
            builder.glow(glow.value());
        }
        if (map.has("frame-time")) {
            Result<Integer> frameTime = FluidData.whole(map, "frame-time", 2);
            if (frameTime.isErr()) return prefix(frameTime, "look.");
            builder.frameTime(frameTime.value());
        }
        return done();
    }

    private static Result<FluidTexture> texture(DataValue raw, String where, RegistryAccess registries) {
        DataMap config;
        Key source;
        if (raw instanceof DataValue.DataString text) {
            // A key alone is a file: look.still: mymod:block/oil_still
            Result<Key> file = Codecs.parseKey(text.value().trim());
            if (file.isErr() || !text.value().contains(":")) return FluidData.fail(where + " must be a texture key or a map with source");
            return Result.ok(FluidTextures.file(file.value()));
        }
        if (!(raw instanceof DataMap map)) return FluidData.fail(where + " must be a texture key or a map with source");
        if (!map.has("source")) return FluidData.fail(where + ".source is missing");
        Result<Key> key = map.getString("source").flatMap(FluidData::typeKey);
        if (key.isErr()) return FluidData.fail(where + ".source is not a key");
        source = key.value();
        config = map.without("source");
        FluidTextureType type = registries.registry(Registries.FLUID_TEXTURE_TYPE).get(source).orElse(null);
        if (type == null) return FluidData.fail(where + ".source " + source.asString() + " is not a registered fluid texture type");
        return type.parse(config).mapError(error -> MachineError.of(error.code(), where + ": " + error.message()));
    }

    private static Result<Boolean> physics(FluidType.Builder builder, DataMap spec, @Nullable FluidType base) {
        if (!spec.has("physics")) return done();
        Result<DataMap> section = section(spec, "physics", PHYSICS, "physics");
        if (section.isErr()) return prefix(section, "");
        DataMap map = section.value();
        FluidPhysics current = base != null ? base.physics() : FluidPhysics.WATER;
        if (map.has("viscosity")) {
            Result<DataMap> viscosity = section(map, "viscosity", VISCOSITY, "physics.viscosity");
            if (viscosity.isErr()) return prefix(viscosity, "");
            Viscosity was = current.viscosity();
            DataMap v = viscosity.value();
            Result<Double> speed = FluidData.number(v, "speed", was.speed());
            Result<Double> gravity = FluidData.number(v, "gravity", was.gravity());
            Result<Double> jump = FluidData.number(v, "jump", was.jump());
            Result<Double> swim = FluidData.number(v, "swim", was.swim());
            Result<Double> damping = FluidData.number(v, "damping", was.damping());
            Result<Double> drag = FluidData.number(v, "entity-drag", was.entityDrag());
            for (Result<Double> value : java.util.List.of(speed, gravity, jump, swim, damping, drag)) {
                if (value.isErr()) return prefix(value, "physics.viscosity.");
            }
            builder.viscosity(new Viscosity(speed.value(), gravity.value(), jump.value(), swim.value(), damping.value(), drag.value()));
        }
        if (map.has("spread")) {
            Result<DataMap> spread = section(map, "spread", SPREAD, "physics.spread");
            if (spread.isErr()) return prefix(spread, "");
            Result<Integer> ticks = FluidData.whole(spread.value(), "ticks", current.spread().ticks());
            if (ticks.isErr()) return prefix(ticks, "physics.spread.");
            Result<Integer> range = FluidData.whole(spread.value(), "range", current.spread().range());
            if (range.isErr()) return prefix(range, "physics.spread.");
            builder.spread(new Spread(ticks.value(), range.value()));
        }
        if (map.has("infinite-source")) {
            Result<Boolean> infinite = FluidData.bool(map, "infinite-source", true);
            if (infinite.isErr()) return prefix(infinite, "physics.");
            builder.infiniteSource(infinite.value());
        }
        if (map.has("mixed-source")) {
            String text = map.getString("mixed-source").toOptional().orElse("");
            switch (text.trim().toLowerCase()) {
                case "none" -> builder.mixedSource(FluidPhysics.MixedSource.NONE);
                case "water" -> builder.mixedSource(FluidPhysics.MixedSource.WATER);
                default -> {
                    return FluidData.fail("physics.mixed-source must be none or water but is " + text);
                }
            }
        }
        return done();
    }

    private static Result<Boolean> mixing(FluidType.Builder builder, DataMap spec, RegistryAccess registries) {
        if (!spec.has("mixing")) return done();
        if (!(spec.get("mixing").orElseThrow() instanceof DataValue.DataList list)) return FluidData.fail("mixing must be a list");
        java.util.List<FluidMixing.Rule> rules = new java.util.ArrayList<>();
        int index = 0;
        for (DataValue raw : list.values()) {
            String where = "mixing[" + index++ + "]";
            if (!(raw instanceof DataMap rule)) return FluidData.fail(where + " must be a map");
            if (!rule.has("with")) return FluidData.fail(where + ".with is missing");
            Result<FluidMixing.Selector> with = selector(rule.getString("with").toOptional().orElse(""));
            if (with.isErr()) return prefix(with, where + ".");
            Result<Key> result = FluidData.typeKey(rule.getString("result").toOptional().orElse("stop"));
            if (result.isErr()) return FluidData.fail(where + ".result is not a key");
            FluidReactionType type = registries.registry(Registries.FLUID_REACTION_TYPE).get(result.value()).orElse(null);
            if (type == null) return FluidData.fail(where + ".result " + result.value().asString() + " is not a registered fluid reaction type");
            Result<FluidReaction> reaction = type.parse(rule.without("with").without("result"));
            if (reaction.isErr()) return prefix(reaction, where + ": ");
            rules.add(new FluidMixing.Rule(with.value(), reaction.value()));
        }
        builder.mixing(new FluidMixing(rules));
        return done();
    }

    static Result<FluidMixing.Selector> selector(String text) {
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase("any")) return Result.ok(new FluidMixing.Selector.Any());
        if (trimmed.equalsIgnoreCase("water")) return Result.ok(new FluidMixing.Selector.Fluid(Fluids.WATER_KEY));
        if (trimmed.equalsIgnoreCase("lava")) return Result.ok(new FluidMixing.Selector.Fluid(Fluids.LAVA_KEY));
        if (trimmed.startsWith("#")) {
            return Codecs.parseKey(trimmed.substring(1)).map(tag -> (FluidMixing.Selector) new FluidMixing.Selector.Tag(tag))
                    .mapError(error -> MachineError.of(error.code(), "with must be water, any, \"#<tag>\" or a fluid key but is " + text));
        }
        if (!trimmed.contains(":")) return FluidData.fail("with must be water, any, \"#<tag>\" or a fluid key but is " + text);
        return Codecs.parseKey(trimmed).map(key -> (FluidMixing.Selector) new FluidMixing.Selector.Fluid(key));
    }

    private static Result<Boolean> effects(FluidType.Builder builder, DataMap spec, RegistryAccess registries) {
        if (!spec.has("effects")) return done();
        if (!(spec.get("effects").orElseThrow() instanceof DataValue.DataList list)) return FluidData.fail("effects must be a list");
        builder.clearEffects();
        int index = 0;
        for (DataValue raw : list.values()) {
            String where = "effects[" + index++ + "]";
            if (!(raw instanceof DataMap effect)) return FluidData.fail(where + " must be a map");
            if (!effect.has("type")) return FluidData.fail(where + ".type is missing");
            Result<Key> key = effect.getString("type").flatMap(FluidData::typeKey);
            if (key.isErr()) return FluidData.fail(where + ".type is not a key");
            FluidEffectType type = registries.registry(Registries.FLUID_EFFECT_TYPE).get(key.value()).orElse(null);
            if (type == null) return FluidData.fail(where + ".type " + key.value().asString() + " is not a registered fluid effect type");
            var parsed = type.parse(effect.without("type"));
            if (parsed.isErr()) return prefix(parsed, where + ": ");
            builder.effect(parsed.value());
        }
        return done();
    }

    private static Result<DataMap> section(DataMap parent, String field, Set<String> allowed, String path) {
        if (!(parent.get(field).orElseThrow() instanceof DataMap map)) return FluidData.fail(path + " must be a map");
        String problem = unknown(map, allowed, path + ".");
        return problem != null ? FluidData.fail(problem) : Result.ok(map);
    }

    private static @Nullable String unknown(DataMap map, Set<String> allowed, String prefix) {
        for (String key : map.keys()) {
            if (!allowed.contains(key)) {
                return "unknown field " + prefix + key + " (expected one of: " + String.join(", ", new java.util.TreeSet<>(allowed)) + ")";
            }
        }
        return null;
    }

    private static <T, R> Result<R> prefix(Result<T> result, String prefix) {
        return Result.err(MachineError.of(result.error().code(), prefix + result.error().message()));
    }

    private static Result<Boolean> done() {
        return Result.ok(Boolean.TRUE);
    }
}
