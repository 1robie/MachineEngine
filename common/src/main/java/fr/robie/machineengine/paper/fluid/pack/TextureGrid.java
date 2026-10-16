package fr.robie.machineengine.paper.fluid.pack;

import org.jetbrains.annotations.NotNull;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * The water sprite the pack replaces: every fluid texture side by side (docs/FLUID_TYPES_PLAN.md §4.3).
 * One animation frame is a {@code columns × columns} grid of tiles (tile 0 is vanilla water); frames are
 * stacked downwards, as a {@code .mcmeta} animation.
 *
 * <p>The alpha of every texel holds where it sits in its frame, so the shader can find the frame in the
 * atlas (the shader sets the real opacity itself). Alpha is {@code 128 + tag × 32 + payload}, where
 * {@code tag} is the texel's place in its 2×2 block and the block's top-left position is spread over the
 * four:</p>
 * <table>
 *   <tr><th>tag</th><th>payload</th></tr>
 *   <tr><td>0</td><td>flow flag × 16 + (x &amp; 15)</td></tr>
 *   <tr><td>1</td><td>x &gt;&gt; 4 (x &lt; 512)</td></tr>
 *   <tr><td>2</td><td>y &amp; 31</td></tr>
 *   <tr><td>3</td><td>y &gt;&gt; 5 (y &lt; 1024)</td></tr>
 * </table>
 * <p>Simulated with atlas offsets and edge padding: 0 wrong in 60,000 points, and no vanilla block
 * texture decodes as a frame.</p>
 */
public final class TextureGrid {
    /** The largest frame side the code holds. */
    public static final int MAX_FRAME = 512;

    private TextureGrid() {
    }

    /** The grid side (tiles per row) for {@code textures} tiles, water included. */
    public static int columns(int textures) {
        int columns = 1;
        while (columns * columns < textures) columns++;
        return Math.max(2, columns);
    }

    /**
     * @param tiles  each tile's frames (tile 0: water), all {@code frames} long and {@code tile} px square
     * @param flow   whether these are the flowing textures (written into the code)
     */
    public static @NotNull BufferedImage build(@NotNull List<List<BufferedImage>> tiles, int tile, int columns, int frames, boolean flow) {
        int side = columns * tile;
        if (side > MAX_FRAME) throw new IllegalArgumentException("a grid frame of " + side + " px is larger than " + MAX_FRAME);
        BufferedImage grid = new BufferedImage(side, side * frames, BufferedImage.TYPE_INT_ARGB);
        for (int frame = 0; frame < frames; frame++) {
            for (int i = 0; i < columns * columns; i++) {
                List<BufferedImage> source = i < tiles.size() ? tiles.get(i) : tiles.getFirst();
                BufferedImage image = fit(source.get(frame % source.size()), tile);
                int ox = (i % columns) * tile, oy = frame * side + (i / columns) * tile;
                for (int y = 0; y < tile; y++) {
                    for (int x = 0; x < tile; x++) grid.setRGB(ox + x, oy + y, image.getRGB(x, y) & 0xFFFFFF);
                }
            }
        }
        for (int y = 0; y < grid.getHeight(); y++) {
            int local = y % side;
            for (int x = 0; x < side; x++) {
                int alpha = 128 + code(x & ~1, local & ~1, flow, (local & 1) * 2 + (x & 1));
                grid.setRGB(x, y, (alpha << 24) | (grid.getRGB(x, y) & 0xFFFFFF));
            }
        }
        return grid;
    }

    /** The code of the texel with {@code tag} in the 2×2 block at {@code x, y} of its frame (0..127). */
    static int code(int x, int y, boolean flow, int tag) {
        return switch (tag) {
            case 0 -> (flow ? 16 : 0) + (x & 15);
            case 1 -> 32 + (x >> 4);
            case 2 -> 64 + (y & 31);
            default -> 96 + (y >> 5);
        };
    }

    /** Reads a frame back from codes (what the shader does), for tests: {origin x, origin y, flow} or {@code null}. */
    static int[] decode(int[][] alpha, int tx, int ty) {
        int a = value(alpha, tx, ty);
        if (a < 0) return null;
        int tag = a >> 5;
        int bx = tx - (tag & 1), by = ty - (tag >> 1);
        int[] v = {value(alpha, bx, by), value(alpha, bx + 1, by), value(alpha, bx, by + 1), value(alpha, bx + 1, by + 1)};
        for (int i = 0; i < 4; i++) if (v[i] < 0 || v[i] >> 5 != i) return null;
        int flow = (v[0] >> 4) & 1;
        int x = (v[0] & 15) | ((v[1] & 31) << 4);
        int y = (v[2] & 31) | ((v[3] & 31) << 5);
        if ((x & 1) != 0 || (y & 1) != 0) return null;
        int ox = bx - x, oy = by - y;
        if (value(alpha, ox, oy) != 16 * flow || value(alpha, ox + 1, oy) != 32 || value(alpha, ox, oy + 1) != 64
                || value(alpha, ox + 1, oy + 1) != 96 || value(alpha, ox + 2, oy) != 16 * flow + 2) return null;
        return new int[]{ox, oy, flow};
    }

    private static int value(int[][] alpha, int x, int y) {
        if (y < 0 || y >= alpha.length || x < 0 || x >= alpha[y].length) return -1;
        return alpha[y][x] - 128;
    }

    /** A frame scaled to the tile size (nearest neighbour keeps pixel art crisp). */
    static BufferedImage fit(BufferedImage image, int tile) {
        if (image.getWidth() == tile && image.getHeight() == tile) return image;
        BufferedImage out = new BufferedImage(tile, tile, BufferedImage.TYPE_INT_ARGB);
        var graphics = out.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(image.getScaledInstance(tile, tile, Image.SCALE_REPLICATE), 0, 0, null);
        graphics.dispose();
        return out;
    }
}
