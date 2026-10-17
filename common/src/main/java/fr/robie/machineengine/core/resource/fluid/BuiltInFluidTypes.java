package fr.robie.machineengine.core.resource.fluid;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.paper.WorldFluids;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.fluid.FluidEffect;
import fr.robie.machineengine.api.resource.fluid.FluidEffectType;
import fr.robie.machineengine.api.resource.fluid.FluidReaction;
import fr.robie.machineengine.api.resource.fluid.FluidReactionType;
import fr.robie.machineengine.api.resource.fluid.FluidTextureType;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.core.registry.RegistrySet;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;

/**
 * The effect, reaction and texture types MachineEngine provides (docs/FLUID_TYPES_PLAN.md §2.3).
 * Their parsers only read the spec; game objects (potion effects, blocks) are looked up when the
 * effect or reaction runs, so definitions load without a server.
 */
public final class BuiltInFluidTypes {
    // ---- effects (called every 10 ticks while an entity is inside)

    /** {@code effect: minecraft:slowness, amplifier: 0}: keeps the effect on while inside. */
    public static final FluidEffectType POTION = FluidEffectType.of(MachineEngineKeys.key("potion"), config ->
            FluidData.key(config, "effect").flatMap(effect -> FluidData.whole(config, "amplifier", 0).map(amplifier -> (FluidEffect)
                    (entity, fluid, ticks) -> {
                        if (!(entity instanceof LivingEntity living)) return;
                        @SuppressWarnings("deprecation")
                        PotionEffectType type = PotionEffectType.getByKey(new NamespacedKey(effect.namespace(), effect.value()));
                        if (type != null) living.addPotionEffect(new PotionEffect(type, 40, amplifier, true, false));
                    })));

    /** {@code amount: 2, every: 20}: damage every {@code every} ticks inside. */
    public static final FluidEffectType DAMAGE = FluidEffectType.of(MachineEngineKeys.key("damage"), config ->
            FluidData.number(config, "amount", 1).flatMap(amount -> FluidData.whole(config, "every", 20).flatMap(every -> {
                if (!(amount > 0)) return FluidData.fail("amount must be above 0 but is " + amount);
                if (every < 1) return FluidData.fail("every must be 1 or more but is " + every);
                // Effects run every 10 ticks: damage when a multiple of "every" was passed since the last run.
                return Result.ok((FluidEffect) (entity, fluid, ticks) -> {
                    if (entity instanceof LivingEntity living && ticks / every != Math.max(-1, ticks - 10) / every) living.damage(amount);
                });
            })));

    /** {@code ticks: 60}: sets entities on fire. */
    public static final FluidEffectType FIRE = FluidEffectType.of(MachineEngineKeys.key("fire"), config ->
            FluidData.whole(config, "ticks", 60).map(fire -> (FluidEffect)
                    (entity, fluid, ticks) -> entity.setFireTicks(Math.max(entity.getFireTicks(), fire))));

    /** {@code ticks: 140}: freezes entities, like powder snow. */
    public static final FluidEffectType FREEZE = FluidEffectType.of(MachineEngineKeys.key("freeze"), config ->
            FluidData.whole(config, "ticks", 140).map(freeze -> (FluidEffect)
                    (entity, fluid, ticks) -> entity.setFreezeTicks(Math.max(entity.getFreezeTicks(), freeze))));

    // ---- reactions (when a fluid flows into another)

    /** Both stop where they meet. */
    public static final FluidReactionType STOP = FluidReactionType.of(MachineEngineKeys.key("stop"),
            config -> Result.ok(FluidReaction.STOP));

    /** The incoming fluid replaces the one there. */
    public static final FluidReactionType FLOW = FluidReactionType.of(MachineEngineKeys.key("flow"),
            config -> Result.ok((at, incoming, present) -> FluidReaction.Outcome.FLOW));

