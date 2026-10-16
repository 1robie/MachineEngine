package fr.robie.machineengine.test;

import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.machineengine.paper.fluid.pack.FluidPackBuilder;
import fr.robie.machineengine.paper.fluid.pack.ShaderPatch;
import fr.robie.machineengine.paper.fluid.pack.TextureGrid;
import fr.robie.machineengine.paper.fluid.pack.VanillaClient;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The world fluids pack (D46, docs/FLUID_TYPES_PLAN.md stage 5): grids, shader patch, builder. */
class FluidPackTest {
    private static final int NEUTRAL = 0x3F76E4;

    private static List<BufferedImage> plain(int size, int frames, int rgb) {
        List<BufferedImage> out = new ArrayList<>();
        for (int f = 0; f < frames; f++) {
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) image.setRGB(x, y, 0xFF000000 | (rgb + f));
            out.add(image);
        }
        return out;
    }

    private static int[] decode(int[][] alpha, int x, int y) throws Exception {
        Method decode = TextureGrid.class.getDeclaredMethod("decode", int[][].class, int.class, int.class);
        decode.setAccessible(true);
        return (int[]) decode.invoke(null, alpha, x, y);
    }

    @Test
    void everyTexelOfAGridFrameFindsItsFrameInAnAtlas() throws Exception {
        for (boolean flow : new boolean[]{false, true}) {
            int tile = flow ? 32 : 16, columns = 8, side = tile * columns;
            BufferedImage grid = TextureGrid.build(List.of(plain(tile, 2, 0x2040), plain(tile, 2, 0x8000)), tile, columns, 2, flow);
            assertEquals(side, grid.getWidth());
            assertEquals(2 * side, grid.getHeight(), "frames stacked downwards");
            // One frame placed at a random spot of an atlas of opaque texels, with one texel of padding.
            Random random = new Random(flow ? 2 : 1);
            int ox = 40 + random.nextInt(16), oy = 40 + random.nextInt(16);
            int[][] atlas = new int[side + 120][side + 120];
            for (int[] row : atlas) java.util.Arrays.fill(row, 255);
            for (int y = -1; y <= side; y++) {
                for (int x = -1; x <= side; x++) {
                    int sx = Math.min(Math.max(x, 0), side - 1), sy = Math.min(Math.max(y, 0), side - 1);
                    atlas[oy + y][ox + x] = grid.getRGB(sx, sy) >>> 24;
                }
            }
            for (int i = 0; i < 3000; i++) {
                int x = ox + random.nextInt(side), y = oy + random.nextInt(side);
                assertArrayEquals(new int[]{ox, oy, flow ? 1 : 0}, decode(atlas, x, y));
            }
            assertNull(decode(atlas, 5, 5), "opaque texels are not a frame");
            assertNull(decode(atlas, ox - 1, oy - 1), "padding is not a frame");
        }
        assertEquals(0x2040 & 0xFF, TextureGrid.build(List.of(plain(16, 1, 0x2040)), 16, 2, 1, false).getRGB(0, 0) & 0xFF,
                "colors stay, only alpha carries the code");
    }

    private static final String VERTEX = """
            #version 150
            in vec4 Color;
            in vec2 UV0;
            in ivec2 UV2;
            uniform sampler2D Sampler2;
            out vec4 vertexColor;
            out vec2 texCoord0;

            void main() {
                vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
                texCoord0 = UV0;
            }
            """;

    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D Sampler0;
            in vec4 vertexColor;
            in vec2 texCoord0;
            out vec4 fragColor;

            void main() {
                vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                fragColor = color;
            }
            """;

    private static ShaderPatch.Constants constants() {
        return new ShaderPatch.Constants(List.of(0xFF0000, 0x0080FF), NEUTRAL, List.of(NEUTRAL), 0.05, 2,
                new int[]{0, 1, 0}, new int[]{0, 1, 0}, new int[]{0xFFFFFF, 0xFFFFFF, 0x2A2238}, new double[]{0.7, 0.96, 0.7},
                new double[]{0, 0.2, 0}, false);
    }

    @Test
    void thePatchKeepsVanillaAndAddsTheFluids() throws Exception {
        String vertex = ShaderPatch.vertex(VERTEX, constants());
        String fragment = ShaderPatch.fragment(FRAGMENT, constants());

        assertTrue(vertex.contains("uniform sampler2D Sampler0;"), "the vertex reads the atlas");
        assertTrue(vertex.contains("meLight = (minecraft_sample_lightmap(Sampler2, UV2)).rgb;"), "the version's own light");
        assertTrue(vertex.indexOf("texCoord0 = UV0;") < vertex.indexOf("meUnmix(Color.rgb);"));
        assertTrue(vertex.contains("const vec3 ME_KEY[ME_KEY_COUNT] = vec3[](vec3(255.0, 0.0, 0.0) / 255.0, vec3(0.0, 128.0, 255.0) / 255.0);"));
        assertTrue(fragment.contains("color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;"), "vanilla stays for everything else");
        assertTrue(fragment.contains("color *= ColorModulator;"));
        assertTrue(fragment.contains("const vec3 ME_TINT[3] = vec3[](vec3(255.0, 255.0, 255.0) / 255.0, vec3(255.0, 255.0, 255.0) / 255.0, vec3(42.0, 34.0, 56.0) / 255.0);"));
        assertTrue(fragment.contains("flat in int meSprite;") && vertex.contains("flat out int meSprite;"));
        assertEquals(count(vertex, "{") , count(vertex, "}"), "balanced braces");
        assertEquals(count(fragment, "{"), count(fragment, "}"), "balanced braces");
    }

    @Test
    void explicitLocationsContinueTheVanillaOnes() throws Exception {
        String vertex = ShaderPatch.vertex(VERTEX.replace("out vec4 vertexColor;", "layout(location = 0) out vec4 vertexColor;")
                .replace("out vec2 texCoord0;", "layout(location = 1) out vec2 texCoord0;"), constants());
        String fragment = ShaderPatch.fragment(FRAGMENT.replace("in vec4 vertexColor;", "layout(location = 0) in vec4 vertexColor;")
                .replace("in vec2 texCoord0;", "layout(location = 1) in vec2 texCoord0;"), constants());

        assertTrue(vertex.contains("layout(location = 2) flat out int meSprite;"));
        assertTrue(fragment.contains("layout(location = 2) flat in int meSprite;"));
        assertTrue(fragment.contains("layout(location = 9) in vec3 meLight;"));
    }

    @Test
    void aShaderWithoutTheSkeletonIsRefused() {
        assertThrows(ShaderPatch.MismatchException.class, () -> ShaderPatch.vertex(VERTEX.replace("texCoord0 = UV0;", "texCoord0 = UV0 * 2.0;"), constants()));
        assertThrows(ShaderPatch.MismatchException.class, () -> ShaderPatch.fragment(FRAGMENT.replace("* vertexColor", "+ vertexColor"), constants()));
        assertThrows(ShaderPatch.MismatchException.class, () -> ShaderPatch.vertex(VERTEX + VERTEX.substring(VERTEX.indexOf("out vec2")), constants()),
                "twice is refused too");
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    /**
     * Dev check: patches every version's vanilla files (extracted from the official client jars by
     * {@code fetch_vanilla.py}, never in git). {@code -Dmachineengine.vanilla=<folder of versions>}; the
     * results go to {@code -Dmachineengine.vanillaOut}.
     */
    @Test
    void everyVersionsShaderIsPatched(@TempDir Path temp) throws Exception {
        String folder = System.getProperty("machineengine.vanilla");
        Assumptions.assumeTrue(folder != null && Files.isDirectory(Path.of(folder)), "no vanilla files");
        Path out = System.getProperty("machineengine.vanillaOut") != null ? Path.of(System.getProperty("machineengine.vanillaOut")) : temp;
        FluidType oil = FluidType.builder(Key.key("devcheck", "fluid/oil")).color(0x2A2238).placeable()
                .still((size, context) -> context.vanilla("block/lava_still", size)).flow((size, context) -> context.vanilla("block/lava_flow", size)).build();
        FluidType brine = FluidType.builder(Key.key("devcheck", "fluid/brine")).color(0xC8B88A).placeable().build();
        FluidKeys keys = FluidKeys.of(List.of(oil, brine), FluidType::look, NEUTRAL, true);
        try (Stream<Path> versions = Files.list(Path.of(folder))) {
            for (Path version : versions.filter(Files::isDirectory).sorted().toList()) {
                try (VanillaClient client = VanillaClient.open(version)) {
                    Path pack = out.resolve(version.getFileName().toString());
                    FluidPackBuilder.Result result = FluidPackBuilder.build(client, keys, FluidType::look,
                            new FluidPackBuilder.Settings(NEUTRAL, true, false, 8), out.resolve("none"), pack);
                    assertEquals(2, result.looks(), version.toString());
                    assertTrue(Files.isRegularFile(pack.resolve("resourcepack/" + FluidPackBuilder.OVERLAY + "/assets/minecraft/shaders/core/"
                            + result.shader() + ".vsh")), version.toString());
                }
            }
        }
    }

    @Test
    void aBucketTextureRecolorsOnlyTheWater() throws Exception {
        BufferedImage water = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        water.setRGB(0, 0, 0xFF3F76E4); // water: blue
        water.setRGB(1, 0, 0xFFA8A8A8); // the bucket: grey metal
        Method bucket = FluidPackBuilder.class.getDeclaredMethod("bucket", BufferedImage.class, int.class);
        bucket.setAccessible(true);
        BufferedImage out = (BufferedImage) bucket.invoke(null, water, 0xC8B88A);

        int r = (out.getRGB(0, 0) >> 16) & 255, b = out.getRGB(0, 0) & 255;
        assertTrue(r > b, "the water takes the fluid's (cream) color");
        assertEquals(0xFFA8A8A8, out.getRGB(1, 0), "the bucket stays");
        assertEquals(Key.key("machineengine", "item/fluid_bucket/mekanized/fluid/brine"),
                FluidPackBuilder.bucketTexture(Key.key("mekanized", "fluid/brine")));
    }

    @Test
    void aLookWithoutTextureIsWaterTinted() {
        FluidLook look = FluidKeys.resolved(FluidLook.TINTED_WATER, 0x2A2238);
        assertEquals(0x2A2238, look.tint().intValue());
    }
}
