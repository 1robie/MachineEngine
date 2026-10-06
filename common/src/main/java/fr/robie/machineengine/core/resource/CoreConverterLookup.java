package fr.robie.machineengine.core.resource;

import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.api.resource.convert.ResourceConverterLookup;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Converter index built once from the frozen converter registry. */
public final class CoreConverterLookup implements ResourceConverterLookup {
    private final Map<ResourceType, List<ResourceConverter>> byInput;

    public CoreConverterLookup(@NotNull Registry<ResourceConverter> converters) {
        Map<ResourceType, List<ResourceConverter>> index = new IdentityHashMap<>();
        for (ResourceConverter converter : converters) {
            index.computeIfAbsent(converter.from(), type -> new ArrayList<>()).add(converter);
        }
        index.replaceAll((type, list) -> List.copyOf(list));
        this.byInput = index;
    }

    @Override
    public @NotNull List<ResourceConverter> find(@NotNull ResourceType from, @NotNull ResourceType to) {
        return from(from).stream().filter(converter -> converter.to() == to).toList();
    }

    @Override
    public @NotNull List<ResourceConverter> from(@NotNull ResourceType from) {
        return this.byInput.getOrDefault(from, List.of());
    }
}
