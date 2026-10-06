package fr.robie.machineengine.api.registry;

import fr.robie.machineengine.api.addon.RegistrationContext;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Registration-time handle on a static registry. Entries are attributed to the addon whose
 * {@link RegistrationContext} provided this handle.
 */
@ApiStatus.NonExtendable
public interface WritableRegistry<T extends Keyed> {

    @NotNull RegistryType<T> type();

    /**
     * Registers {@code value} under its {@link Keyed#key()}.
     *
     * @return {@code value}
     * @throws RegistrationException if the key is taken or the registry is frozen
     */
    <V extends T> @NotNull V register(@NotNull V value);

    boolean contains(@NotNull Key id);
}
