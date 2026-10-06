package fr.robie.machineengine.api.resource.convert;

import fr.robie.machineengine.api.resource.ResourceType;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Finds registered converters. Returns all candidates in registration order; the caller chooses
 * (MachineEngine never picks one implicitly, §9.4).
 */
@ApiStatus.NonExtendable
public interface ResourceConverterLookup {

    @NotNull List<ResourceConverter> find(@NotNull ResourceType from, @NotNull ResourceType to);

    @NotNull List<ResourceConverter> from(@NotNull ResourceType from);
}
