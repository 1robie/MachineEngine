package fr.robie.machineengine.api.addon;

import fr.robie.machineengine.api.diagnostic.Validator;
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
}
