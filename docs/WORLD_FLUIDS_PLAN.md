# World fluids plan (2026-10-15, revision 3: implementation)

The owner's request (2026-10-14) is custom fluids placed in the world, such as brine, heavy water or oil, that players see on vanilla clients. Each fluid needs:
- **its own texture**, not just a color;
- **its own viscosity**;
- **its own flow**.

A core shader in the server resource pack may be used.

**Revisions:**
- rev 1: color only;
- rev 2: textures and viscosity, after the owner said a Paper server already does it;
- rev 3 (this one): the full implementation, with modules, classes, data formats, packets, shader, stages and tests.

Status: plan only, nothing implemented. The owner's answers are needed before stage 0 (see the end).

**Notation:** every class is named by module and package. Marks:
- **[V]**: checked in a source, or in CE's source at `.craftengine/source`;
- **[G]**: an inference, proved or disproved by stage 0;
- **[VERIFY]**: an open item that a named stage settles.

---

## 1. Summary

A custom fluid is **vanilla water**, both on the server and on the client. The engine remembers which fluid each water block is. Four layers sit on top, and each one works without the ones after it:

| Layer | Where | What it does | Without it |
|---|---|---|---|
| 1. Tracker | server | which water block is which fluid; flow, buckets, mixing, pump/plenisher | — |
| 2. Key biomes | client, through packets | every fluid cell gets a client-side biome whose `water_color` is the fluid's **key** and whose `water_fog_color` is its fog | the fluid looks like water |
| 3. Shader | resource pack | `core/terrain` (or `rendertype_translucent`) recognizes the key and draws the fluid's own animated texture and opacity | key tint only (Sodium, Iris, OptiFine, `exclude-core-shaders`) |
| 4. Viscosity | server | player attribute modifiers, velocity damping for players and other entities, per-fluid spreading speed | water physics |

```
            server                                  client (vanilla + server pack)
 ┌───────────────────────────┐   chunk packets   ┌───────────────────────────────┐
 │ FluidTracker (chunk PDC)  │──(CE BiomeRemapper)──▶ biome palette: key biomes   │
 │ FluidFlow / Buckets / Mix │   registry data   │ water tint = key color         │
 │ ViscosityTask             │──(our handler)───▶│ terrain.vsh: key → fluid id    │
 │ KeyPalette / KeyBiomes    │   resource pack   │ terrain.fsh: tile, frame, alpha│
 │ FluidPackBuilder ─────────┼──(CE pack)───────▶│ water_still/flow = tile grid   │
 └───────────────────────────┘                   └───────────────────────────────┘
```

## 2. What we know (research, 2026-10-14/15)

### Others