    /** {@code block: minecraft:obsidian}: the block becomes this block (like lava meeting water). */
    public static final FluidReactionType BLOCK = FluidReactionType.of(MachineEngineKeys.key("block"), config ->
            FluidData.key(config, "block").map(block -> (FluidReaction) (at, incoming, present) -> {
                Material material = Material.matchMaterial(block.asString());
                if (material != null && material.isBlock()) at.setType(material);
                return FluidReaction.Outcome.STOP;
            }));

    /** {@code fluid: mymod:fluid/slurry}: the block becomes a source of this placeable fluid. */
    public static final FluidReactionType FLUID = FluidReactionType.of(MachineEngineKeys.key("fluid"), config ->
            FluidData.key(config, "fluid").map(target -> (FluidReaction) (at, incoming, present) -> {
                ResourceType type = Fluids.fromWorld(target);
                if (type instanceof FluidType fluid && fluid.placeable()) {
                    WorldFluids.take(at);
                    WorldFluids.place(at, fluid);
                }
                return FluidReaction.Outcome.STOP;
            }));

    // ---- textures

    /** {@code texture: mymod:block/oil_still}: a file from the CraftEngine resource packs. */
    public static final FluidTextureType FILE = FluidTextureType.of(MachineEngineKeys.key("file"), config ->
            FluidData.key(config, "texture").map(FluidTextures::file));

    /** {@code texture: block/lava_still}: a vanilla texture (default: water's). */
    public static final FluidTextureType VANILLA = FluidTextureType.of(MachineEngineKeys.key("vanilla"), config ->
            Result.ok(FluidTextures.vanilla(config.getString("texture").toOptional().orElse(null))));

    /** {@code pattern: lava, from: "#0A080E", to: "#4E3E60"}: a vanilla pattern recolored by brightness. */
    public static final FluidTextureType RECOLOR = FluidTextureType.of(MachineEngineKeys.key("recolor"), config ->
            FluidData.color(config, "from", 0x000000).flatMap(from -> FluidData.color(config, "to", 0xFFFFFF).map(to ->
                    FluidTextures.recolor(config.getString("pattern").toOptional().orElse("water"), from, to))));

    /** {@code color: "#C8BA8C", sparkle: "#FFFFFF"}: drawn, twinkling crystals. */
    public static final FluidTextureType CRYSTALS = FluidTextureType.of(MachineEngineKeys.key("crystals"), config ->
            FluidData.color(config, "color", 0xC8BA8C).flatMap(base -> FluidData.color(config, "sparkle", 0xFFFFFF).map(sparkle ->
                    FluidTextures.crystals(base, sparkle))));

    /** {@code color: "#B4B428", bubble: "#FAFAAA"}: drawn, rising bubbles. */
    public static final FluidTextureType BUBBLES = FluidTextureType.of(MachineEngineKeys.key("bubbles"), config ->
            FluidData.color(config, "color", 0xB4B428).flatMap(base -> FluidData.color(config, "bubble", 0xFAFAAA).map(bubble ->
                    FluidTextures.bubbles(base, bubble))));

    public static final List<FluidEffectType> EFFECTS = List.of(POTION, DAMAGE, FIRE, FREEZE);
    public static final List<FluidReactionType> REACTIONS = List.of(STOP, FLOW, BLOCK, FLUID);
    public static final List<FluidTextureType> TEXTURES = List.of(FILE, VANILLA, RECOLOR, CRYSTALS, BUBBLES);

    private BuiltInFluidTypes() {
    }

    /** Registers them as MachineEngine's (while the registries are created). */
    public static void register(RegistrySet registries) {
        EFFECTS.forEach(type -> registries.staticRegistry(Registries.FLUID_EFFECT_TYPE).register(AddonId.MACHINE_ENGINE, type));
        REACTIONS.forEach(type -> registries.staticRegistry(Registries.FLUID_REACTION_TYPE).register(AddonId.MACHINE_ENGINE, type));
        TEXTURES.forEach(type -> registries.staticRegistry(Registries.FLUID_TEXTURE_TYPE).register(AddonId.MACHINE_ENGINE, type));
    }
}
