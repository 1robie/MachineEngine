package fr.robie.machineengine.test;

import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.api.resource.convert.ResourceConverter.ConversionResult;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static fr.robie.machineengine.test.Fixtures.EU;
import static fr.robie.machineengine.test.Fixtures.FE;
import static fr.robie.machineengine.test.Fixtures.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConverterTest {
    private static final ResourceConverter FE_TO_EU = ResourceConverter.ratio(Key.key("energyplugin", "fe_to_eu"), FE, 4, EU, 1);

    @Test
    void ratioConvertsWholeUnitsOnly() {
        ConversionResult result = FE_TO_EU.convert(FE.variant(), 10);
        assertEquals(8, result.consumed());
        assertEquals(EU.stack(2), result.output());
        assertNull(FE_TO_EU.convert(FE.variant(), 3), "less than one unit converts nothing");
        assertThrows(IllegalArgumentException.class, () -> FE_TO_EU.convert(WATER.variant(), 10));
    }

    @Test
    void lookupReturnsEveryCandidateWithoutChoosing() {
        ResourceConverter alternative = ResourceConverter.ratio(Key.key("energyplugin", "fe_to_eu_lossy"), FE, 5, EU, 1);
        CoreMachineEngine engine = TestEngine.builder()
                .addon("energyplugin", ctx -> {
                    Fixtures.registerEnergy(ctx);
                    ctx.converters().register(FE_TO_EU);
                    ctx.converters().register(alternative);
                })
                .start();

        assertEquals(List.of(FE_TO_EU, alternative), engine.converters().find(FE, EU));
        assertTrue(engine.converters().find(EU, FE).isEmpty(), "converters are one-way");
    }

    @Test
    void identityConvertersAreInvalid() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("energyplugin", ctx -> {
                    Fixtures.registerEnergy(ctx);
                    ctx.converters().register(ResourceConverter.ratio(Key.key("energyplugin", "fe_to_fe"), FE, 1, FE, 1));
                })
                .start();

        LifecycleTest.assertHasError(engine, DiagnosticCodes.INVALID_DEFINITION, "energyplugin");
    }
}
