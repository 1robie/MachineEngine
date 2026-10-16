package fr.robie.machineengine.nms;

import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Biome sections as the client reads them in a chunk biomes packet: one paletted container of 4×4×4
 * biome ids per section. Used by every version's world fluids (D46); each checks the bytes against the
 * server's own reader at startup.
 */
public final class BiomeSections {
    private BiomeSections() {
    }

    /**
     * Appends one section.
     *
     * @param ids          the 64 client biome ids, index {@code (y << 4) | (z << 2) | x}
     * @param clientSize   the number of biomes the client knows (sets the global palette's bits)
     * @param lengthPrefix whether the long array is preceded by its length (before 1.21.5)
     */
    public static void write(@NotNull ByteArrayOutputStream out, int @NotNull [] ids, int clientSize, boolean lengthPrefix) {
        int[] palette = Arrays.stream(ids).distinct().toArray();
        if (palette.length == 1) {
            out.write(0);
            writeVarInt(out, palette[0]);
            if (lengthPrefix) writeVarInt(out, 0);
            return;
        }
        int bits = ceilLog2(palette.length);
        boolean linear = bits <= 3;
        if (!linear) bits = ceilLog2(clientSize);
        out.write(bits);
        if (linear) {
            writeVarInt(out, palette.length);
            for (int id : palette) writeVarInt(out, id);
        }
        int perLong = 64 / bits;
        long[] longs = new long[(64 + perLong - 1) / perLong];
        for (int i = 0; i < 64; i++) {
            long value = ids[i];
            if (linear) {
                for (int p = 0; p < palette.length; p++) {
                    if (palette[p] == ids[i]) {
                        value = p;
                        break;
                    }
                }
            }
            longs[i / perLong] |= value << ((i % perLong) * bits);
        }
        if (lengthPrefix) writeVarInt(out, longs.length);
        for (long l : longs) {
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (l >>> shift) & 0xFF);
        }
    }

    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }
}
