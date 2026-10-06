package fr.robie.machineengine.api.diagnostic;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/** Handed to a {@link Validator}. */
@ApiStatus.NonExtendable
public interface ValidationContext {

    /** The registries being validated (staged content at startup, the new snapshot on reload). */
    @NotNull RegistryAccess registries();

    /**
     * Reports a finding. If it has no {@linkplain Diagnostic#source() source}, it is attributed to
     * the addon owning its {@linkplain Diagnostic#subject() subject}, or else to the validator's
     * own addon.
     */
    void report(@NotNull Diagnostic diagnostic);

    /** @return the owner of an entry, for attribution */
    default <T extends Keyed> @NotNull Optional<AddonId> owner(@NotNull RegistryType<T> type, @NotNull Key id) {
        return registries().registry(type).owner(id);
    }
}
