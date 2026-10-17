package fr.robie.machineengine.paper.fluid.client;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The key colors (docs/FLUID_TYPES_PLAN.md §4.3): the water color a fluid look's cells get, which the
 * shader recognizes after the client blends it with the neutral water. Keys are spread as far apart as
 * possible in direction, seen from the neutral water (a blend moves a key's color toward the neutral
 * along that direction). Deterministic: the same count always gives the same keys.
 */
public final class KeyPalette {
    /** With uniform water: the most keys the simulation reads right (0.2% wrong at 40–100% share). */
    public static final int MAX_UNIFORM = 48;
    /** Without uniform water: vanilla's 11 water colors leave room for these 6 (spike, §4.3). */
    public static final int[] VANILLA_WATER_KEYS = {0xAA00FF, 0x80FF00, 0xFFAA00, 0x0000FF, 0x00FF2A, 0xFF0000};

    private KeyPalette() {
    }

    /** The {@code count} keys around {@code neutral} (0xRRGGBB). */
    public static @NotNull List<Integer> choose(int count, int neutral) {
        if (count <= 0) return List.of();
        double[] center = chroma(neutral);
        List<Integer> candidates = new ArrayList<>();
        for (int h = 0; h < 360; h += 3) {
            for (double saturation : new double[]{1.0, 0.85, 0.7}) {
                int rgb = hsv(h / 360.0, saturation);
                double[] c = chroma(rgb);
                if (Math.hypot(c[0] - center[0], c[1] - center[1]) > 0.12) candidates.add(rgb);
            }
        }
        List<Integer> keys = new ArrayList<>();
        int first = candidates.getFirst();
        double farthest = -1;
        for (int candidate : candidates) {
            double[] c = chroma(candidate);
            double distance = Math.hypot(c[0] - center[0], c[1] - center[1]);
            if (distance > farthest) {
                farthest = distance;
                first = candidate;
            }
        }
        keys.add(first);
        while (keys.size() < count && keys.size() < candidates.size()) {
            int best = candidates.getFirst();
            double bestGap = -1;
            for (int candidate : candidates) {
                double angle = angle(candidate, center);
                double gap = Double.POSITIVE_INFINITY;
                for (int key : keys) gap = Math.min(gap, Math.abs(floorMod(angle - angle(key, center) + Math.PI, 2 * Math.PI) - Math.PI));
                if (gap > bestGap) {
                    bestGap = gap;
                    best = candidate;
                }
            }
            keys.add(best);
        }
        return keys;
    }

    /**
     * The smallest share of a key the shader still shows as its fluid: below it, too many keys sit close
     * enough to be mistaken (§4.3: under about 0.1% wrong).
     */
    public static double shareCut(int keys) {
        if (keys <= 12) return 0.05;
        if (keys <= 22) return 0.20;
        return 0.40;
    }

    /** {@code share} of color {@code a}, the rest of {@code b} (0xRRGGBB). */
    public static int mix(int a, int b, double share) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            double channel = ((a >> shift) & 255) * share + ((b >> shift) & 255) * (1 - share);
            color |= ((int) Math.round(channel)) << shift;
        }
        return color;
    }

    private static double[] chroma(int rgb) {
        double r = ((rgb >> 16) & 255) / 255.0, g = ((rgb >> 8) & 255) / 255.0, b = (rgb & 255) / 255.0;
        double sum = r + g + b;
        if (sum == 0) return new double[]{1 / 3.0, 1 / 3.0}; // black: no hue, the middle
        return new double[]{r / sum, g / sum};
    }

    private static double angle(int rgb, double[] center) {
        double[] c = chroma(rgb);
        return Math.atan2(c[1] - center[1], c[0] - center[0]);
    }

    private static double floorMod(double value, double modulus) {
        double result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    /** Python's {@code colorsys.hsv_to_rgb} at full value, rounded half to even like the simulator. */
    private static int hsv(double hue, double saturation) {
        int i = (int) (hue * 6.0);
        double f = hue * 6.0 - i;
        double v = 1.0, p = v * (1 - saturation), q = v * (1 - saturation * f), t = v * (1 - saturation * (1 - f));
        double[] rgb = switch (i % 6) {
            case 0 -> new double[]{v, t, p};
            case 1 -> new double[]{q, v, p};
            case 2 -> new double[]{p, v, t};
            case 3 -> new double[]{p, q, v};
            case 4 -> new double[]{t, p, v};
            default -> new double[]{v, p, q};
        };
        return ((int) Math.rint(rgb[0] * 255) << 16) | ((int) Math.rint(rgb[1] * 255) << 8) | (int) Math.rint(rgb[2] * 255);
    }
}
