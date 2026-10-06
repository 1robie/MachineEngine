package fr.robie.machineengine.api;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * Keys in MachineEngine's own namespace (ARCHITECTURE.md §6).
 */
public final class MachineEngineKeys {
    /** The namespace of every identifier MachineEngine itself defines. */
    public static final String NAMESPACE = "machineengine";

    private MachineEngineKeys() {
    }

    /**
     * Creates a key in the {@value #NAMESPACE} namespace.
     *
     * @param value the key value, e.g. {@code machine}
     * @return {@code machineengine:<value>}
     * @throws InvalidKeyException if {@code value} is not a valid key value
     */
    public static @NotNull Key key(@NotNull String value) {
        return Key.key(NAMESPACE, value);
    }
}
