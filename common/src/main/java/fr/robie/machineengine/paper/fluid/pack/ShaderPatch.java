package fr.robie.machineengine.paper.fluid.pack;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds world fluids to a version's vanilla water shader (docs/FLUID_TYPES_PLAN.md §7.1). Every version
 * from 1.20.6 to 26.3 has the same skeleton, which is all this relies on:
 * <ul>
 *   <li>vertex: {@code out vec2 texCoord0;}, {@code vertexColor = Color * <light>;}, {@code texCoord0 = UV0;};</li>
 *   <li>fragment: {@code in vec2 texCoord0;} and {@code vec4 color = <sample> * vertexColor[ * ColorModulator];}.</li>
 * </ul>
 * The rest is found in the file: explicit varying locations (26.3), the lightmap expression, the alpha-only
 * pass without a lightmap (26.3 {@code OIT_ALPHA_ONLY}). A shader that doesn't match is refused, never
 * guessed: a shader that fails to compile disables the whole resource pack.
 *
 * <p>The vertex finds the grid frame from the texture's alpha code ({@link TextureGrid}), splits the
 * water color into a key and the neutral water, and passes the fluid and its share on; the fragment
 * draws the fluid's tile with its tint, opacity and glow.</p>
 */
public final class ShaderPatch {
    private static final Pattern OUT_TEXCOORD = Pattern.compile("^(layout\\(location = \\d+\\) )?out vec2 texCoord0;\\R", Pattern.MULTILINE);
    private static final Pattern IN_TEXCOORD = Pattern.compile("^(layout\\(location = \\d+\\) )?in vec2 texCoord0;\\R", Pattern.MULTILINE);
    private static final Pattern LIGHT = Pattern.compile("vertexColor = Color \\* (.+);");
    private static final Pattern MAIN_TEXCOORD = Pattern.compile("^(\\s*)texCoord0 = UV0;\\R", Pattern.MULTILINE);
    private static final Pattern COLOR = Pattern.compile("^(\\s*)vec4 color = (.+?) \\* vertexColor( \\* ColorModulator)?;\\R", Pattern.MULTILINE);
    private static final Pattern LOCATION = Pattern.compile("layout\\(location = (\\d+)\\) out ");
    private static final Pattern SAMPLER0 = Pattern.compile("uniform sampler2D Sampler0;");

    /** What the patch needs to know about the fluids (index 0 is plain water, 1..n the keys). */
    public record Constants(@NotNull List<Integer> keys, int neutral, @NotNull List<Integer> backgrounds, double cut,
                            int columns, int @NotNull [] still, int @NotNull [] flow, int @NotNull [] tint,
                            double @NotNull [] alpha, double @NotNull [] glow, boolean sharp) {
        public Constants {
            int n = keys.size() + 1;
            if (keys.isEmpty() || backgrounds.isEmpty() || still.length != n || flow.length != n || tint.length != n
                    || alpha.length != n || glow.length != n) throw new IllegalArgumentException("constants of " + keys.size() + " keys don't line up");
        }
    }

    /** A vanilla shader the patch does not fit. */
    public static final class MismatchException extends Exception {
        MismatchException(String message) {
            super(message);
        }
    }

    private ShaderPatch() {
    }

    public static @NotNull String vertex(@NotNull String vanilla, @NotNull Constants constants) throws MismatchException {
        Matcher light = once(LIGHT, vanilla, "vertexColor = Color * <light>;");
        String lightExpression = light.group(1);
        int location = nextLocation(vanilla);
        StringBuilder declarations = new StringBuilder("\n// ---- MachineEngine world fluids (docs/FLUID_TYPES_PLAN.md §7) ----\n");
        if (!SAMPLER0.matcher(vanilla).find()) declarations.append("uniform sampler2D Sampler0;\n");
        String[][] varyings = varyings();
        for (int i = 0; i < varyings.length; i++) {
            declarations.append(location < 0 ? "" : "layout(location = " + (location + i) + ") ").append(varyings[i][0]).append("out ")
                    .append(varyings[i][1]).append(";\n");
        }
        declarations.append(vertexConstants(constants)).append(VERTEX_FUNCTIONS).append("// ---- end world fluids ----\n");
        String withDeclarations = insertAfter(OUT_TEXCOORD, vanilla, "out vec2 texCoord0;", declarations.toString());
        Matcher main = once(MAIN_TEXCOORD, withDeclarations, "texCoord0 = UV0;");
        String body = VERTEX_MAIN.replace("@LIGHT@", lightExpression).replace("\n", "\n" + main.group(1)).stripTrailing();
        return withDeclarations.substring(0, main.end()) + main.group(1) + body.strip() + "\n" + withDeclarations.substring(main.end());
    }

