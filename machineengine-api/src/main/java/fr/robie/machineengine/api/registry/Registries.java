package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.event.EventType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;

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

    /** Tag contents are reloadable; several addons may contribute to one tag. */
    public static final RegistryType<ResourceTagContribution> RESOURCE_TAG =
            RegistryType.createReloadable(MachineEngineKeys.key("resource_tag"), ResourceTagContribution.class);

    private Registries() {
    }
}
