package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.resource.ResourceCodecs;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static fr.robie.machineengine.test.Fixtures.FE;
import static fr.robie.machineengine.test.Fixtures.STEAM;
import static fr.robie.machineengine.test.Fixtures.TEMPERATURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceCodecTest {
    private final CoreMachineEngine engine = TestEngine.builder()
            .addon("energyplugin", Fixtures::registerEnergy)
            .addon("fluids", Fixtures::registerFluids)
            .start();
    private final ResourceCodecs codecs = this.engine.resourceCodecs();

    @Test
    void stacksRoundTripWithComponents() {
        ResourceStack stack = STEAM.variant().with(TEMPERATURE, 400L).stack(500);
        DataValue encoded = this.codecs.stack().encode(stack);

        assertEquals(DataMap.builder()
                .put("type", "mymod:steam")
                .put("components", DataMap.builder().put("mymod:temperature", 400).build())
                .put("amount", 500)
                .build(), encoded);
        assertEquals(stack, this.codecs.stack().decode(encoded).value());
        assertEquals(FE.stack(7), this.codecs.stack().decode(this.codecs.stack().encode(FE.stack(7))).value());
    }

    @Test
    void unknownComponentsArePreservedVerbatim() {
        DataMap encoded = DataMap.builder()
                .put("type", "mymod:steam")
                .put("components", DataMap.builder()
                        .put("mymod:temperature", 350)
                        .put("removedmod:purity", DataMap.builder().put("value", 92).build())
                        .build())
                .build();

        ResourceVariant variant = this.codecs.variant().decode(encoded).value();
        assertEquals(350L, variant.components().get(TEMPERATURE));
        assertTrue(variant.components().unknown().containsKey(Key.key("removedmod", "purity")));
        assertEquals(encoded, this.codecs.variant().encode(variant), "re-encoding writes the unknown component back unchanged");
    }

    @Test
    void anUnregisteredTypeIsAMissingResourceError() {
        Result<ResourceVariant> result = this.codecs.variant().decode(DataMap.builder().put("type", "removedmod:plasma").build());
        MachineError.MissingResource missing = assertInstanceOf(MachineError.MissingResource.class, result.error());
        assertEquals(Key.key("removedmod", "plasma"), missing.id());
    }

    @Test
    void malformedDataReportsWhere() {
        DataMap encoded = DataMap.builder()
                .put("type", "mymod:steam")
                .put("components", DataMap.builder().put("mymod:temperature", "hot").build())
                .build();
        MachineError.SerializationError error = assertInstanceOf(MachineError.SerializationError.class,
                this.codecs.variant().decode(encoded).error());
        assertEquals("components.mymod:temperature", error.path());

        MachineError.SerializationError negative = assertInstanceOf(MachineError.SerializationError.class,
                this.codecs.stack().decode(DataMap.builder().put("type", "energyplugin:fe").put("amount", -5).build()).error());
        assertEquals("amount", negative.path());
    }
}