- **No public plugin or pack has textured, viscous custom fluids.**
- **ItemsAdder "liquids"** [V] ([docs](https://wiki.itemsadder.com/adding-content/blocks/liquids), [FAQ](https://wiki.itemsadder.com/faq/liquids-issues)) are water recolored by a biome color sent to the client:
  - no spreading;
  - colors bleed between cells;
  - Biome Blend must stay at 5×5 or lower.
- **Core shaders can single out water by biome color** [V]: McTsts' *Wavy Water* ([mc-core-shaders](https://github.com/McTsts/mc-core-shaders)).
- **A vertex shader can read atlas pixels** [V]: [objmc](https://github.com/Godlander/objmc) does it, using `gl_VertexID % 4` to find the quad corner.

### Client facts

- **The client hardcodes the water sprites** (`block/water_still`, `block/water_flow`, `block/water_overlay`). A pack can't give one water block a different sprite, but a shader can redirect the UVs.
- **Lava is never tinted**, so it can't carry a key.
- **The vertex color of water** is the biome water color × the face shade [G]:

  | Face | Shade |
  |---|---|
  | top | 1.0 |
  | north/south | 0.8 |
  | east/west | 0.6 |
  | bottom | 0.5 |

  Fluids get no ambient occlusion. Light arrives in `UV2`.
- **Biomes are sent per 4×4×4 cell.** Biome Blend (1×1 to 15×15) averages colors across cells.
- **Custom biomes reach the client** in `ClientboundRegistryDataPacket` (configuration phase, 1.20.5+).
- **[VERIFY stage 0]** whether `water_color` / `water_fog_color` are still biome `effects` on 26.x, or moved into environment attributes in 1.21.11.

### Movement attributes

Movement attributes are synced to the client and predicted by it [V] ([Attribute](https://minecraft.wiki/w/Attribute)):

| Attribute | Since |
|---|---|
| `movement_speed` | always |
| `gravity` | 1.20.5 |
| `jump_strength` | 1.20.5 |
| `water_movement_efficiency` | 1.21 |
| `oxygen_bonus` | 1.21 |
| `submerged_mining_speed` | 1.21 |

Swimming acceleration in water is fixed (0.02) unless `water_movement_efficiency` > 0. So attributes can make a fluid **faster** than water, **heavier** (it sinks) and **harder to jump out of**, but never **slower to swim through** [G]. Thick fluids need velocity damping from the server (see §5.8).

### Shader formats

Mojang says shader replacement is not supported, and a shader that fails to compile disables the whole pack.

| Versions | Water shader | Notes |
|---|---|---|
| 1.20.6 – 1.21.1 | `core/rendertype_translucent` + JSON | GLSL 150, plain uniforms |
| 1.21.2 – 1.21.4 | `core/terrain` (+ JSON) | defines |
| 1.21.5 – 1.21.8 | `core/terrain` | no JSON (1.21.5); uniform blocks (1.21.6) |
| 1.21.9 – 1.21.10 | `core/terrain` | GLSL 330 |
| 1.21.11 – 26.2 | `core/terrain` | `ChunkSection` block, GPU sprite animation, linear sampling with RGSS, every block mipmapped; 26.1 drops `Normal`; 26.2 adds Vulkan and reversed depth |
| 26.3 | `core/terrain` | ShaderC, `#include`, explicit `layout(location)`, `MULTIDRAW_TERRAIN` |

Pack formats [V] ([Pack format](https://minecraft.wiki/w/Pack_format)):

| Version | Format |
|---|---|
| 1.20.6 | 32 |
| 1.21.1 | 34 |
| 1.21.2 | 42 |
| 1.21.4 | 46 |
| 1.21.5 | 55 |
| 1.21.6 | 63 |
| 1.21.8 | 64 |
| 1.21.9 | 69 |
| 1.21.11 | 75 |
| 26.1 | 84 |
| 26.2 | 88 |
| 26.3 | 97.1 |

### CraftEngine [V]

- **Chunk packets:** CE parses every section of every chunk packet (`LevelChunkWithLightListener`). It exposes a **public static per-player, per-section biome hook** that it never uses itself:
  - `LevelChunkWithLightListener.addBiomeRemapper(BiomeRemapper)`, where `boolean remap(Player, SectionPos, PalettedContainer<Integer> biomes)`;
  - remappers chain;
  - CE re-encodes only the sections that changed.

  This is our layer 2 entry point.
- **Registry data:**
  - CE only *reads* the number of `worldgen/biome` entries (`RegistryDataListener`, sizes `user.clientBiomeList`);
  - it injects nothing;
  - its packet-listener registration is private, one listener per packet id.

  So we inject our biomes with our own Netty handler placed **before** CE's encoder; CE then counts our entries too.
- **Overlays:**
  - Every file under a CE pack's `resourcepack/` is copied by path, including a `pack.mcmeta` and overlay directories.
  - `merge_pack_mcmeta` merges `overlays.entries` by directory.
  - `validatePackMetadata` rewrites ranges to `formats` or `min_format`/`max_format` as needed.

  So our overlays survive.
- **`resource-pack.exclude-core-shaders`** (default false) deletes `assets/minecraft/shaders` everywhere.
- **Per-player sending:** `BukkitAdaptor.adapt(player)` → `NetWorkUser.sendPacket(nmsPacket, immediately)`.
- **No fluid support:** CE's `fluid_state` setting only accepts `water` or nothing. Its only no-collision translucent pool is `tripwire` (126 states). This is why the fallback carrier (custom blocks) is not the plan.

---

### Findings from the stage 0 spike (2026-10-15/16)

Each was measured with a Python simulation of the whole chain: the server's areas, the client's jittered biome lookup (copied from 26.2 and checked against vanilla, 2000/2000), Biome Blend 1×1 to 15×15, 8-bit colors and face shading, and the shader's decision.

- **The only per-block data a fluid face carries** (26.2 `FluidRenderer`, Sodium 0.9 `DefaultFluidRenderer`) is the tint (the biome water color) and the light. Fluid sprites are fixed per fluid type (`FluidStateModelSet`). Sodium smooths light, so the tint is the only usable channel.
- **The client reads a block's color from one of the 8 biome cells around it**, chosen with a jitter seeded by the hashed world seed it gets at login. The server can compute exactly which cell (`BiomeZoom`).
- **Splitting a color against unknown waters is unreliable.** With 6 keys and 11 vanilla water colors as possible backgrounds:
  - it was wrong 1 in 10 below a 30% key share;
  - mixes of a diluted key, other waters and blend gave wrong fluids in game (green around brine).
- **Splitting against one known water is reliable.** The server puts a *neutral* water color around every fluid, so near a fluid every blend is key + neutral. The result: wrong 0 in 20,000 from a 10% share up, with the share accurate to ±3%.
- **Neutral ring width:** 6 cells, measured on all 6 fluids and three natural water colors. 3 cells still gave up to 98 wrong blocks at 15×15; 5 or 6 gave none.
- **Plain-water check:** blends of two vanilla waters (biome borders) must count as plain water. Without that, acid was read where neutral met a warm ocean.
- **Two different keys in one blend window can't be split.** A three-color solve made it worse (531 versus 80 wrong). Different custom fluids therefore keep apart:

  | Gap | Biome Blend 5×5 | Biome Blend 15×15 |
  |---|---|---|
  | 4 blocks | 0.2% wrong | — |
  | 12 blocks | 0 wrong | — |
  | 16 blocks | 0 wrong | 0 wrong |
- **Dispersion:** diluted keys (key + neutral at 50% and 25%) per ring of cells. With the neutral ring in place, all 6 fluids at every blend setting gave 0 wrong, and with Blend OFF the fade is stepped.
- **Texture grid sampling:** the grid's tiles must be sampled at LOD 0. Mipmaps blended neighbouring tiles at a distance, which made plain water show fluid colors.

## 3. Goals and non-goals

**Goals:**
- Placeable custom fluids with their own texture (still, flow and overlay), animation, tint, opacity, fog, viscosity, flow speed and range.
- Buckets, pumps and plenishers.
- Persistent across restarts.
- 1.20.6 → 26.3; Paper and Folia.

**Non-goals:**
- Gases or chemicals in the world.
- Custom fluid physics on mod clients (Sodium, Iris and OptiFine see the key tint).
- Lava-like hot fluids (there is no carrier; heat effects can be done server-side, §5.9).
- Worldgen lakes.
- Fluids inside CE furniture.

---

## 4. API (`api`)

**New package `fr.robie.machineengine.api.world.fluid`:**

```java
/** How a fluid behaves and looks when placed in the world. Built once; immutable. */
public final class WorldFluidSettings {
    public static Builder builder();
    // look
    Key stillTexture();      // e.g. mekanized:block/fluid/brine_still (pack texture path); default: recolored water
    Key flowTexture();       // default: recolored water_flow
    @Nullable Key overlayTexture();
    int tint();              // 0xRRGGBB multiplied over the texture (white for colored textures)
    int fogColor();          // underwater fog
    float opacity();         // 0..1, 1 = opaque (oil)
    int frameTime();         // ticks per animation frame (water: 2)
    // physics
    Viscosity viscosity();
    Spread spread();
    boolean infiniteSource();         // two sources of this fluid make a new one, like water (default true)
    MixedSource mixedSource();        // a source formed between this fluid and another (default NONE)
    Mixing mixing();
    List<FluidEffect> effects();     // optional (§5.9)
}

public record Viscosity(double swimFactor, double walkFactor, double gravityFactor,
                        double jumpFactor, double entityDrag) {
    public static final Viscosity WATER = new Viscosity(1, 1, 1, 1, 0);
}
public record Spread(int tickDelay, int range) {          // water: 5 ticks, 7 levels
    public static final Spread WATER = new Spread(5, 7);
}
/** What a new source between different fluids (custom + water, or two custom fluids) becomes. */
public enum MixedSource { NONE, WATER }
public sealed interface Mixing { /* IncomingLoses, BecomesBlock(Key block), ... */ }
```

**Registration:** a new static registry, frozen after enable like the others:

```java
context.worldFluids().register(oilType, WorldFluidSettings.builder()...build());
```

- `ResourceType` stays as it is (D41 keeps only the color on it).
- Addon YAML gets a `world:` section on fluid definitions with the same keys (`YamlDefinitions`).

**Lookup and service:**
- `Fluids.worldFluid(type)` / `Fluids.fromWorld(key)` keep their meaning for water and lava. For a placeable fluid, `worldFluid` returns the fluid's own key: the world id of a tracked fluid **is** its resource key.
- New service `WorldFluids` (from `MachineEngine.world()`):

```java
public interface WorldFluids {
    @Nullable WorldFluidSettings settings(ResourceType fluid);    // null = not placeable
    @Nullable WorldFluid at(World world, int x, int y, int z);    // tracked → its key; water/lava → minecraft:*
    boolean place(World world, int x, int y, int z, ResourceType fluid);   // a source block
    @Nullable ResourceType take(World world, int x, int y, int z);         // a source block
}
```

**What doesn't change:**
- `MachineWorld.fluidAt` / `placeFluid` / `takeFluid`, and `WorldFluid(Key, boolean)`. `MachineInstanceImpl` and `CraftEngineMachineHost` call `WorldFluids`. So the Electric Pump and the Fluidic Plenisher handle custom fluids with no change on their side.
- E5 still holds: the core gives the ids no meaning.

**Events** (`api.paper.event`, D43):
- `WorldFluidPlaceEvent`, `WorldFluidRemoveEvent` (cancellable);
- `PlayerEnterWorldFluidEvent`, `PlayerLeaveWorldFluidEvent`.

---

## 5. Server implementation (`common`, package `fr.robie.machineengine.paper.fluid`)

### 5.1 Storage: `FluidChunkStore`, `FluidChunk`, `FluidChunkCodec`

- **In memory:** `FluidChunk` per loaded chunk holds `Section[]` (index = section y). Each `Section` is:
  - a palette (`ResourceType[]`, plus opaque `Key`s for unknown fluids, D3);
  - `short[] positions` (packed `y<<8 | z<<4 | x`);
  - `byte[] paletteIndex`.

  A chunk without fluids has no `FluidChunk`.
- **On disk:** chunk PDC, key `machineengine:world_fluids`, `byte[]`:

  ```
  u8 version=1
  varint paletteSize, then paletteSize × (utf key)
  varint sectionCount, then per section:
      varint sectionY (zigzag), varint count, count × (u16 packedPos, varint paletteIdx)
  ```

  A 10,000-block lake is about 30 KB.
- **When it is read and written:**
  - load on `ChunkLoadEvent`;
  - write the PDC on `ChunkUnloadEvent` and `WorldSaveEvent`, only when the chunk is dirty;
  - every access runs on the chunk's region thread (D35).
- **Lazy cleanup:** a tracked position whose block is no longer water (or waterlogged) is dropped when it is next read, and the chunk is checked once on load.
- **Unknown fluid** (its addon was removed): the key stays in the palette (D3), and the block renders and acts as plain water.

### 5.2 `FluidTracker`

Wraps the store.
- **Reads:** `fluidAt(block)`.
- **Writes:** `set(block, type)` and `clear(block)`.
- **On every change it:**
  - fires the API events;
  - marks the 4×4×4 cell dirty for layer 2 (§6.3);
  - tells the viscosity task (§5.8).

### 5.3 Flow: `FluidFlowListener`

Vanilla water does the physics; we steer it:
- **`BlockFromToEvent`, where the source block is tracked as F:**
  1. **Range.** If the target's new level would be beyond `F.spread.range`, cancel.
  2. **Speed.** If `F.spread.tickDelay > 5`, cancel, then schedule `FluidFlow.spread(from, to, F)` after `tickDelay - 5` ticks on the region scheduler. That sets the same water state vanilla would have set, and tracks it. If `tickDelay < 5`, we can't make vanilla faster [G], so the API documents 5 as the minimum.
  3. **Mixing.** If the target is water that is tracked as G ≠ F, or is plain water, apply `F.mixing` (§5.5).
  4. Otherwise let it happen and `tracker.set(to, F)`.
- **`BlockFromToEvent` from plain water into a tracked block:** mixing, the same way the other way round.
- **Drying up:** vanilla removes flowing water without an event. [VERIFY stage 0] whether Paper's `FluidLevelChangeEvent` fires for it, including becoming air. If not, lazy cleanup (§5.1) handles it, and the cell refresh is fired by a neighbour check in `BlockPhysicsEvent` on water.
- **Infinite sources** (owner's rule, 2026-10-15): Paper fires `FluidLevelChangeEvent` (cancellable) just before flowing water becomes a source [V: `FlowingFluid.tick`, 26.2]. The fluids of the source neighbours decide:
  - **same custom fluid:** a new source of that fluid if `infiniteSource` is on (default, like water); otherwise the event is cancelled;
  - **custom + plain water, or two custom fluids:** `mixedSource`. `NONE` (default) cancels, so no new source forms; `WATER` makes plain water.

  The same event reports water drying up, which clears the tracking at once.

### 5.4 Placing and removing: `FluidBlockListener`

- **`BlockPlaceEvent` into a tracked fluid:**
  - a non-waterloggable block clears the tracking;
  - a waterloggable block keeps it (the block becomes waterlogged with F).
- **Other events that clear or move the tracking:**
  - `BlockBreakEvent` on a waterlogged tracked block;
  - `SpongeAbsorbEvent`;
  - `BlockPistonExtendEvent` / `RetractEvent` (water is destroyed: clear);
  - `BlockExplodeEvent` / `EntityExplodeEvent`;
  - `BlockFormEvent` (ice etc.: clear; [VERIFY] whether ice forms on tracked water, see the open questions);
  - `CauldronLevelChangeEvent` (custom buckets are refused).

### 5.5 Mixing: `FluidMixing`

The default rule is `IncomingLoses`: the flow is cancelled and nothing changes, so two fluids meet and stop. `BecomesBlock(block)` sets the target to the given block (vanilla or CE) and clears the tracking, like lava + water makes stone. Plain water counts as a fluid with the default rule.

### 5.6 Buckets: `FluidBucketItems`, `FluidBucketListener`

- **Items:** one CE item per placeable fluid, `<ns>:bucket/<name>`, made from a template (D36) on `minecraft:water_bucket` with the fluid's bucket texture. If none is given, a generated recolor of `item/water_bucket` (§7.2).
- **`PlayerBucketFillEvent` on a tracked source:** the result item is that fluid's bucket, and the tracking is cleared.
- **`PlayerBucketEmptyEvent` with a fluid bucket:** vanilla places water, and we track the placed block (or the waterlogged block). Then `tracker.set`.
- **`BlockDispenseEvent`** with a fluid bucket: the same, done the next tick on the target block.
- **Mekanized:** the `mekanized:bucket_filling` / `bucket_draining` recipes get one row per placeable fluid.

### 5.7 Machines: `CraftEngineMachineHost`

`worldFluidAt`, `placeWorldFluid` and `takeWorldFluid` (l.286-294, 417-427) go through `WorldFluids`. `fluidMaterial`/`fluidKey` (l.567-577) stay for water and lava.

The plenisher's `findSpace` (Mekanized `UtilityBehaviors`) already compares `WorldFluid` keys, so it keeps filling only with its own fluid. A small check that the pump's `findSource` matches by key covers the pump.

### 5.8 Viscosity: `FluidViscosityTask`

- **Players:** one repeating task per online player on the entity scheduler (Folia-safe), every 2 ticks.
  - It checks which tracked fluid the feet and the eyes are in (two lookups in the map).
  - On entering F, it adds attribute modifiers keyed `machineengine:fluid_viscosity`:

    | Viscosity field | Attribute |
    |---|---|
    | `swimFactor > 1` | `water_movement_efficiency` + `(swimFactor-1)` (1.21+; faster) |
    | `walkFactor` | `movement_speed` × `walkFactor` (`ADD_MULTIPLIED_TOTAL`) |
    | `gravityFactor` | `gravity` × `gravityFactor` (1.20.5+) |
    | `jumpFactor` | `jump_strength` × `jumpFactor` |

  - On leaving, it removes them.
  - `swimFactor < 1` (thick fluids) cannot use attributes, so the task scales the player's velocity (`setVelocity(v × (1 - entityDrag))`) every 2 ticks while inside. That costs some jitter [G, measured in stage 0]. Thick fluids are therefore a per-fluid choice the owner makes knowingly.
- **Attribute access** differs by version (enum, then registry; binary change, D25), so it goes through `NmsAccess.attribute(Key)`.
- **Other living entities, items and boats:** the server moves them, so a region task per chunk with tracked fluid applies `entityDrag` and gravity to the entities inside. Chunks without fluids cost nothing.
- **Reset:** on quit, death, world change and plugin disable, every modifier is removed. Modifiers are also transient: they are never saved, because of their key and because they are removed on quit.

### 5.9 Effects (optional): `FluidEffect`

Run by the same task:
- potion effects while inside;
- damage per second;
- fire ticks;
- `oxygen_bonus` (breath).

Example: brine does nothing, sulfuric acid does damage.

---

## 6. Client layer 2: key biomes

### 6.1 `KeyPalette` (`common`, `paper.fluid.client`)

- **Assignment:** every placeable fluid gets a **key color** and an index `k` (1..N; 0 = plain water), assigned when the registries freeze and stable for the session.
- **Rules,** all unit-tested:
  - **Away from vanilla.** In chromaticity space `(r/(r+g+b), g/(r+g+b))`, every key keeps at least a distance `D` from every vanilla biome water color: `3F76E4` default, `43D5EE` warm, `45ADF2` lukewarm, `3D57D6` cold, `3938C9` frozen, `617B64` swamp, `3A7A6A` mangrove, `5DB7EF` cherry, plus any biome on the server.
  - **Away from each other**, by the same distance `D`.
  - **Same brightness.** The keys use maximum brightness so that `× 0.5` stays above the noise.
  - **Close to the fluid's own color** when possible, so that the fallback without a shader looks right (§6.4).
- **Too many fluids:** the palette is the fluid colors, nudged until the rules hold. When more than about 40 placeable fluids make that impossible, the engine logs it and the extra fluids reuse the nearest key (they look alike).

### 6.2 `KeyBiomes` and the registry injection

- **Biome entries:** for each **visual group** of real biomes (biomes with the same sky, fog, grass, foliage, music and particle settings, so many fewer than the biomes) and each fluid *k*, one client-only biome `machineengine:key/<group>/<k>`. It copies the group's network NBT (`Biome.NETWORK_CODEC`), with `water_color = key(k)` and `water_fog_color = fluid.fogColor`.
- **Size:** about 30 groups × 22 fluids ≈ 660 entries, around 150 KB of registry data at join.
- **Injection:** `KeyBiomeInjector`, a Netty `ChannelOutboundHandler` added with Paper's `ChannelInitializeListenerHolder.addListener` (exists in 1.20.6).
  - It sits **before** CE's `craftengine_player_channel_handler`, so it rewrites the NMS packet object and CE then counts our entries.
  - On `ClientboundRegistryDataPacket` for `worldgen/biome`, it appends our entries **at the end**. Their client ids are therefore `serverBiomeCount + i`, known on the server.
- **Version code:** building the NBT and the packet differs by version, so it goes into `NmsAccess.injectBiomes(...)`, implemented in `nms:v1_20_6` and `nms:v26_3`, with `ReflectiveNms` covering 1.21.x, 26.1 and 26.2 (the packet shape `(ResourceKey, List<PackedRegistryEntry>)` has been stable since 1.20.5 [VERIFY per version]).
- **[VERIFY stage 0]:**
  - the known-packs handshake (1.20.5+) still accepts full-data entries appended after vanilla ones;
  - where `water_color` lives on 26.x.

### 6.3 `KeyBiomeRemapper` and cell refresh

- **The remapper:** an implementation of CE's `BiomeRemapper`, registered at enable (and removed at disable) in `common/craftengine`. For a `SectionPos` with tracked fluids, it:
  1. for each of the 64 cells in the section, takes the dominant tracked fluid (most blocks; ties go to the lower `k`);
  2. finds the visual group of the cell's current client biome id;
  3. writes the key biome's client id;
  4. **padding:** cells next to a fluid cell that contain *no water at all* also get the key biome. This pushes Biome Blend's smearing outward. It is configurable (0–2 cells; default 1). Padding never covers cells with plain water, or that water would show the custom texture.
- **Precompute:** `FluidChunk` keeps a per-section `byte[64]` cell→k table, rebuilt when it is dirty. The remapper (which runs on Netty threads) reads only an immutable snapshot published with a volatile write.
- **Refresh after changes:** dirty cells are collected per chunk. Once a second at most per chunk, `World.refreshChunk(x, z)` resends it to the players watching it, and CE runs the remapper again.
- **Later optimization** (stage 6): send `ClientboundChunksBiomesPacket` with our own section data instead of a full chunk resend (version code in `NmsAccess`).

### 6.4 Fallback

Without the shader (Sodium, Iris, OptiFine, `exclude-core-shaders`, broken shader), the fluid shows as water tinted with its key and with its own fog. That's why keys are kept close to the fluid's color (§6.1).

---

## 7. Client layer 3: shader and textures

### 7.1 How the shader finds the fluid

Inside `terrain.vsh`, or `rendertype_translucent.vsh` before 1.21.2:

```glsl
// added; constants generated per server (§7.3)
#define ME_KEYS 22
const vec3 ME_KEY[ME_KEYS] = vec3[](...);      // chromaticity-normalised keys
uniform sampler2D Sampler0;                     // declared by hand in the vertex stage [VERIFY 26.3]
flat out int meFluid;          // 0 = not a fluid, -1 = water, k = fluid k
flat out vec4 meTile;          // atlas rect of the chosen tile/frame
out vec2 meLocal;              // 0..1 inside the tile

int meDecode(vec3 c) {                          // c = Color.rgb
    float s = c.r + c.g + c.b;                  // shade cancels out in chromaticity
    vec2 ch = c.rg / s;
    int best = 0; float bestD = ME_TOLERANCE;
    for (int k = 0; k < ME_KEYS; k++) { float d = distance(ch, ME_KEY[k].rg); if (d < bestD) { bestD = d; best = k + 1; } }
    return best;                                // 0 = no key: plain water look
}
```

Two checks, both required, so that other tinted blocks (grass, redstone, stems) never match:
1. **The sprite is a water sprite.** The texel at `UV0` (read with `texelFetch` at LOD 0) carries the water marker: the generated water sprites have their low color bits set to a known pattern in every texel of block (0,0) of each 16×16 tile [G, chosen in stage 0; see 7.2].
2. **The color decodes to a key.**

**Finding the tile origin** [G, stage 0 picks one]:
- **(a) Marker-coded blocks.** Every 16×16 block of the generated sprites encodes its own (column, row) in the low bits of its top-left texel (3+3+2 bits, invisible). The vertex reads the texel it sits on, rounds down to its 16-px block (atlas sprites are placed on 16-px multiples when mipmaps are on; [VERIFY]), reads that block's code, and gets the sprite origin.
- **(b) Still quads only from UVs.** Still-water quads cover the whole sprite, so the corner (`gl_VertexID % 4`) gives the origin directly. Flowing quads use (a).

**The remap:**
- **Where the tile is:** the still and flow sprites are a grid: columns = animation frames, rows = fluids (row 0 = water). The vertex computes its position inside the vanilla frame (`meLocal`), and the target rect is `row = k`, `column = frame`.
- **Which frame:** `frame = int(GameTime * 24000.0 / frameTime) % frames`. `GameTime` is `(world age % 24000) / 24000`, so this has one-tick precision.
- **In the fragment shader:**
  - sample `textureLod(Sampler0, meTile.xy + clamp(meLocal, h, 1-h) * meTile.zw, 0)`, with `h` = half a texel, so the linear filtering of 1.21.11+ can't bleed across tiles;
  - multiply by the fluid's tint (a constant array);
  - set alpha to the fluid's opacity;
  - the result then goes through the vanilla lighting and fog code unchanged.
- **Plain water (`meFluid == -1`)** uses row 0 with the same code, so it looks like vanilla. Our grid replaces the vanilla `.mcmeta` animation.

### 7.2 Generated textures: `FluidPackBuilder` (`common`, `paper.fluid.pack`)

It runs at enable and on reload, **before CE builds the pack** [VERIFY stage 0 the order: MachineEngine copies `craftengine-pack` at load, `MachineEnginePlugin.java:106-109`].

1. **Collect.** For each placeable fluid, it reads `stillTexture`, `flowTexture` and `overlayTexture` from the CE resource folders (`plugins/CraftEngine/resources/*/resourcepack/assets/<ns>/textures/<path>.png` and its `.mcmeta`). A missing texture becomes a recolor of the vanilla water texture (read from the server jar's assets? No: Paper has no client assets, so vanilla `water_still`/`water_flow` greyscale copies ship in our pack, 2 small PNGs, from the vanilla client jar; [VERIFY] the license note, otherwise draw our own).
2. **Normalize.** Every fluid's frames are resampled to the water frame size (16×16 still, 32×32 flow) and the same frame count (the least common multiple, capped at 32; frames are repeated or dropped).
3. **Build the grids.** `water_still.png` = `frames × 16` wide, `(N+1) × 16` tall, and the same for `water_flow` (32-px tiles) and `water_overlay`. Row 0 is vanilla water. Then the marker bits.
4. **Write the files.** The grids go into the same overlay directories as the shaders (§7.3), under `assets/minecraft/textures/block/`, with **no `.mcmeta`**, so the whole grid lands in the atlas. The base pack keeps vanilla water. A client above our newest shader set therefore never gets a grid without a shader. The grid stays within 2048 px on its longest side.

   **Known gap:** a client that ignores core shaders but still applies overlay textures (Sodium, OptiFine) would draw the whole grid squeezed onto one block [G]. Stage 0b looks at this. The options, in order:
   1. ship a Sodium shader override as well (packs can override Sodium's shaders from Sodium 0.9 / 26.2);
   2. make each grid row show as plain water when squeezed (each row's frames carry the vanilla look in their colors, and the fluid's look comes only from the tint the shader applies);
   3. accept it, and document "use vanilla rendering or our Sodium support".
5. **Buckets:** generated recolored bucket textures for fluids that have no bucket texture.
6. **The generated GLSL header** `me_fluids.glsl`: keys, tolerance, tints, opacities, frame times, frame counts and grid size.

### 7.3 Shader sources and overlays

- **Sources:** `plugin/src/main/resources/craftengine-pack/resourcepack/` gets one overlay directory per set:

  | Directory | Versions | Formats | Files |
  |---|---|---|---|
  | `me_fluids_a` | 1.20.6–1.21.1 | 32–34 | `rendertype_translucent.{json,vsh,fsh}` |
  | `me_fluids_b` | 1.21.2–1.21.4 | 42–46 | `terrain.{json,vsh,fsh}` |
  | `me_fluids_c` | 1.21.5–1.21.8 | 55–64 | `terrain.{vsh,fsh}` (uniform blocks from 63: split c1 55 / c2 63–64 if needed) |
  | `me_fluids_d` | 1.21.9–1.21.10 | 69 | GLSL 330 |
  | `me_fluids_e` | 1.21.11–26.2 | 75–88 | `ChunkSection`, RGSS; 26.1 may need its own (`Normal` removed) |
  | `me_fluids_f` | 26.3 | 97.1 | ShaderC, `#include`, explicit locations |

- **Base files:** each set starts from that version's vanilla files (from [misode/mcmeta](https://github.com/misode/mcmeta) tags) with our code added between `// ME_FLUIDS BEGIN/END` markers, so a new Minecraft version is a merge of a small block.
- **`pack.mcmeta`** in our `resourcepack/` declares the overlay entries. CE merges them and rewrites the format fields (§2).
- **Above our highest set:** the base pack has no shader, so a newer client gets vanilla rendering plus the key tint. An unknown version never breaks the pack.
- **Config:**
  - `world-fluids.shader: true` (default true after stage 4 is checked); when false, the overlays are not written;
  - on enable, a warning when CE's `resource-pack.exclude-core-shaders` is true.

### 7.4 Underwater view

- `water_fog_color` gives the fog color.
- The overlay seen with the head under water (`water_overlay` / the in-water screen effect) is out of scope in stage 4. Tinting it would need the post or `blit` shaders. Noted under §13.

---

## 8. Version code (`NmsAccess` additions, D25/D28)

```java
/** Attribute by key (e.g. minecraft:gravity); null where it doesn't exist (before 1.20.5 / 1.21). */
@Nullable Attribute attribute(@NotNull Key key);
/** Appends client-only biomes to the biome registry data sent to a connecting player. */
void installBiomeInjector(@NotNull KeyBiomeSource source);
/** The network NBT of a server biome, to copy into a key biome. */
@NotNull Object biomeNetworkData(@NotNull Key biome);
/** The client id range used by the server's biomes (their count). */
int serverBiomeCount();
```

- **Where they are implemented:**
  - in `nms:v1_20_6` and `nms:v26_3`;
  - reflectively in `ReflectiveNms`, for 1.21.x, 26.1 and 26.2.

  If the reflective path fails on a version, the engine logs it once and runs with layers 1 and 4 only: fluids work but look like water.
- **The trap from 1.20.6:** every class there goes through ASM 9.7. Nothing new may touch the `nms/v26_3` classes from shared code ([[paper-api-compile-version]]).

---

## 9. Config (`config.yml`, `world-fluids.*`)

| Key | Default | Notes |
|---|---|---|
| `world-fluids.enabled` | true | restart-only |
| `world-fluids.shader` | true | writes the shader overlays |
| `world-fluids.padding` | 1 | key-biome padding in cells (0–2) |
| `world-fluids.key-tolerance` | 0.035 | chromaticity distance accepted by the shader |
| `world-fluids.refresh-interval` | 20 | ticks between chunk refreshes after changes |
| `world-fluids.viscosity-interval` | 2 | ticks |
| `world-fluids.max-tracked-per-chunk` | 16384 | stops placement beyond it (protects storage) |

---

## 10. Stages

Each stage ends with its tests green, a dev server check ([[headless-dev-server-checks]]), and the owner's in-game check where one is listed. Nothing is built while a dev server runs ([[no-build-while-server-runs]]).

### Stage 0: spikes (a `spike/world-fluids` branch, thrown away afterwards)

- **0a. Key biome.**
  - Inject one key biome by hand through the Netty handler.
  - A CE `BiomeRemapper` puts it on one hard-coded cell.

  Check on 1.20.6, 1.21.11 and 26.3:
  - the water there is tinted;
  - the fog has its color;
  - F3 shows the key biome client-side only;
  - CE still remaps blocks.

  Answers: whether the known-packs handshake accepts the entries, where `water_color` lives on 26.x, and whether `refreshChunk` re-runs the remapper.
- **0b. Shader texture swap.**
  - A two-row hand-made grid.
  - `terrain.vsh/.fsh` for 1.21.11 and 26.3, plus `rendertype_translucent` for 1.20.6.

  Check:
  - still water, flowing water and waterfalls;
  - Biome Blend 1, 5 and 15;
  - OpenGL and Vulkan (26.2+);
  - Sodium (expect the tint only, and no squeezed grid).

  Answers: whether `Sampler0` works in the vertex stage, which origin method wins (a or b), the 16-px alignment, and how overlay textures behave under Sodium.
- **0c. Viscosity.**
  - Attribute modifiers added and removed at the border of a pool.
  - Velocity damping for a thick fluid.

  Check: rubber-banding and jitter, on 1.20.6 and 26.3.
- **0d. Events.**
  - which events fire when flowing water dries up and when a source forms;
  - whether ice forms on tracked water;
  - the order of the pack build: our `FluidPackBuilder` vs CE's.

The owner reviews the results, and the plan is updated (revision 4) before stage 1.

### Stage 1: API and storage

- **api:**
  - `WorldFluidSettings` (+ builder), `Viscosity`, `Spread`, `InfiniteSource`, `Mixing`, `FluidEffect`;
  - `WorldFluids`, the registry in the addon context, and the events.
- **common:**
  - `FluidChunk`, `FluidChunkCodec`, `FluidChunkStore`, `FluidTracker`;
  - the chunk load/unload/save listeners;
  - YAML `world:` parsing in `YamlDefinitions`.
- **Tests (`test` module):**
  - codec round trip (empty, one block, 10k blocks, unknown key kept);
  - palette compaction;
  - lazy cleanup;
  - settings validation (ranges, defaults);
  - YAML parsing.

### Stage 2: flow, buckets, machines

- **common:** `FluidFlowListener`, `FluidFlow`, `FluidBlockListener`, `FluidMixing`, `FluidBucketItems` (CE template), `FluidBucketListener`.
- **`CraftEngineMachineHost`** fluid methods go through `WorldFluids`.
- **Tests:**
  - range cut-off;
  - delayed spread;
  - mixing rules (both directions, plain water);
  - infinite-source policies;
  - bucket fill/empty/dispense;
  - sponge/piston/explosion clear.
- **Dev check:**
  - a test fluid placed with a bucket spreads at its speed and range;
  - the plenisher fills a pool;
  - the pump drains it back as the same fluid;
  - a restart keeps everything.

### Stage 3: key biomes (layer 2)

- **common:**
  - `KeyPalette`, `KeyBiomes` (visual groups);
  - `KeyBiomeRemapper` with padding;
  - a cell table snapshot per section;
  - `FluidRefreshTask`.
- **nms:** `attribute`, `installBiomeInjector`, `biomeNetworkData` and `serverBiomeCount` in `nms:v1_20_6`, `nms:v26_3` and `ReflectiveNms`.
- **Tests:**
  - key palette rules (distance to vanilla colors and between keys, determinism);
  - dominant-fluid-per-cell and padding (never over plain water);
  - Java decoding that mirrors the GLSL (`KeyDecoder`, the same math as `meDecode`, tested with shaded and blended colors).
- **Owner check:**
  - every test fluid has its tint and fog on 1.20.6, a 1.21.x version and 26.3;
  - grass and sky nearby look unchanged;
  - with two players and one moving the fluid, both see the update within a second.

### Stage 4: shader and generated textures (layer 3)

- **common:** `FluidPackBuilder` (grids, marker bits, bucket textures, `me_fluids.glsl`), and the config switch with the CE warning.
- **plugin:** the six overlay sets, and `pack.mcmeta` overlay entries in `craftengine-pack/resourcepack/`.
- **build:**
  - a Gradle task `:plugin:checkShaders` that runs `glslangValidator` on every set with a sample `me_fluids.glsl` when the tool is on PATH (skipped otherwise, and run in CI);
  - a test that the marker bits round-trip and the grids stay under 2048 px.
- **Owner check:**
  - every set in game;
  - OpenGL and Vulkan;
  - Biome Blend 1/5/15;
  - waterfalls, the edges of pools, and fluids next to plain water;
  - Sodium shows the tint fallback.

### Stage 5: viscosity and effects (layer 4)

- **common:** `FluidViscosityTask` (players), `FluidEntityTask` (other entities), `FluidEffect` handling, and the resets on quit/death/world change/disable.
- **Tests:** modifier add/remove bookkeeping, and the version without attributes (1.20.6 without `water_movement_efficiency`) degrading as documented.
- **Owner check:** swimming, walking on the bottom, jumping out, items floating, a boat on top, for a thin, a normal and a thick fluid.

### Stage 6: Mekanized, docs, optimization

- **Mekanized:**
  - `world_fluids.yml` (or a third column in `fluids.txt`) listing the placeable fluids with textures and physics;
  - bucket recipes;
  - fluid textures from Mekanism (MIT; the license notice is already shipped, [[mekanized-plan]]).
- **Docs site:** a "World fluids" page covering addon API, YAML, limits, the shader and mod clients.
- **ARCHITECTURE.md:** D46, a new §, and the config keys in §23.
- **Optional:** `ClientboundChunksBiomesPacket` instead of `refreshChunk` (§6.3).

### Every new Minecraft version from now on

1. Compare the vanilla `terrain`/`translucent` shader of the new version with the newest set.
2. Merge our marked block into it, or add a set if needed.
3. Run `checkShaders`.
4. Check in game.
5. Check `NmsAccess` (registry packet, attributes).

This goes into CONTRIBUTING.md.

---

## 11. Decision (to record once the owner approves)

**D46: custom world fluids are vanilla water tracked by the engine.**
- **Tracking:** in the chunk PDC (`machineengine:world_fluids`).
- **Look:**
  - through client-only key biomes, injected in the biome registry data and placed per player by a CraftEngine `BiomeRemapper`;
  - a terrain core shader in MachineEngine's CE pack, one overlay per version set, draws each fluid's texture grid row, animation and opacity;
  - without the shader, the key tint and fog remain.
- **Physics:** attribute modifiers and velocity damping while inside, and per-fluid spreading steered through `BlockFromToEvent`.
- **Not used:** fake blocks, and changes to server biomes.
- **Placeable:** only fluids registered with `WorldFluidSettings`.

---

## 12. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| The shader texture swap is unproven publicly | the core feature | stage 0b before anything else; the tint fallback |
| Shaders change with most releases, Vulkan is new | the pack breaks for everyone | per-version overlays, `checkShaders`, no shader above the newest set, config switch |
| 4×4×4 cells and Biome Blend | mixed or blurry edges; plain water in a fluid cell shows the custom texture | padding into dry cells only; tolerance; documented; `IncomingLoses` keeps fluids apart |
| CE's `BiomeRemapper` is internal, not `api` | can change in a CE snapshot | small adapter class in `common/craftengine`; D7 already tracks CE snapshots |
| Registry injection (known packs) | can't add biomes | stage 0a; reflective fallback per version |
| Thick fluids need velocity damping | jitter | measured in 0c; per-fluid choice |
| Reflective `NmsAccess` versions | layer 2 off on those | logged; layers 1 and 4 still work |
| Pack build order with CE | missing grids at first start | stage 0d; regenerate and `/ce reload` |
| Performance (tracker lookups, remapper on Netty threads) | lag | O(1) map lookups; immutable snapshots for Netty; per-chunk refresh limit |

## 13. Not in this plan (noticed on the way)

- **Underwater screen overlay per fluid:** this needs the post/blit shaders. It could be a later stage 7.
- **Gases and chemicals in the world:** there is no vanilla carrier.
- **CE's `exclude-core-shaders`:** worth a line in the docs whatever happens here.
- **`.claude/worktrees/`:** it holds stale copies of old module layouts and of `.craftengine/source`. Searches should skip it.

## 14. Questions for the owner

1. **Your contact:** can they say whether they use water + biome key + shader, or custom blocks? It would shorten stage 0.
2. **Infinite sources:** answered 2026-10-15. The same custom fluid makes a new source of itself, like water (a per-fluid setting, on by default). A custom fluid next to plain water or another custom fluid makes no source (a setting: none or plain water). The spike tries both rules.
3. **Mixing:** (a) the incoming side loses, so fluids meet and stop (recommended); (b) per fluid, with an optional "becomes block".
4. **Thick fluids:** accept velocity damping (some jitter) for fluids slower than water, or limit fluids to water speed or faster?
5. **Which Mekanized fluids are placeable**, and do we use Mekanism's fluid textures for them?
6. **Reflective versions** (1.21.x, 26.1, 26.2): is "fluids work but look like water if the reflective injection fails" fine, or should we add version modules for those?