    public static @NotNull String fragment(@NotNull String vanilla, @NotNull Constants constants) throws MismatchException {
        int location = nextLocation(vanilla.replace(") in ", ") out "));
        StringBuilder declarations = new StringBuilder("\n// ---- MachineEngine world fluids (docs/FLUID_TYPES_PLAN.md §7) ----\n");
        String[][] varyings = varyings();
        for (int i = 0; i < varyings.length; i++) {
            declarations.append(location < 0 ? "" : "layout(location = " + (location + i) + ") ").append(varyings[i][0]).append("in ")
                    .append(varyings[i][1]).append(";\n");
        }
        declarations.append(fragmentConstants(constants)).append("// ---- end world fluids ----\n");
        String withDeclarations = insertAfter(IN_TEXCOORD, vanilla, "in vec2 texCoord0;", declarations.toString());
        Matcher color = once(COLOR, withDeclarations, "vec4 color = <sample> * vertexColor;");
        String indent = color.group(1);
        String modulator = color.group(3) == null ? "" : color.group(3);
        String branch = FRAGMENT_COLOR.replace("@VANILLA@", color.group(2) + " * vertexColor" + modulator)
                .replace("@MODULATE@", modulator.isEmpty() ? "" : "\n        color *= ColorModulator;")
                .replace("\n", "\n" + indent);
        return withDeclarations.substring(0, color.start()) + indent + branch.strip() + "\n" + withDeclarations.substring(color.end());
    }

    /** {qualifier, declaration} of what the vertex passes to the fragment. */
    private static String[][] varyings() {
        return new String[][]{
                {"flat ", "int meSprite"},       // 0: not the grid; 1: still; 2: flowing
                {"flat ", "vec2 meOrigin"},      // the grid frame's corner in the atlas (texels)
                {"", "vec2 meRel"},              // 0..1 inside the frame
                {"", "float meShare"},           // how much of a fluid's key is in the color
                {"", "float meKeyShare"},        // fluid number × share (interpolates to the number)
                {"", "vec3 meBg"},               // the water around, shade kept
                {"", "float meShade"},           // face shade and darkening (the key's and the water's amount)
                {"", "vec3 meLight"}             // lightmap
        };
    }

    private static int nextLocation(String source) {
        Matcher matcher = LOCATION.matcher(source);
        int max = -1;
        while (matcher.find()) max = Math.max(max, Integer.parseInt(matcher.group(1)));
        return max < 0 ? -1 : max + 1;
    }

