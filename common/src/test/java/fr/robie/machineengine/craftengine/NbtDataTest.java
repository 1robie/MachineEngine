package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NbtDataTest {

    @Test
    void roundTripsEveryKindOfValue() {
        DataMap data = DataMap.builder()
                .put("text", "mymod:steam")
                .put("count", 9_000_000_000L)
                .put("flag", true)
                .put("ratio", DataValue.of(0.25))
                .put("bytes", new DataValue.DataBytes(new byte[]{1, 2, 3}))
                .put("empty", DataMap.EMPTY)
                .put("longs", DataValue.list(List.of(DataValue.of(1), DataValue.of(2))))
                .put("maps", DataValue.list(List.of(DataMap.builder().put("a", 1).build(), DataMap.EMPTY)))
                .put("mixed", DataValue.list(List.of(DataValue.of(1), DataValue.of("two"), DataMap.builder().put("three", 3).build())))
                .put("none", DataValue.list(List.of()))
                .build();

        CompoundTag tag = NbtData.toTag(data);
        assertEquals(data, NbtData.toData(tag));
        assertEquals(data, NbtData.toData(tag.deepClone()));
    }
}
