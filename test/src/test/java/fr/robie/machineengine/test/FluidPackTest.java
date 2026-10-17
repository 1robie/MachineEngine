package fr.robie.machineengine.test;

import fr.robie.machineengine.api.resource.fluid.FluidLook;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.paper.fluid.client.FluidKeys;
import fr.robie.machineengine.paper.fluid.pack.FluidPackBuilder;
import fr.robie.machineengine.paper.fluid.pack.ShaderPatch;
import fr.robie.machineengine.paper.fluid.pack.SodiumShaders;
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
                new double[]{0, 0.2, 0}, false, true);
    }

    @Test
    void thePatchKeepsVanillaAndAddsTheFluids() throws Exception {
        String vertex = ShaderPatch.vertex(VERTEX, constants());
        String fragment = ShaderPatch.fragment(FRAGMENT, constants());

        assertTrue(vertex.contains("uniform sampler2D Sampler0;"), "the vertex reads the atlas");
        assertTrue(vertex.contains("meLight = (minecraft_sample_lightmap(Sampler2, UV2)).rgb;"), "the version's own light");
        assertTrue(vertex.indexOf("texCoord0 = UV0;") < vertex.indexOf("meUnmix(Color.rgb);"));
        assertTrue(vertex.contains("const vec3 ME_KEY[ME_KEY_COUNT] = vec3[](vec3(255.0, 0.0, 0.0) / 255.0, vec3(0.0, 128.0, 255.0) / 255.0);"));
        assertTrue(fragment.contains("color = (texture(Sampler0, texCoord0)) * vertexColor * ColorModulator;"), "vanilla stays for everything else");
        assertTrue(fragment.contains("color *= ColorModulator;"));
        assertTrue(fragment.contains("const vec3 ME_TINT[3] = vec3[](vec3(255.0, 255.0, 255.0) / 255.0, vec3(255.0, 255.0, 255.0) / 255.0, vec3(42.0, 34.0, 56.0) / 255.0);"));
        assertTrue(fragment.contains("in float meSprite;") && vertex.contains("out float meSprite;"));
        assertTrue(!vertex.contains("flat ") && !fragment.contains("flat "), "no flat: GLSL 3.30 has no layout-then-flat order");
        assertEquals(count(vertex, "{") , count(vertex, "}"), "balanced braces");
        assertEquals(count(fragment, "{"), count(fragment, "}"), "balanced braces");
    }

    @Test
    void explicitLocationsContinueTheVanillaOnes() throws Exception {
        String vertex = ShaderPatch.vertex(VERTEX.replace("out vec4 vertexColor;", "layout(location = 0) out vec4 vertexColor;")
                .replace("out vec2 texCoord0;", "layout(location = 1) out vec2 texCoord0;"), constants());
        String fragment = ShaderPatch.fragment(FRAGMENT.replace("in vec4 vertexColor;", "layout(location = 0) in vec4 vertexColor;")
                .replace("in vec2 texCoord0;", "layout(location = 1) in vec2 texCoord0;"), constants());

        assertTrue(vertex.contains("layout(location = 2) out float meSprite;"));
        assertTrue(fragment.contains("layout(location = 2) in float meSprite;"));
        assertTrue(fragment.contains("layout(location = 9) in vec3 meLight;"));
    }

    @Test
    void aShaderWithoutTheSkeletonIsRefused() {
        assertThrows(ShaderPatch.MismatchException.class, () -> ShaderPatch.vertex(VERTEX.replace("    texCoord0 = UV0;\n", ""), constants()),
                "no texCoord0 assignment");
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
        List<Path> versions;
        try (Stream<Path> listed = Files.list(Path.of(folder))) {
            versions = listed.filter(Files::isDirectory).sorted().toList();
        }
        for (boolean grid : new boolean[]{true, false}) {
            for (Path version : versions) {
                try (VanillaClient client = VanillaClient.open(version)) {
                    Path pack = out.resolve(grid ? "grid" : "tint").resolve(version.getFileName().toString());
                    FluidPackBuilder.Result result = FluidPackBuilder.build(client, keys, FluidType::look,
                            new FluidPackBuilder.Settings(NEUTRAL, true, false, 8, grid, true), out.resolve("none"), pack, sodium(version.getFileName().toString()));
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

    /** Sodium's shaders of a version, from {@code -Dmachineengine.sodium=<folder of versions>} (fetch_sodium.py); null without. */
    private static SodiumShaders sodium(String version) throws Exception {
        String folder = System.getProperty("machineengine.sodium");
        if (folder == null) return null;
        Path root = Path.of(folder, version);
        if (!Files.isRegularFile(root.resolve(SodiumShaders.VERTEX))) return null;
        String name = Files.readString(root.resolve("version.txt")).lines().findFirst().orElse("?");
        if (!SodiumShaders.overridable(name)) return null; // as the builder: before 0.9 a pack can't replace them
        java.util.Map<String, String> includes = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(root.resolve(SodiumShaders.INCLUDES))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) includes.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file));
        }
        return new SodiumShaders(name, Files.readString(root.resolve(SodiumShaders.VERTEX)), Files.readString(root.resolve(SodiumShaders.FRAGMENT)), includes);
    }

    @Test
    void modelsThatDrawVanillaWaterAreRedirectedInTheOverlay(@TempDir Path temp) throws Exception {
        Path vanilla = temp.resolve("vanilla/assets/minecraft/textures/block");
        Files.createDirectories(vanilla);
        Files.write(vanilla.resolve("water_still.png"), new byte[]{1});
        Files.write(vanilla.resolve("water_still.png.mcmeta"), new byte[]{2});
        Files.write(vanilla.resolve("water_flow.png"), new byte[]{3});
        Path models = temp.resolve("resources/mekanized/resourcepack/assets/mekanized/models/gui/bar/fluid");
        Files.createDirectories(models);
        Files.writeString(models.resolve("0_1.json"), "{\"textures\":{\"fill\":\"minecraft:block/water_still\",\"particle\":\"block/water_flow\"}}");
        Files.writeString(models.resolve("other.json"), "{\"textures\":{\"all\":\"mekanized:block/steel\"}}");
        Path overlay = temp.resolve("overlay");
        Method plain = FluidPackBuilder.class.getDeclaredMethod("plainWater", VanillaClient.class, Path.class, Path.class);
        plain.setAccessible(true);
        int count;
        try (VanillaClient client = VanillaClient.open(temp.resolve("vanilla"))) {
            count = (int) plain.invoke(null, client, temp.resolve("resources"), overlay);
        }

        assertEquals(1, count, "only the model drawing water");
        assertEquals("{\"textures\":{\"fill\":\"machineengine:block/water_still\",\"particle\":\"machineengine:block/water_flow\"}}",
                Files.readString(overlay.resolve("assets/mekanized/models/gui/bar/fluid/0_1.json")));
        assertTrue(Files.isRegularFile(overlay.resolve("assets/machineengine/textures/block/water_still.png")));
        assertTrue(Files.isRegularFile(overlay.resolve("assets/machineengine/textures/block/water_still.png.mcmeta")));
        assertTrue(Files.notExists(overlay.resolve("assets/mekanized/models/gui/bar/fluid/other.json")));
    }

    @Test
    void aShaderImportingWhatNobodyHasIsRefused(@TempDir Path temp) throws Exception {
        Path vanilla = temp.resolve("vanilla/assets/minecraft/shaders/include");
        Files.createDirectories(vanilla);
        Files.writeString(vanilla.resolve("fog.glsl"), "");
        Path blocks = temp.resolve("overlay/assets/sodium/shaders/blocks");
        Files.createDirectories(blocks);
        Files.writeString(blocks.resolve("block_layer_opaque.vsh"), "#version 330\n#moj_import <minecraft:fog.glsl>\n#moj_import <sodium:globals.glsl>\n");
        Method check = FluidPackBuilder.class.getDeclaredMethod("checkImports", VanillaClient.class, Path.class);
        check.setAccessible(true);
        try (VanillaClient client = VanillaClient.open(temp.resolve("vanilla"))) {
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> check.invoke(null, client, temp.resolve("overlay")));
            assertTrue(thrown.getCause().getMessage().contains("sodium:globals.glsl"), thrown.getCause().getMessage());
            Path include = temp.resolve("overlay/assets/sodium/shaders/include");
            Files.createDirectories(include);
            Files.writeString(include.resolve("globals.glsl"), "");
            check.invoke(null, client, temp.resolve("overlay")); // now it is there
        }
    }

    @Test
    void sodiumsTernarySampleKeepsItsTint() throws Exception {
        String sodium = """
                #version 330 core
                in vec4 v_Color;
                in vec2 v_TexCoord;
                uniform sampler2D u_BlockTex;
                out vec4 fragColor;

                void main() {
                    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);
                    color *= v_Color; // Apply per-vertex color modulator
                    fragColor = color;
                }
                """;
        String fragment = ShaderPatch.fragment(sodium, constants(), ShaderPatch.Dialect.SODIUM);
        assertTrue(fragment.contains("color = (u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize)) * v_Color;"),
                "the tint applies to both samples (gray leaves and grass otherwise)");
    }

    @Test
    void autoUsesTheGridWhereSodiumTakesTheShader() throws Exception {
        Method grid = fr.robie.machineengine.paper.fluid.pack.FluidPackService.class.getDeclaredMethod("grid",
                fr.robie.machineengine.api.resource.fluid.WorldFluidConfig.Textures.class, String.class);
        grid.setAccessible(true);
        var auto = fr.robie.machineengine.api.resource.fluid.WorldFluidConfig.Textures.AUTO;
        assertTrue((boolean) grid.invoke(null, auto, "26.2"));
        assertTrue((boolean) grid.invoke(null, auto, "26.3"));
        assertTrue((boolean) grid.invoke(null, auto, "27.1"));
        assertTrue(!(boolean) grid.invoke(null, auto, "26.1"));
        assertTrue(!(boolean) grid.invoke(null, auto, "1.21.11"));
        assertTrue(!(boolean) grid.invoke(null, auto, "1.20.6"));
        assertTrue((boolean) grid.invoke(null, fr.robie.machineengine.api.resource.fluid.WorldFluidConfig.Textures.GRID, "1.20.6"));
        assertTrue(!(boolean) grid.invoke(null, fr.robie.machineengine.api.resource.fluid.WorldFluidConfig.Textures.TINT, "26.3"));
    }

    @Test
    void tintOnlyKnowsWaterByItsMarkerAndKeepsTheGamesSampling() throws Exception {
        ShaderPatch.Constants tint = new ShaderPatch.Constants(List.of(0xFF0000, 0x0080FF), NEUTRAL, List.of(NEUTRAL), 0.05, 0,
                new int[3], new int[3], new int[]{0xFFFFFF, 0xFFFFFF, 0x2A2238}, new double[]{0.7, 0.96, 0.7}, new double[3], false, false);
        String vertex = ShaderPatch.vertex(VERTEX, tint);
        String fragment = ShaderPatch.fragment(FRAGMENT, tint);
        assertTrue(vertex.contains("#define ME_GRID_MODE 0") && vertex.contains("#define ME_MARKER " + ShaderPatch.TINT_MARKER));
        assertTrue(fragment.contains("vec4 meBase = texture(Sampler0, texCoord0);"), "the fluid is the game's own sample, tinted");
        assertTrue(!fragment.contains("ME_SAMPLE((meOrigin"), "no grid tile is read");
    }

    @Test
    void onlySodiumFromZeroNineTakesShadersFromPacks() {
        assertTrue(SodiumShaders.overridable("mc26.2-0.9.2-fabric"));
        assertTrue(SodiumShaders.overridable("mc26.3-0.9.3-beta.1-fabric"));
        assertTrue(SodiumShaders.overridable("1.0.0"));
        assertTrue(!SodiumShaders.overridable("mc26.1.1-0.8.9-fabric"));
        assertTrue(!SodiumShaders.overridable("mc1.21.4-0.6.13-fabric"));
    }

    @Test
    void aLookWithoutTextureIsWaterTinted() {
        FluidLook look = FluidKeys.resolved(FluidLook.TINTED_WATER, 0x2A2238);
        assertEquals(0x2A2238, look.tint().intValue());
    }
}
