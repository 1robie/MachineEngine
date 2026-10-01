package fr.robie.machineengine.craftengine;

import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftEngineKeysTest {

    @Test
    void roundTripsThroughCraftEngineKeys() {
        Key key = Key.key("mymod", "refinery");
        net.momirealms.craftengine.core.util.Key converted = CraftEngineKeys.toCraftEngine(key);

        assertEquals("mymod", converted.namespace());
        assertEquals("refinery", converted.value());
        assertEquals(key, CraftEngineKeys.fromCraftEngine(converted));
    }
}
