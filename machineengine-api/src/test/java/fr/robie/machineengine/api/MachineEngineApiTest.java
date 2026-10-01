package fr.robie.machineengine.api;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MachineEngineApiTest {

    @Test
    void versionIsExpandedAtBuildTime() {
        String version = MachineEngineApi.version();
        assertFalse(version.isBlank());
        assertFalse(version.contains("${"), "version.properties was not expanded: " + version);
    }

    @Test
    void keysUseTheMachineEngineNamespace() {
        assertEquals(Key.key("machineengine", "machine"), MachineEngineKeys.key("machine"));
    }

    @Test
    void invalidKeyValuesAreRejected() {
        assertThrows(InvalidKeyException.class, () -> MachineEngineKeys.key("Not Valid"));
    }
}
