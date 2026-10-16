package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.resource.fluid.FluidEffectType;
import fr.robie.machineengine.api.resource.fluid.FluidReactionType;
import fr.robie.machineengine.api.resource.fluid.FluidTextureType;
import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.capability.CapabilityType;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.definition.BehaviorType;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.item.ItemTagContribution;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.multiblock.MultiblockType;
import fr.robie.machineengine.api.network.NetworkType;
import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerDataStorage;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.api.recipe.Recipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.upgrade.UpgradeType;

/** Registries MachineEngine creates itself (ARCHITECTURE.md §7.2). */
public final class Registries {
    public static final RegistryType<ResourceCategory> RESOURCE_CATEGORY =
            RegistryType.createStatic(MachineEngineKeys.key("resource_category"), ResourceCategory.class);
    public static final RegistryType<ResourceType> RESOURCE_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("resource_type"), ResourceType.class);
    public static final RegistryType<ResourceComponentType<?>> RESOURCE_COMPONENT_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("resource_component_type"), ResourceComponentType.class);
    public static final RegistryType<ResourceConverter> RESOURCE_CONVERTER =
            RegistryType.createStatic(MachineEngineKeys.key("resource_converter"), ResourceConverter.class);
    public static final RegistryType<VersionedCodec<?>> STORAGE_CODEC =
            RegistryType.createStatic(MachineEngineKeys.key("storage_codec"), VersionedCodec.class);
    public static final RegistryType<EventType<?>> EVENT_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("event_type"), EventType.class);

    public static final RegistryType<CapabilityType<?>> CAPABILITY_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("capability_type"), CapabilityType.class);
    public static final RegistryType<MachineType> MACHINE_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("machine_type"), MachineType.class);
    public static final RegistryType<RecipeType<?>> RECIPE_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("recipe_type"), RecipeType.class);
    public static final RegistryType<ModifierType> MODIFIER_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("modifier_type"), ModifierType.class);
    public static final RegistryType<UpgradeType> UPGRADE_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("upgrade_type"), UpgradeType.class);

    public static final RegistryType<NetworkType> NETWORK_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("network_type"), NetworkType.class);
    public static final RegistryType<TransportType> TRANSPORT_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("transport_type"), TransportType.class);

    /** Kinds of multiblock (D33): their shapes, named by multiblock members. */
    public static final RegistryType<MultiblockType> MULTIBLOCK_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("multiblock_type"), MultiblockType.class);

    /** Behaviors data-driven machine definitions can use by key (§29). */
    public static final RegistryType<BehaviorType> BEHAVIOR_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("behavior_type"), BehaviorType.class);

    /** Kinds of effect a placed fluid has on entities inside it (D47), usable by key in definitions. */
    public static final RegistryType<FluidEffectType> FLUID_EFFECT_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("fluid_effect_type"), FluidEffectType.class);

    /** Kinds of reaction when a placed fluid meets another (D47), usable by key in definitions. */
    public static final RegistryType<FluidReactionType> FLUID_REACTION_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("fluid_reaction_type"), FluidReactionType.class);

    /** Kinds of fluid texture source (D47), usable by key in definitions. */
    public static final RegistryType<FluidTextureType> FLUID_TEXTURE_TYPE =
            RegistryType.createStatic(MachineEngineKeys.key("fluid_texture_type"), FluidTextureType.class);

    /** Values kept per player (D37). */
    public static final RegistryType<PlayerDataKey<?>> PLAYER_DATA =
            RegistryType.createStatic(MachineEngineKeys.key("player_data"), PlayerDataKey.class);

    /** Per-player values players change themselves (D37); each one's {@link PlayerSetting#data()} is a player data key too. */
    public static final RegistryType<PlayerSetting<?>> PLAYER_SETTING =
            RegistryType.createStatic(MachineEngineKeys.key("player_setting"), PlayerSetting.class);

    /** Where player data can be saved, chosen with {@code players.storage} (D37). */
    public static final RegistryType<PlayerDataStorage.Type> PLAYER_STORAGE =
            RegistryType.createStatic(MachineEngineKeys.key("player_storage"), PlayerDataStorage.Type.class);

    /** Recipes are reloadable (D5). */
    public static final RegistryType<Recipe> RECIPE =
            RegistryType.createReloadable(MachineEngineKeys.key("recipe"), Recipe.class);

    /** Tag contents are reloadable; several addons may contribute to one tag. */
    public static final RegistryType<ResourceTagContribution> RESOURCE_TAG =
            RegistryType.createReloadable(MachineEngineKeys.key("resource_tag"), ResourceTagContribution.class);

    /** Item tags (by item id); reloaded together with resource tags. */
    public static final RegistryType<ItemTagContribution> ITEM_TAG =
            RegistryType.createReloadable(MachineEngineKeys.key("item_tag"), ItemTagContribution.class);

    private Registries() {
    }
}
