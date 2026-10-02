package fr.robie.machineengine.craftengine;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.DataValue.DataBool;
import fr.robie.machineengine.api.data.DataValue.DataBytes;
import fr.robie.machineengine.api.data.DataValue.DataDouble;
import fr.robie.machineengine.api.data.DataValue.DataList;
import fr.robie.machineengine.api.data.DataValue.DataLong;
import fr.robie.machineengine.api.data.DataValue.DataString;
import net.momirealms.craftengine.libraries.nbt.ByteArrayTag;
import net.momirealms.craftengine.libraries.nbt.ByteTag;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import net.momirealms.craftengine.libraries.nbt.DoubleTag;
import net.momirealms.craftengine.libraries.nbt.FloatTag;
import net.momirealms.craftengine.libraries.nbt.ListTag;
import net.momirealms.craftengine.libraries.nbt.LongTag;
import net.momirealms.craftengine.libraries.nbt.NumericTag;
import net.momirealms.craftengine.libraries.nbt.StringTag;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps MachineEngine's format-neutral {@link DataValue} trees to CraftEngine's sparrow NBT and back
 * (§20.3). Lists whose elements are not all of one kind are wrapped, because NBT lists are
 * homogeneous: {@code {"__mixed": [{"v": a}, {"v": b}]}}.
 */
public final class NbtData {
    private static final String MIXED = "__mixed";
    private static final String VALUE = "v";

    private NbtData() {
    }

    public static @NotNull CompoundTag toTag(@NotNull DataMap map) {
        CompoundTag tag = new CompoundTag();
        map.entries().forEach((key, value) -> tag.put(key, toTag(value)));
        return tag;
    }

    public static @NotNull Tag toTag(@NotNull DataValue value) {
        return switch (value) {
            case DataMap map -> toTag(map);
            case DataString string -> new StringTag(string.value());
            case DataLong number -> LongTag.valueOf(number.value());
            case DataDouble number -> new DoubleTag(number.value());
            case DataBool bool -> ByteTag.valueOf(bool.value());
            case DataBytes bytes -> new ByteArrayTag(bytes.value());
            case DataList list -> listTag(list);
        };
    }

    private static Tag listTag(DataList list) {
        boolean homogeneous = list.values().stream().map(DataValue::kind).distinct().count() <= 1;
        if (homogeneous) {
            ListTag tag = new ListTag();
            for (DataValue element : list.values()) tag.add(toTag(element));
            return tag;
        }
        ListTag wrapped = new ListTag();
        for (DataValue element : list.values()) {
            CompoundTag holder = new CompoundTag();
            holder.put(VALUE, toTag(element));
            wrapped.add(holder);
        }
        CompoundTag mixed = new CompoundTag();
        mixed.put(MIXED, wrapped);
        return mixed;
    }

    public static @NotNull DataMap toData(@NotNull CompoundTag tag) {
        Map<String, DataValue> entries = new LinkedHashMap<>();
        for (Map.Entry<String, Tag> entry : tag.entrySet()) entries.put(entry.getKey(), toData(entry.getValue()));
        return DataMap.of(entries);
    }

    public static @NotNull DataValue toData(@NotNull Tag tag) {
        return switch (tag) {
            case CompoundTag compound when compound.size() == 1 && compound.get(MIXED) instanceof ListTag wrapped -> {
                List<DataValue> values = new ArrayList<>(wrapped.size());
                for (int i = 0; i < wrapped.size(); i++) {
                    Tag holder = wrapped.get(i);
                    values.add(holder instanceof CompoundTag compoundHolder && compoundHolder.get(VALUE) != null
                            ? toData(compoundHolder.get(VALUE)) : toData(holder));
                }
                yield DataValue.list(values);
            }
            case CompoundTag compound -> toData(compound);
            case StringTag string -> DataValue.of(string.value());
            case ByteTag bool -> DataValue.of(bool.booleanValue());
            case DoubleTag number -> DataValue.of(number.value());
            case FloatTag number -> DataValue.of((double) number.getAsFloat());
            case NumericTag number -> DataValue.of(number.getAsLong());
            case ByteArrayTag bytes -> new DataBytes(bytes.getAsByteArray());
            case ListTag list -> {
                List<DataValue> values = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) values.add(toData(list.get(i)));
                yield DataValue.list(values);
            }
            default -> DataValue.of(tag.getAsString());
        };
    }
}
