package fr.robie.machineengine.core.resource.fluid;

import fr.robie.machineengine.api.resource.fluid.FluidTexture;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The built-in fluid textures (docs/FLUID_TYPES_PLAN.md §2.3): a resource pack file, a vanilla texture,
 * a recolored vanilla pattern, and two drawn patterns (crystals, bubbles). Every one tiles and loops.
 */
public final class FluidTextures {
    /** Frames of drawn patterns (water's still animation has 32). */
    static final int DRAWN_FRAMES = 32;

    private FluidTextures() {
    }

    /** A file from the CraftEngine resource packs. */
    public static @NotNull FluidTexture file(@NotNull Key texture) {
        return (size, context) -> context.pack(texture, size);
    }

    /** A vanilla texture, by path ({@code block/lava_still}); {@code null}: water's still or flowing one. */
    public static @NotNull FluidTexture vanilla(String path) {
        return (size, context) -> context.vanilla(path != null ? path
                : context.flowing() ? "block/water_flow" : "block/water_still", size);
    }

    /**
     * A vanilla pattern recolored: each pixel's brightness picks a color between {@code from} (dark)
     * and {@code to} (light).
     *
     * @param pattern {@code water}, {@code lava} or a vanilla block texture path without {@code _still}/{@code _flow}
     */
    public static @NotNull FluidTexture recolor(@NotNull String pattern, int from, int to) {
        return (size, context) -> {
            String base = switch (pattern) {
                case "water", "lava" -> "block/" + pattern;
                default -> pattern;
            };
            String path = base.startsWith("block/water") || base.startsWith("block/lava")
                    ? base + (context.flowing() ? "_flow" : "_still") : base;
            List<BufferedImage> out = new ArrayList<>();
            for (BufferedImage frame : context.vanilla(path, size)) out.add(recolor(frame, from, to));
            return out;
        };
    }

    /** Cream (by default) slowly shifting, with crystals that twinkle (and drift down when flowing). */
    public static @NotNull FluidTexture crystals(int base, int sparkle) {
        return (size, context) -> crystalFrames(size, context.flowing(), base, sparkle);
    }

    /** A liquid with darker swirls and bubbles rising (carried down when flowing). */
    public static @NotNull FluidTexture bubbles(int base, int bubble) {
        return (size, context) -> bubbleFrames(size, context.flowing(), base, bubble);
    }

    static BufferedImage recolor(BufferedImage frame, int from, int to) {
        BufferedImage out = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++) {
                int argb = frame.getRGB(x, y);
                double light = (0.299 * ((argb >> 16) & 255) + 0.587 * ((argb >> 8) & 255) + 0.114 * (argb & 255)) / 255.0;
                out.setRGB(x, y, 0xFF000000 | mix(from, to, light));
            }
        }
        return out;
    }

    static List<BufferedImage> crystalFrames(int size, boolean flow, int base, int sparkle) {
        Random random = new Random(7);
        int count = size * size / 18;
        int[][] specks = new int[count][];
        double[] phases = new double[count];
        for (int i = 0; i < count; i++) {
            specks[i] = new int[]{random.nextInt(size), random.nextInt(size)};
            phases[i] = random.nextDouble();
        }
        List<BufferedImage> frames = new ArrayList<>(DRAWN_FRAMES);
        for (int f = 0; f < DRAWN_FRAMES; f++) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    img.setRGB(x, y, 0xFF000000 | shade(base, 0.85 + 0.25 * wave(x, y, f, size, 3)));
                }
            }
            for (int i = 0; i < count; i++) {
                double light = Math.max(0, Math.sin(2 * Math.PI * (f / (double) DRAWN_FRAMES * 2 + phases[i])));
                if (light <= 0.2) continue;
                int y = Math.floorMod(specks[i][1] + (flow ? f * size / DRAWN_FRAMES : 0), size);
                img.setRGB(specks[i][0], y, 0xFF000000 | mix(base, sparkle, 0.4 + 0.6 * light));
            }
            frames.add(img);
        }
        return frames;
    }

    static List<BufferedImage> bubbleFrames(int size, boolean flow, int base, int bubble) {
        Random random = new Random(11);
        int count = Math.max(1, size / 3);
        int[][] blobs = new int[count][];
        for (int i = 0; i < count; i++) {
            blobs[i] = new int[]{random.nextInt(size), random.nextInt(size), random.nextInt(3) == 0 ? 2 : 1};
        }
        List<BufferedImage> frames = new ArrayList<>(DRAWN_FRAMES);
        for (int f = 0; f < DRAWN_FRAMES; f++) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    img.setRGB(x, y, 0xFF000000 | shade(base, 0.75 + 0.35 * wave(x, y, f, size, 5)));
                }
            }
            int shift = f * size / DRAWN_FRAMES;
            for (int[] blob : blobs) {
                int r = blob[2];
                int cy = Math.floorMod(blob[1] + (flow ? shift : -shift), size);
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        int d = dx * dx + dy * dy;
                        if (d >= r * r - r && d <= r * r + r) {
                            img.setRGB(Math.floorMod(blob[0] + dx, size), Math.floorMod(cy + dy, size), 0xFF000000 | bubble);
                        }
                    }
                }
            }
            frames.add(img);
        }
        return frames;
    }

    /** Smooth noise in 0..1 that tiles over {@code size} and loops over the animation. */
    static double wave(int x, int y, int frame, int size, long seed) {
        Random random = new Random(seed);
        double total = 0;
        for (int i = 0; i < 4; i++) {
            int a = 1 + random.nextInt(3), b = random.nextInt(7) - 3, c = random.nextBoolean() ? 1 : -1;
            total += Math.sin(2 * Math.PI * (a * x / (double) size + b * y / (double) size + c * frame / (double) DRAWN_FRAMES)
                    + random.nextDouble() * 2 * Math.PI);
        }
        return 0.5 + total / 8;
    }

    static int mix(int from, int to, double t) {
        int color = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            double channel = ((from >> shift) & 255) * (1 - t) + ((to >> shift) & 255) * t;
            color |= Math.max(0, Math.min(255, (int) Math.round(channel))) << shift;
        }
        return color;
    }

    static int shade(int color, double factor) {
        int out = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            out |= Math.max(0, Math.min(255, (int) Math.round(((color >> shift) & 255) * factor))) << shift;
        }
        return out;
    }
}
