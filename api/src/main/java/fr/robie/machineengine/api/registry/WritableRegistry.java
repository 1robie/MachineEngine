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

    /**
     * Makes the old key {@code from} mean the entry {@code to}, for an entry that was renamed or moved
     * to another addon. Everything that looks entries up by key follows it: saved machine data,
     * YAML definitions, recipes and tags. Data is saved again under {@code to}.
     *
     * <p>Startup validation fails the alias's addon if {@code to} is not an entry of this registry
     * (aliases do not chain) or if {@code from} is in a namespace reserved for MachineEngine.</p>
     *
     * @throws RegistrationException if {@code from} already is an entry or an alias, or the registry is frozen
     * @throws IllegalArgumentException if {@code from} equals {@code to}
     */
    void alias(@NotNull Key from, @NotNull Key to);
}
