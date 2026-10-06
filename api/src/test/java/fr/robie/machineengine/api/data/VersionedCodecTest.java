package fr.robie.machineengine.api.data;

import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionedCodecTest {
    private static final Key KEY = Key.key("mymod", "furnace_state");

    /** v1 stored {"heat": n}; v2 renamed it to "temperature"; v3 stores milli-Kelvin. */
    private static final VersionedCodec<Long> CODEC = VersionedCodec.builder(KEY, Codecs.LONG)
            .version(3)
            .migrate(1, data -> ((DataMap) data).getLong("heat").map(heat -> (DataValue) DataMap.builder().put("temperature", heat).build()))
            .migrate(2, data -> ((DataMap) data).getLong("temperature").map(kelvin -> (DataValue) DataValue.of(kelvin * 1000)))
            .build();

    @Test
    void encodesWithItsVersion() {
        assertEquals(DataMap.builder().put("v", 3).put("data", 300_000).build(), CODEC.encode(300_000L));
        assertEquals(300_000L, CODEC.decode(CODEC.encode(300_000L)).value());
    }

    @Test
    void migratesOldDataStepByStep() {
        DataMap v1 = DataMap.builder().put("v", 1).put("data", DataMap.builder().put("heat", 350).build()).build();
        assertEquals(350_000L, CODEC.decode(v1).value());
        assertEquals(1, CODEC.oldestSupportedVersion());
    }

    @Test
    void refusesDataFromTheFuture() {
        Result<Long> result = CODEC.decode(DataMap.builder().put("v", 4).put("data", 1).build());
        MachineError.SerializationError error = assertInstanceOf(MachineError.SerializationError.class, result.error());
        assertTrue(error.message().contains("newer"), error.message());
    }

    @Test
    void reportsBrokenMigrationChains() {
        VersionedCodec<Long> gap = VersionedCodec.builder(KEY, Codecs.LONG).version(4)
                .migrate(1, Result::ok).migrate(3, Result::ok).build();
        assertEquals(List.of("no migration from version 2 (gap between 1 and 3)"), gap.definitionProblems());
        assertEquals(3, gap.oldestSupportedVersion());

        VersionedCodec<Long> outOfRange = VersionedCodec.builder(KEY, Codecs.LONG).version(2).migrate(2, Result::ok).build();
        assertTrue(outOfRange.definitionProblems().stream().anyMatch(problem -> problem.contains("outside")));
        assertTrue(CODEC.definitionProblems().isEmpty());
    }
}