    private static Matcher once(Pattern pattern, String source, String what) throws MismatchException {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) throw new MismatchException("no \"" + what + "\" in the vanilla shader");
        int start = matcher.start();
        if (matcher.find()) throw new MismatchException("\"" + what + "\" is in the vanilla shader more than once");
        matcher.reset();
        matcher.find();
        if (matcher.start() != start) throw new IllegalStateException();
        return matcher;
    }

    private static String insertAfter(Pattern pattern, String source, String what, String insert) throws MismatchException {
        Matcher matcher = once(pattern, source, what);
        return source.substring(0, matcher.end()) + insert + source.substring(matcher.end());
    }

    // ---------------------------------------------------------------- generated constants

    private static String vertexConstants(Constants c) {
        StringBuilder out = new StringBuilder();
        out.append("#define ME_KEY_COUNT ").append(c.keys().size()).append('\n');
        out.append("#define ME_BG_COUNT ").append(c.backgrounds().size()).append('\n');
        out.append("#define ME_GRID ").append(c.columns()).append('\n');
        out.append("#define ME_CUT ").append(number(c.cut())).append('\n');
        out.append("#define ME_PLAIN_TOL 0.01\n#define ME_KEY_TOL 0.015\n");
        out.append("const vec3 ME_KEY[ME_KEY_COUNT] = vec3[](").append(colors(c.keys())).append(");\n");
        out.append("const vec3 ME_NEUTRAL = ").append(color(c.neutral())).append(";\n");
        out.append("const vec3 ME_BG[ME_BG_COUNT] = vec3[](").append(colors(c.backgrounds())).append(");\n");
        return out.toString();
    }

    private static String fragmentConstants(Constants c) {
        int n = c.keys().size() + 1;
        StringBuilder out = new StringBuilder();
        out.append("#define ME_GRID ").append(c.columns()).append('\n');
        out.append("#define ME_CUT ").append(number(c.cut())).append('\n');
        out.append("const int ME_STILL[").append(n).append("] = int[](").append(ints(c.still())).append(");\n");
        out.append("const int ME_FLOW[").append(n).append("] = int[](").append(ints(c.flow())).append(");\n");
        StringBuilder tints = new StringBuilder();
        for (int i = 0; i < n; i++) tints.append(i == 0 ? "" : ", ").append(color(c.tint()[i]));
        out.append("const vec3 ME_TINT[").append(n).append("] = vec3[](").append(tints).append(");\n");
        out.append("const float ME_ALPHA[").append(n).append("] = float[](").append(numbers(c.alpha())).append(");\n");
        out.append("const float ME_GLOW[").append(n).append("] = float[](").append(numbers(c.glow())).append(");\n");
        out.append("// Where a fluid meets water: ").append(c.sharp() ? "sharp (more than half decides)" : "soft (fades with its share)").append(".\n");
        out.append(c.sharp() ? "#define ME_EDGE(w) ((w) > 0.5 ? 1.0 : 0.0)\n"
                : "#define ME_EDGE(w) clamp(((w) - ME_CUT) / (0.9 - ME_CUT), 0.0, 1.0)\n");
        // Full resolution only: the tiles sit side by side in one sprite, and mipmaps (and RGSS) would
        // blend neighbouring tiles at a distance.
        out.append("#define ME_SAMPLE(uv) textureLod(Sampler0, uv, 0.0)\n");
        return out.toString();
    }

    private static String colors(List<Integer> colors) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < colors.size(); i++) out.append(i == 0 ? "" : ", ").append(color(colors.get(i)));
        return out.toString();
    }

    private static String color(int rgb) {
        return "vec3(" + ((rgb >> 16) & 255) + ".0, " + ((rgb >> 8) & 255) + ".0, " + (rgb & 255) + ".0) / 255.0";
    }

    private static String ints(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) out.append(i == 0 ? "" : ", ").append(values[i]);
        return out.toString();
    }

    private static String numbers(double[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) out.append(i == 0 ? "" : ", ").append(number(values[i]));
        return out.toString();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    // ---------------------------------------------------------------- GLSL

    private static final String VERTEX_FUNCTIONS = """
            int meAlpha(ivec2 p) {
                return int(texelFetch(Sampler0, p, 0).a * 255.0 + 0.5) - 128;
            }

            bool meInside(ivec2 p, ivec2 size) {
                return p.x >= 0 && p.y >= 0 && p.x < size.x && p.y < size.y;
            }

            // The grid frame texel t belongs to (TextureGrid): 0 none, 1 still, 2 flowing; its corner in origin.
            int meFind(ivec2 t, ivec2 size, out ivec2 origin) {
                origin = ivec2(0);
                if (!meInside(t, size)) return 0;
                int a = meAlpha(t);
                if (a < 0) return 0;
                int tag = a >> 5;
                ivec2 base = t - ivec2(tag & 1, tag >> 1);
                if (!meInside(base, size) || !meInside(base + ivec2(1, 1), size)) return 0;
                int v0 = meAlpha(base);
                int v1 = meAlpha(base + ivec2(1, 0));
                int v2 = meAlpha(base + ivec2(0, 1));
                int v3 = meAlpha(base + ivec2(1, 1));
                if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0 || (v0 >> 5) != 0 || (v1 >> 5) != 1 || (v2 >> 5) != 2 || (v3 >> 5) != 3) return 0;
                int flow = (v0 >> 4) & 1;
                int x = (v0 & 15) | ((v1 & 31) << 4);
                int y = (v2 & 31) | ((v3 & 31) << 5);
                if ((x & 1) != 0 || (y & 1) != 0) return 0;
                origin = base - ivec2(x, y);
                // The frame's corner must read (0, 0) and the block to its right (2, 0): rejects padding copies.
                if (!meInside(origin, size) || !meInside(origin + ivec2(2, 1), size)) return 0;
                if (meAlpha(origin) != 16 * flow || meAlpha(origin + ivec2(1, 0)) != 32 || meAlpha(origin + ivec2(0, 1)) != 64
                        || meAlpha(origin + ivec2(1, 1)) != 96 || meAlpha(origin + ivec2(2, 0)) != 16 * flow + 2) return 0;
                return flow + 1;
            }

            // Best c = x * A + y * B with x, y >= 0; returns the error relative to c.
            float meFit(vec3 c, vec3 A, vec3 B, out float x, out float y) {
                float aa = dot(A, A);
                float ab = dot(A, B);
                float bb = dot(B, B);
                float ac = dot(A, c);
                float bc = dot(B, c);
                float det = aa * bb - ab * ab;
                if (abs(det) < 1e-6) {
                    x = 0.0;
                    y = bc / bb;
                } else {
                    x = (ac * bb - bc * ab) / det;
                    y = (bc * aa - ac * ab) / det;
                }
                if (x < 0.0) { x = 0.0; y = max(bc / bb, 0.0); }
                if (y < 0.0) { y = 0.0; x = max(ac / aa, 0.0); }
                return length(c - x * A - y * B) / max(length(c), 1e-4);
            }

            // Splits a water color (simulated on the whole chain, plan §4.3, §4.4): plain water (one biome
            // water, or a blend of two) stays as it is; a key blended with the neutral water is that fluid
            // with its share; anything else is neutral water at the same brightness, never a guessed fluid.
            void meUnmix(vec3 c) {
                float len = max(length(c), 1e-4);
                float x;
                float y;
                for (int i = 0; i < ME_BG_COUNT; i++) {
                    vec3 w = ME_BG[i];
                    if (length(c - (dot(w, c) / dot(w, w)) * w) / len < ME_PLAIN_TOL) return;
                    for (int j = i + 1; j < ME_BG_COUNT; j++) {
                        if (meFit(c, w, ME_BG[j], x, y) < ME_PLAIN_TOL) return;
                    }
                }
                float best = ME_KEY_TOL;
                int bestK = 0;
                float bestShare = 0.0;
                float bestShade = 1.0;
                for (int k = 0; k < ME_KEY_COUNT; k++) {
                    float r = meFit(c, ME_KEY[k], ME_NEUTRAL, x, y);
                    if (r < best && x > 0.0) {
                        best = r;
                        bestK = k + 1;
                        bestShare = x / (x + y);
                        bestShade = x + y;
                    }
                }
                if (bestK > 0 && bestShare >= ME_CUT) {
                    meShare = bestShare;
                    meKeyShare = float(bestK) * bestShare;
                    meShade = bestShade;
                    meBg = bestShade * ME_NEUTRAL;
                } else {
                    meBg = ME_NEUTRAL * (dot(c, vec3(1.0)) / dot(ME_NEUTRAL, vec3(1.0)));
                }
            }
            """;

    private static final String VERTEX_MAIN = """
            // ---- MachineEngine world fluids ----
            meSprite = 0;
            meOrigin = vec2(0.0);
            meRel = vec2(0.0);
            meShare = 0.0;
            meKeyShare = 0.0;
            meBg = Color.rgb;
            meShade = 1.0;
            #ifdef OIT_ALPHA_ONLY
            meLight = vec3(1.0);
            #else
            meLight = (@LIGHT@).rgb;
            #endif
            ivec2 meSize = textureSize(Sampler0, 0);
            vec2 meTexel = UV0 * vec2(meSize);
            // The vertex sits on a texel edge: one of the four texels around it is inside the sprite.
            for (int i = 0; i < 4; i++) {
                vec2 meOffset = vec2((i & 1) == 0 ? 0.5 : -0.5, i < 2 ? 0.5 : -0.5);
                ivec2 meCorner;
                int meFound = meFind(ivec2(floor(meTexel + meOffset)), meSize, meCorner);
                if (meFound > 0) {
                    meSprite = meFound;
                    meOrigin = vec2(meCorner);
                    meRel = (meTexel - meOrigin) / (float(ME_GRID) * (meFound == 2 ? 32.0 : 16.0));
                    meUnmix(Color.rgb);
                    break;
                }
            }
            // ---- end world fluids ----
            """;

    private static final String FRAGMENT_COLOR = """
            vec4 color;
            // ---- MachineEngine world fluids ----
            if (meSprite > 0) {
                int meK = meShare > 0.001 ? int(meKeyShare / meShare + 0.5) : 0;
                float meT = meK == 0 ? 0.0 : ME_EDGE(meShare);
                float meTile = meSprite == 2 ? 32.0 : 16.0;
                vec2 meAtlas = vec2(textureSize(Sampler0, 0));
                vec2 meLocal = clamp(meRel * meTile, vec2(0.5), vec2(meTile - 0.5));
                vec4 meWater = vec4(0.0);
                if (meT < 1.0) {
                    vec3 meTex = ME_SAMPLE((meOrigin + meLocal) / meAtlas).rgb;
                    meWater = vec4(meTex * meBg * meLight, ME_ALPHA[0] * vertexColor.a);
                }
                if (meT > 0.0) {
                    int meIndex = meSprite == 2 ? ME_FLOW[meK] : ME_STILL[meK];
                    vec2 meCorner = vec2(float(meIndex % ME_GRID), float(meIndex / ME_GRID)) * meTile;
                    vec3 meFluidTex = ME_SAMPLE((meOrigin + meCorner + meLocal) / meAtlas).rgb;
                    vec3 meLit = max(meLight, vec3(ME_GLOW[meK]));
                    vec4 meFluid = vec4(meFluidTex * ME_TINT[meK] * meShade * meLit, ME_ALPHA[meK] * vertexColor.a);
                    color = meT >= 1.0 ? meFluid : mix(meWater, meFluid, meT);
                } else {
                    color = meWater;
                }@MODULATE@
            } else {
            // ---- end world fluids ----
                color = @VANILLA@;
            }
            """;
}
