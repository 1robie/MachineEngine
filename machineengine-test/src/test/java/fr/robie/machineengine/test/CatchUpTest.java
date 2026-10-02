package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.machine.CatchUpPolicy;
import fr.robie.machineengine.api.machine.MachineInspection;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.Direction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Time spent unloaded (D4, §18): frozen by default, bounded catch-up when the type opts in. The
 * generators make 10 alpha:b every 20 ticks. They are saved after 25 ticks: one batch made, the
 * second process 5 ticks in (4 of which are not yet recorded, because it sleeps until done).
 */
class CatchUpTest {
    private static final WorldPosition HERE = FakeWorld.at(0, 64, 0);
    private static final MachineType PATIENT = generator("patient_generator", CatchUpPolicy.bounded(60));
    private static final MachineType FROZEN = generator("frozen_generator", CatchUpPolicy.NONE);

    private static MachineType generator(String name, CatchUpPolicy policy) {
        return MachineType.builder(Key.key("omega", name))
                .storage(StorageDefinition.tank("output", 1000))
                .catchUp(policy)
                .behavior(ProcessingBehavior.of(MachineFixtures.GENERATING, new ProcessBinding(List.of(), "output", null)))
                .build();
    }

    private final CoreMachineEngine engine = TestEngine.builder()
            .addon(MachineFixtures.alpha())
            .addon(AddonRegistration.builder("omega", "1.0.0").requires("alpha", "1.0").register(ctx -> {
                ctx.machineTypes().register(PATIENT);
                ctx.machineTypes().register(FROZEN);
            }).build())
            .start();

    @Test
    void underTheCapCatchUpMatchesAnUninterruptedRun() {
        // 25 ticks before, 10 unloaded: like 35 ticks of uninterrupted work (one batch, 15 ticks into the next).
        MachineInspection after = reload(PATIENT, 10);
        assertEquals(10, output(after));
        assertEquals(15, after.process().progress());
    }

    @Test
    void catchUpIsCappedAtTheBound() {
        // 4 slept ticks + at most 60 unloaded = 64 ticks of work from progress 1:
        // finish the batch (19), two more (40), 5 into the next.
        MachineInspection after = reload(PATIENT, 500);
        assertEquals(40, output(after));
        assertEquals(5, after.process().progress());
    }

    @Test
    void withoutCatchUpTheMachineResumesWhereItStopped() {
        FakeWorld later = load(FROZEN, 500);
        assertEquals(10, output(later.inspect(HERE)), "nothing happened while unloaded");
        later.tick(15);
        assertEquals(20, output(later.inspect(HERE)), "the second batch finishes 15 ticks after loading, as if never unloaded");
    }

    private MachineInspection reload(MachineType type, long absence) {
        return load(type, absence).inspect(HERE);
    }

    private FakeWorld load(MachineType type, long absence) {
        FakeWorld original = FakeWorld.singleRegion(this.engine);
        original.place(type, HERE, Direction.NORTH);
        original.tick(25);
        assertEquals(10, output(original.inspect(HERE)));
        DataMap saved = original.save(HERE);
        FakeWorld later = FakeWorld.singleRegion(this.engine);
        later.skipTime(original.time() + absence);
        later.load(HERE, saved);
        return later;
    }

    private static long output(MachineInspection inspection) {
        return inspection.storages().get("output").stream().mapToLong(StorageView::amount).sum();
    }
}
