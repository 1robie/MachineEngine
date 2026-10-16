package fr.robie.machineengine.paper.fluid;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The placeable fluids of one chunk, saved in the chunk's own data (D46): block positions and fluid keys.
 * Keys are saved, not numbers, so fluids keep their place when others are added or removed; a fluid
 * whose addon is missing stays in the data unchanged (D3) and its blocks show as plain water.
 *
 * <p>Format 1: version byte; key count, keys (UTF); entry count, entries (packed position, key index).</p>
 */
public final class FluidChunkCodec {
    public static final int VERSION = 1;

    private FluidChunkCodec() {
    }

    /** Thrown for data written by a newer MachineEngine: the chunk's data must be left as it is. */
    public static final class NewerFormatException extends Exception {
        NewerFormatException(int version) {
            super("fluid data format " + version + " is newer than " + VERSION);
        }
    }

    public static byte @NotNull [] encode(@NotNull Map<Long, Key> blocks) {
        Map<Key, Integer> indices = new LinkedHashMap<>();
        for (Key key : blocks.values()) indices.putIfAbsent(key, indices.size());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeInt(indices.size());
            for (Key key : indices.keySet()) out.writeUTF(key.asString());
            out.writeInt(blocks.size());
            for (Map.Entry<Long, Key> entry : blocks.entrySet()) {
                out.writeLong(entry.getKey());
                out.writeInt(indices.get(entry.getValue()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * @return block position (packed) to fluid key
     * @throws NewerFormatException for data of a newer format
     * @throws IOException          for damaged data
     */
    public static @NotNull Map<Long, Key> decode(byte @NotNull [] data) throws IOException, NewerFormatException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readUnsignedByte();
            if (version > VERSION) throw new NewerFormatException(version);
            if (version != VERSION) throw new IOException("unknown fluid data format " + version);
            int keyCount = in.readInt();
            if (keyCount < 0 || keyCount > data.length) throw new IOException("bad key count " + keyCount);
            List<Key> keys = new ArrayList<>(keyCount);
            for (int i = 0; i < keyCount; i++) {
                String text = in.readUTF();
                try {
                    keys.add(Key.key(text));
                } catch (RuntimeException e) {
                    throw new IOException("bad fluid key " + text, e);
                }
            }
            int count = in.readInt();
            if (count < 0 || count > data.length / 12) throw new IOException("bad entry count " + count);
            Map<Long, Key> blocks = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                long position = in.readLong();
                int index = in.readInt();
                if (index < 0 || index >= keys.size()) throw new IOException("bad key index " + index);
                blocks.put(position, keys.get(index));
            }
            return blocks;
        }
    }
}
