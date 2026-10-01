package fr.robie.machineengine.core.resource;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.StorageCodec;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.MachineError.SerializationError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceComponents;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Codecs for resource variants and stacks, resolved against the registries:
 * {@code {type: "mymod:steam", components: {"mymod:temperature": 400}, amount: 500}}.
 *
 * <p>Components whose type is not registered are kept as raw data (D3). A resource type that is not
 * registered is a {@link MachineError.MissingResource} error; callers keep such data opaque.</p>
 */
public final class ResourceCodecs {
    private final StorageCodec<ResourceVariant> variant;
    private final StorageCodec<ResourceStack> stack;

    public ResourceCodecs(@NotNull RegistryAccess registries) {
        Registry<ResourceType> types = registries.registry(Registries.RESOURCE_TYPE);
        Registry<ResourceComponentType<?>> components = registries.registry(Registries.RESOURCE_COMPONENT_TYPE);
        this.variant = StorageCodec.of(ResourceCodecs::encodeVariant, data -> data instanceof DataMap map
                ? decodeVariant(map, types, components)
                : Result.err(SerializationError.at("", "expected a resource (map) but found " + data.kind())));
        this.stack = StorageCodec.of(
                stack -> encodeVariant(stack.variant()).with("amount", DataValue.of(stack.amount())),
                data -> {
                    if (!(data instanceof DataMap map)) {
                        return Result.err(SerializationError.at("", "expected a resource stack (map) but found " + data.kind()));
                    }
                    Result<Long> amount = map.getLong("amount");
                    if (amount.isErr()) return Result.err(amount.error());
                    if (amount.value() < 0) return Result.err(SerializationError.at("amount", "amount must not be negative"));
                    return decodeVariant(map, types, components).map(variant -> new ResourceStack(variant, amount.value()));
                });
    }

    public @NotNull StorageCodec<ResourceVariant> variant() {
        return this.variant;
    }

    public @NotNull StorageCodec<ResourceStack> stack() {
        return this.stack;
    }

    private static DataMap encodeVariant(ResourceVariant variant) {
        DataMap.Builder builder = DataMap.builder().put("type", variant.type().key().asString());
        ResourceComponents components = variant.components();
        if (!components.isEmpty()) {
            DataMap.Builder encoded = DataMap.builder();
            for (ResourceComponentType<?> type : components.types()) {
                encoded.put(type.key().asString(), encodeComponent(type, components));
            }
            components.unknown().forEach((key, raw) -> encoded.put(key.asString(), raw));
            builder.put("components", encoded.build());
        }
        return builder.build();
    }

    private static <T> DataValue encodeComponent(ResourceComponentType<T> type, ResourceComponents components) {
        return type.codec().encode(type.cast(components.get(type)));
    }

    private static Result<ResourceVariant> decodeVariant(DataMap map, Registry<ResourceType> types,
                                                         Registry<ResourceComponentType<?>> componentTypes) {
        Result<Key> typeKey = map.getString("type").flatMap(Codecs::parseKey);
        if (typeKey.isErr()) return Result.err(typeKey.error());
        ResourceType type = types.get(typeKey.value()).orElse(null);
        if (type == null) return Result.err(new MachineError.MissingResource(Registries.RESOURCE_TYPE.key(), typeKey.value()));
        if (!map.has("components")) return Result.ok(type.variant());

        Result<DataMap> encoded = map.getMap("components");
        if (encoded.isErr()) return Result.err(encoded.error());
        ResourceComponents.Builder builder = ResourceComponents.builder();
        for (Map.Entry<String, DataValue> entry : encoded.value().entries().entrySet()) {
            Result<Key> componentKey = Codecs.parseKey(entry.getKey());
            if (componentKey.isErr()) return Result.err(underComponents(componentKey.error(), entry.getKey()));
            ResourceComponentType<?> componentType = componentTypes.get(componentKey.value()).orElse(null);
            if (componentType == null) {
                builder.unknown(componentKey.value(), entry.getValue());
                continue;
            }
            Result<?> decoded = decodeInto(builder, componentType, entry.getValue());
            if (decoded.isErr()) return Result.err(underComponents(decoded.error(), entry.getKey()));
        }
        return Result.ok(new ResourceVariant(type, builder.build()));
    }

    private static <T> Result<T> decodeInto(ResourceComponents.Builder builder, ResourceComponentType<T> type, DataValue data) {
        return type.codec().decode(data).ifOk(value -> builder.set(type, value));
    }

    private static MachineError underComponents(MachineError error, String component) {
        return error instanceof SerializationError serialization ? serialization.under("components." + component) : error;
    }
}
