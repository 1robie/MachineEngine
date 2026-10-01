package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.addon.AddonId;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/** Handed to a {@link ReloadableProvider} while a snapshot is being built. */
@ApiStatus.NonExtendable
public interface ReloadContext<T extends Keyed> {

    @NotNull AddonId addon();

    /** Static registries (frozen) and the reloadable registries' current snapshots. */
    @NotNull RegistryAccess registries();

    /**
     * @throws RegistrationException if the key is already used in this snapshot
     */
    <V extends T> @NotNull V register(@NotNull V value);
}
