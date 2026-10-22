package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.SchedulerState;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A pushing machine pushes its output only: a by-product nobody drains must not keep it retrying. */
class ByProductTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final MachineType REFINER = MachineType.builder(Key.key("omega", "refiner"))
            .storage(StorageDefinition.tank("input", 100))
            .storage(StorageDefinition.tank("output", 100))
            .storage(StorageDefinition.tank("waste", 100))
            .behavior(ProcessingBehavior.pushing(MachineFixtures.CONSUMING,
                    new ProcessBinding(List.of("input"), "output", null, List.of("waste")), RelativeSide.FRONT, 20))
            .build();

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon(MachineFixtures.alpha())
            .addon(MachineFixtures.beta())
            .addon(AddonRegistration.builder("omega", "1.0.0").requires("beta", "1.0")
                    .register(ctx -> ctx.machineTypes().register(REFINER)).build())
            .start();

    @Test
    void anUndrainedByProductLetsTheMachineWaitForInput() {
        FakeWorld world = FakeWorld.singleRegion(this.engine);
        var refiner = world.place(REFINER, HERE, Direction.NORTH);
        MachineFixtures.fill(refiner, "waste", MachineFixtures.B.stack(30));
        world.tick(3);

        assertEquals(SchedulerState.WAITING, world.state(HERE), "nothing to do until input arrives");
        assertNull(world.inspect(HERE).lastError(), "not blocked by an output it never pushes");
    }
}
