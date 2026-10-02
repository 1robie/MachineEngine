package fr.robie.machineengine.core.data;

import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.DataValue.DataBool;
import fr.robie.machineengine.api.data.DataValue.DataBytes;
import fr.robie.machineengine.api.data.DataValue.DataDouble;
import fr.robie.machineengine.api.data.DataValue.DataList;
import fr.robie.machineengine.api.data.DataValue.DataLong;
import fr.robie.machineengine.api.data.DataValue.DataString;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * A compact, compressed binary form of {@link DataValue} trees, for platforms that store raw bytes
 * (e.g. machine contents kept in an item, §20.5). Platform-neutral and versioned.
 */
public final class DataBinary {
    private static final int VERSION = 1;
    private static final int MAX_DEPTH = 64;
    private static final byte MAP = 0, LIST = 1, STRING = 2, LONG = 3, DOUBLE = 4, BOOL = 5, BYTES = 6;

    private DataBinary() {
    }

    public static byte @NotNull [] write(@NotNull DataMap map) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeByte(VERSION);
            write(out, map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** @throws IOException if the bytes are not a tree written by {@link #write} */
    public static @NotNull DataMap read(byte @NotNull [] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported data version " + version);
            if (!(read(in, 0) instanceof DataMap map)) throw new IOException("Root is not a map");
            return map;
        }
    }

    private static void write(DataOutputStream out, DataValue value) throws IOException {
        switch (value) {
            case DataMap map -> {
                out.writeByte(MAP);
                out.writeInt(map.size());
                for (Map.Entry<String, DataValue> entry : map.entries().entrySet()) {
                    out.writeUTF(entry.getKey());
                    write(out, entry.getValue());
                }
            }
            case DataList list -> {
                out.writeByte(LIST);
                out.writeInt(list.values().size());
                for (DataValue element : list.values()) write(out, element);
            }
            case DataString string -> {
                out.writeByte(STRING);
                out.writeUTF(string.value());
            }
            case DataLong number -> {
                out.writeByte(LONG);
                out.writeLong(number.value());
            }
            case DataDouble number -> {
                out.writeByte(DOUBLE);
                out.writeDouble(number.value());
            }
            case DataBool bool -> {
                out.writeByte(BOOL);
                out.writeBoolean(bool.value());
            }
            case DataBytes raw -> {
                byte[] content = raw.value();
                out.writeByte(BYTES);
                out.writeInt(content.length);
                out.write(content);
            }
        }
    }

    private static DataValue read(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Data nested too deeply");
        byte kind = in.readByte();
        return switch (kind) {
            case MAP -> {
                int size = size(in);
                Map<String, DataValue> entries = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = in.readUTF();
                    entries.put(key, read(in, depth + 1));
                }
                yield DataMap.of(entries);
            }
            case LIST -> {
                int size = size(in);
                List<DataValue> values = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) values.add(read(in, depth + 1));
                yield DataValue.list(values);
            }
            case STRING -> DataValue.of(in.readUTF());
            case LONG -> DataValue.of(in.readLong());
            case DOUBLE -> DataValue.of(in.readDouble());
            case BOOL -> DataValue.of(in.readBoolean());
            case BYTES -> new DataBytes(in.readNBytes(size(in)));
            default -> throw new IOException("Unknown value kind " + kind);
        };
    }

    private static int size(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 16 * 1024 * 1024) throw new IOException("Invalid size " + size);
        return size;
    }
}
