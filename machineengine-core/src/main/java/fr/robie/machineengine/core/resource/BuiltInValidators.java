package fr.robie.machineengine.core.resource;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.ValidationContext;
import fr.robie.machineengine.api.diagnostic.Validator;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;

import java.util.List;
import java.util.Set;

/** Validators for MachineEngine's own registries (§24). */
public final class BuiltInValidators {
    /** Namespaces only MachineEngine itself may register into. */
    public static final Set<String> RESERVED_NAMESPACES = Set.of(MachineEngineKeys.NAMESPACE, Key.MINECRAFT_NAMESPACE);

    private BuiltInValidators() {
    }

    /** Validators for static registries; they run at startup. */
    public static List<Validator> staticValidators() {
        return List.of(
                BuiltInValidators::reservedNamespaces,
                BuiltInValidators::resourceTypes,
                BuiltInValidators::converters,
                BuiltInValidators::codecs,
                MachineValidators::machineTypes,
                MachineValidators::upgradeTypes,
                MachineValidators::transportTypes);
    }

    /** Validators for one reloadable registry; they run whenever it is (re)built. */
    public static List<Validator> reloadValidators(RegistryType<?> type) {
        if (type.equals(Registries.RESOURCE_TAG)) return List.of(BuiltInValidators::tags);
        if (type.equals(Registries.RECIPE)) return List.of(MachineValidators::recipes);
        return List.of();
    }

    private static void reservedNamespaces(ValidationContext context) {
        for (RegistryType<?> type : context.registries().types()) {
            if (type.isReloadable()) continue;
            Registry<?> registry = context.registries().registry(type);
            for (Key key : registry.keys()) {
                AddonId owner = registry.owner(key).orElse(null);
                if (owner != null && !owner.equals(AddonId.MACHINE_ENGINE) && RESERVED_NAMESPACES.contains(key.namespace())) {
                    context.report(Diagnostic.error(DiagnosticCodes.RESERVED_NAMESPACE,
                                    "The '" + key.namespace() + "' namespace is reserved for MachineEngine; use your own namespace")
                            .source(owner).subject(key).with("registry", type.toString()).build());
                }
            }
        }
    }

    private static void resourceTypes(ValidationContext context) {
        Registry<ResourceType> types = context.registries().registry(Registries.RESOURCE_TYPE);
        var categories = context.registries().registry(Registries.RESOURCE_CATEGORY);
        Registry<ResourceComponentType<?>> components = context.registries().registry(Registries.RESOURCE_COMPONENT_TYPE);
        for (ResourceType type : types) {
            if (!categories.containsValue(type.category())) {
                context.report(unregistered(types, type, "category " + type.category().key().asString()));
            }
            for (ResourceComponentType<?> component : type.defaultComponents().types()) {
                if (!components.containsValue(component)) {
                    context.report(unregistered(types, type, "component type " + component.key().asString()));
                }
            }
        }
    }

    private static void converters(ValidationContext context) {
        Registry<ResourceConverter> converters = context.registries().registry(Registries.RESOURCE_CONVERTER);
        Registry<ResourceType> types = context.registries().registry(Registries.RESOURCE_TYPE);
        for (ResourceConverter converter : converters) {
            if (!types.containsValue(converter.from())) {
                context.report(unregistered(converters, converter, "input type " + converter.from().key().asString()));
            }
            if (!types.containsValue(converter.to())) {
                context.report(unregistered(converters, converter, "output type " + converter.to().key().asString()));
            }
            if (converter.from() == converter.to()) {
                context.report(Diagnostic.error(DiagnosticCodes.INVALID_DEFINITION, "A converter must change the resource type")
                        .source(converters.owner(converter.key()).orElse(null)).subject(converter.key()).build());
            }
        }
    }

    private static void codecs(ValidationContext context) {
        Registry<VersionedCodec<?>> codecs = context.registries().registry(Registries.STORAGE_CODEC);
        for (VersionedCodec<?> codec : codecs) {
            for (String problem : codec.definitionProblems()) {
                context.report(Diagnostic.error(DiagnosticCodes.INVALID_CODEC, "Codec " + codec.key().asString() + ": " + problem)
                        .source(codecs.owner(codec.key()).orElse(null)).subject(codec.key()).build());
            }
        }
    }

    private static void tags(ValidationContext context) {
        Registry<ResourceTagContribution> tags = context.registries().registry(Registries.RESOURCE_TAG);
        Registry<ResourceType> types = context.registries().registry(Registries.RESOURCE_TYPE);
        for (ResourceTagContribution contribution : tags) {
            for (ResourceType member : contribution.members()) {
                if (!types.containsValue(member)) {
                    context.report(unregistered(tags, contribution, "resource type " + member.key().asString()));
                }
            }
        }
    }

    static <T extends Keyed> Diagnostic unregistered(Registry<T> registry, T entry, String what) {
        return Diagnostic.error(DiagnosticCodes.UNREGISTERED_REFERENCE,
                        entry.key().asString() + " references " + what + ", which is not registered")
                .source(registry.owner(entry.key()).orElse(null)).subject(entry.key())
                .with("registry", registry.type().toString()).build();
    }
}
