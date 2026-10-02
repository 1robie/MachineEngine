package fr.robie.machineengine.api.addon;

import fr.robie.machineengine.api.capability.CapabilityType;
import fr.robie.machineengine.api.definition.BehaviorType;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.Validator;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.network.NetworkType;
import fr.robie.machineengine.api.network.TransportType;
import fr.robie.machineengine.api.recipe.Recipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.upgrade.ModifierType;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.registry.ReloadableProvider;
import fr.robie.machineengine.api.registry.WritableRegistry;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Handed to an addon's registration callback. Everything registered through it is owned by
 * {@link #addon()}. Only valid while the callback runs; static registries freeze afterwards.
 */
@ApiStatus.NonExtendable
public interface RegistrationContext {

    @NotNull AddonId addon();

    /**
     * Read access to everything registered so far, including the content of addons this addon
     * depends on (their callbacks already ran).
     */
    @NotNull RegistryAccess registries();

    /**
     * The engine's event bus, already usable during registration (e.g. for
     * {@link fr.robie.machineengine.api.event.EngineEvents#REGISTRIES_FROZEN}). Subscribe with
     * {@link #addon()} as owner.
     */
    @NotNull fr.robie.machineengine.api.event.EventBus events();

    /**
     * @throws fr.robie.machineengine.api.registry.RegistrationException if {@code type} does not
     *         exist or is not a static registry
     */
    <T extends Keyed> @NotNull WritableRegistry<T> registry(@NotNull RegistryType<T> type);

    /**
     * Creates a new registry owned by this addon (static or reloadable, per {@code type}).
     *
     * @throws fr.robie.machineengine.api.registry.RegistrationException if it already exists
     */
    <T extends Keyed> void createRegistry(@NotNull RegistryType<T> type);

    /** Contributes entries to a reloadable registry; {@code provider} runs on every (re)load. */
    <T extends Keyed> void reloadable(@NotNull RegistryType<T> type, @NotNull ReloadableProvider<T> provider);

    /** Adds a validator that runs at startup validation and on every reload. */
    void validator(@NotNull Validator validator);

    /**
     * Adds a finding to the startup report without failing the registration, e.g. a data-driven
     * entry that was skipped. Attributed to {@link #addon()} if it has no source.
     */
    void report(@NotNull Diagnostic diagnostic);

    default @NotNull WritableRegistry<ResourceCategory> resourceCategories() {
        return registry(Registries.RESOURCE_CATEGORY);
    }

    default @NotNull WritableRegistry<ResourceType> resourceTypes() {
        return registry(Registries.RESOURCE_TYPE);
    }

    default @NotNull WritableRegistry<ResourceComponentType<?>> componentTypes() {
        return registry(Registries.RESOURCE_COMPONENT_TYPE);
    }

    default @NotNull WritableRegistry<ResourceConverter> converters() {
        return registry(Registries.RESOURCE_CONVERTER);
    }

    default void resourceTags(@NotNull ReloadableProvider<ResourceTagContribution> provider) {
        reloadable(Registries.RESOURCE_TAG, provider);
    }

    default @NotNull WritableRegistry<CapabilityType<?>> capabilityTypes() {
        return registry(Registries.CAPABILITY_TYPE);
    }

    default @NotNull WritableRegistry<MachineType> machineTypes() {
        return registry(Registries.MACHINE_TYPE);
    }

    default @NotNull WritableRegistry<RecipeType<?>> recipeTypes() {
        return registry(Registries.RECIPE_TYPE);
    }

    default @NotNull WritableRegistry<ModifierType> modifierTypes() {
        return registry(Registries.MODIFIER_TYPE);
    }

    default @NotNull WritableRegistry<UpgradeType> upgradeTypes() {
        return registry(Registries.UPGRADE_TYPE);
    }

    default @NotNull WritableRegistry<NetworkType> networkTypes() {
        return registry(Registries.NETWORK_TYPE);
    }

    default @NotNull WritableRegistry<TransportType> transportTypes() {
        return registry(Registries.TRANSPORT_TYPE);
    }

    default @NotNull WritableRegistry<BehaviorType> behaviorTypes() {
        return registry(Registries.BEHAVIOR_TYPE);
    }

    default void recipes(@NotNull ReloadableProvider<Recipe> provider) {
        reloadable(Registries.RECIPE, provider);
    }

    default void itemTags(@NotNull ReloadableProvider<fr.robie.machineengine.api.item.ItemTagContribution> provider) {
        reloadable(Registries.ITEM_TAG, provider);
    }
}
