# Fluid types plan (2026-10-16)

The owner's request (2026-10-16): turn the world fluids spike into a real feature.
- A fluid is a **custom object that extends `ResourceType`**.
- **Everything about it is customizable**, from code (addons) or from configuration (YAML).

This plan builds on [WORLD_FLUIDS_PLAN.md](WORLD_FLUIDS_PLAN.md): the research, and the spike results in its §2 "Findings". Where the two disagree, this plan replaces revision 3's implementation sections (§4–§10 there).

Status: plan only, the owner's answers are in (§7, 2026-10-16). The key palette research is done (§4.3). The spike stays on branch `spike/world-fluids` as the reference.

## 1. What exists today

**Resource types:**
- `ResourceType` (`api/resource/ResourceType.java`) is a `public final class` with a private constructor and `of(...)` factories.
  - Fields: `key`, `category`, `defaultComponents`, optional `color` (D41).
  - Identity is the registered object, compared by reference (D9).
- **Code registration:** addons register types in `RegistrationContext.resourceTypes()` (static registry `Registries.RESOURCE_TYPE`, frozen after registration, `CoreMachineEngine.start`).
- **YAML registration:** definitions (`DefinitionCompiler`, the `definitions` pseudo-addon, D21) take `resource-types:` with `category` and `color` only. Static, so a change needs a restart.
- **Fluid helpers:** `Fluids` holds the global category (D29), water and lava built in, `create(key[, color])`. `worldFluid`/`fromWorld` know only water and lava.
- **Config:** `ConfigKey`/`ConfigSpec`/`ConfigFile`; built-in keys in `EngineConfig.SPEC`, addon keys in their own spec (Mekanized `MekanizedConfig`).
- **Mekanized:** fluids come from `fluids.txt` (`name #color`), with config color overrides. The pump and the plenisher go through `Fluids.worldFluid`/`fromWorld`.
- **Spike:** everything a placeable fluid needs, in throwaway form under `paper/fluid/spike`: tracking, persistence, flow, buckets, viscosity, key biomes, neutral ring, shader, pack builder (Python).

## 2. The model

### 2.1 `FluidType extends ResourceType`

- **`ResourceType` stops being `final`.** It becomes `public class` with a `protected` constructor. Fields stay `private final`, and `equals`/`hashCode` stay identity (D9). Its factories keep returning plain types. A test checks that no subclass overrides `equals`/`hashCode`.
- **`FluidType`** is a `public final class FluidType extends ResourceType` in `api.resource.fluid`. It is always in `Fluids.category()`.
  - It is registered in the same `RESOURCE_TYPE` registry, so every pipe, tank and recipe keeps working with no change.
  - `Fluids.isFluid(type)` becomes `type instanceof FluidType` (a plain type in the fluid category still counts, for older addons).
- **Built-ins:** `Fluids.water()` and `Fluids.lava()` become `FluidType`s with `WorldMode.VANILLA` (§2.2). `Fluids.create(key[, color])` returns a `FluidType` with no world settings (`WorldMode.NONE`): the same behavior as today.

### 2.2 What a `FluidType` holds

Every part is an immutable record with defaults, so a fluid sets only what it needs:

```java
public final class FluidType extends ResourceType {
    WorldMode world();          // NONE (only in tanks), VANILLA (water/lava), PLACEABLE (custom, carried by water)
    FluidLook look();           // textures, tint, opacity, glow, fog, animation
    FluidPhysics physics();     // viscosity, spread, sources, temperature
    FluidMixing mixing();       // what happens when it meets other fluids
    List<FluidEffect> effects();// what it does to entities inside
    FluidItems items();         // bucket (and later other containers)
    static Builder builder(Key key);
}
```

| Record | Fields (defaults = plain water) |
|---|---|
| `FluidLook` | `stillTexture`, `flowTexture`, `overlayTexture` (resource-pack paths, optional); `tint` (0xRRGGBB); `opacity` (0–1, default water's 0.7); `glow` (0–1, ignores darkness); `fogColor`; `frameTime` (ticks per animation frame) |
| `FluidPhysics` | `viscosity` (`Viscosity`: speed, gravity, jump, swim, damping factors), `spread` (`Spread`: ticks per step, range in levels), `infiniteSource` (bool), `mixedSource` (`NONE`/`WATER`), `entityDrag` (mobs, items, boats) |
| `FluidMixing` | rules against other fluids: list of `(other: fluid key, tag or "water"/"any", result: STOP / REPLACE / BECOME_BLOCK(block) / BECOME_FLUID(fluid) / custom)` |
| `FluidEffect` | effect objects from a registry (§2.3): potion, damage, fire, oxygen bonus, custom |
| `FluidItems` | `bucket`: a CraftEngine item key, or `AUTO` (generated from a template, D36), or none |

- **A fluid needs no texture of its own** (owner, 2026-10-16). Without one, it is drawn with the **vanilla water texture tinted with any color** (its `tint`, by default its menu `color`). Such a fluid takes no tile in the texture grid: the shader uses water's tile and the tint. Only fluids with their own texture use a tile, so the grid limits textured fluids, not fluids.
- **Keys belong to looks, not to fluids** (owner, 2026-10-16). The shader only sees a key, so a key stands for a *look*: texture tile, tint, opacity, glow. Two fluids with the same look share one key. The server still knows which fluid each block is (tracking), so physics, effects, mixing and buckets stay per fluid. Textures are shared too: a tile is one texture, and the tint is per look, so many fluids can use one texture in different colors.
- **So no limit is on fluids:**
  - keys limit *distinct looks* (§4.3);
  - grid tiles limit *distinct textures* (§4.3);
  - fluids are unlimited.
- **The key color is never chosen by the fluid.** It is the code the shader recognizes, so the engine assigns it when the registries freeze (§4.3). The menu `color` (D41) stays the fluid's own color and is also the fallback look.

### 2.3 Extension points (code)

Addons can add behavior through registries, like machine behavior types today:

| Registry (static) | Type | Built-ins |
|---|---|---|
| `FLUID_EFFECT_TYPE` | `FluidEffectType<C>`: config codec + `apply(Entity, FluidType, C, ticksInside)` | `potion`, `damage`, `fire`, `freeze`, `oxygen`, `levitate` |
| `FLUID_MIXING_RESULT` | `FluidMixingResult<C>`: config codec + `apply(world, pos, incoming, present, C)` | `stop`, `replace`, `block`, `fluid` |
| `FLUID_LOOK_SOURCE` | how a texture is produced: `file`, `recolor` (water/lava pattern + gradient), `generated` (crystals, bubbles, as in the spike), or an addon's own generator | `file`, `recolor`, `crystals`, `bubbles` |

- **Events** (`api.paper.event`, D43): `WorldFluidPlaceEvent`, `WorldFluidSpreadEvent`, `WorldFluidRemoveEvent` (cancellable), `EntityEnterFluidEvent`, `EntityLeaveFluidEvent`.
- **Service** `WorldFluids` (from `MachineEngine.world()`): `at(world, x, y, z)`, `place`, `take`, `canPlace(world, pos, fluid)` (with the reason, as `/mefluid show` uses), `fluidsNear`.

## 3. Configuration

Three layers, each overriding the one before:

1. **Code:** `FluidType.builder(key)...build()` in an addon's registration callback.
2. **Definitions YAML** (`plugins/MachineEngine/definitions/**/*.yml`, D21). Static: a restart applies changes. `resource-types` gains a `fluid:` section:

   ```yaml
   resource-types:
     mekanized:fluid/brine:
       category: machineengine:fluid      # optional for fluids
       color: "#FEEF9C"
       fluid:
         world: placeable
         look:
           still: { source: generated, generator: crystals }
           flow: { source: recolor, pattern: water, from: "#C8BA8C", to: "#F0DCAA" }
           opacity: 0.88
           fog: "#C8B88A"
         physics:
           viscosity: { speed: 0.9, gravity: 0.8 }
           spread: { ticks: 5, range: 7 }
           infinite-source: true
         mixing:
           - { with: water, result: stop }
           - { with: mekanized:fluid/sulfuric_acid, result: block, block: minecraft:calcite }
         effects:
           - { type: machineengine:potion, effect: minecraft:slowness, amplifier: 0 }
         bucket: auto
   ```

3. **Server owner overrides:** `plugins/MachineEngine/fluids.yml`, one section per fluid key, any field from 2.2. It is generated on first start with every placeable fluid's current values, commented. **Which fields can be reloaded** (`/machineengine reload fluids`):

   | Fields | Applies |
   |---|---|
   | `physics` (viscosity, spread, sources), `effects`, `mixing` | at once (read at runtime) |
   | `look.opacity/glow/tint/textures` | after the pack is rebuilt (`/machineengine reload fluids` rebuilds it; then `ce reload pack`) |
   | `look.fog` | players must rejoin (it is in the key biomes sent at login) |
   | `world`, `bucket` | restart (static) |

**Global settings** in `config.yml` (`world-fluids.*`, typed `ConfigKey`s next to their owners, as `Fluids.UNIT` is):

| Key | Default | From the spike |
|---|---|---|
| `enabled` | true | — |
| `shader` | true | the overlay; off = key tint only |
| `sodium` | true | the Sodium shader overlay |
| `edge` | `soft` | `--edge` |
| `conflict` | `stop` | `/mefluid conflict` |
| `fluid-gap` | 2 | `/mefluid gap` (2 = about 8 blocks, owner chose after testing) |
| `padding` | 1 | `/mefluid padding` |
| `dispersion` | 2 | `/mefluid dispersion` (used with `conflict: allow`) |
| `neutral-color` | `"#3F76E4"` | the neutral water |
| `uniform-water` | true (owner's choice) | every biome's water color is the neutral (needed for more than 6 placeable fluids, §4.3) |
| `neutral-rings` | 6 | only with `uniform-water: false`: measured 5–6 needed at Biome Blend 15×15 |
| `client-jar` | `download` | where vanilla shaders and water textures come from (§4.4) |

## 4. Engine implementation (production version of the spike)

### 4.1 Modules and packages

- **`api`:**
  - `api.resource.fluid`: `FluidType`, the records, `FluidEffectType`, `FluidMixingResult`, `WorldMode`;
  - `api.world.WorldFluids`;
  - registries in `Registries`.
- **`common`:**
  - `paper.fluid`: tracker, flow, buckets, viscosity, effects, commands;
  - `paper.fluid.client`: key palette, key biomes, remapper, chunk updates;
  - `paper.fluid.pack`: pack builder, ported from `build_pack.py` to Java.
- **`nms`:** biome registry injection, biome-only chunk packet, `BiomeManager` check; in `nms:v26_3`, `nms:v1_20_6`, and reflective for the others (D25). The spike's `FluidSpikeNms` already works on 26.2 and 26.3.

### 4.2 From spike to product (what changes)

| Spike | Product |
|---|---|
| `SpikeFluid` constants | registered `FluidType`s with `WorldMode.PLACEABLE` |
| `/mefluid` | `/machineengine fluid <info|show|fill|bucket|clear>` (admin), messages through MessageFlow (D44) |
| hand-made key colors | `KeyPalette` assigned at freeze (§4.3) |
| persistence (PDC positions + kinds) | same idea, versioned codec (`FluidChunkCodec`), unknown fluids kept as opaque ids (D3) |
| Python pack builder | Java `FluidPackBuilder`, run at enable and on `reload fluids`, writing into MachineEngine's CE pack |
| one shader set (26.2) | one overlay per version band (plan rev 3 §7.3), from templates |
| conflict and gap as commands | `config.yml` keys |

### 4.3 Key palette (researched 2026-10-16)

- **What was tested:** a simulation (as in the spike's findings) of how many keys the shader tells apart. Keys are chosen to be as far apart as possible as seen from the neutral water. Each was mixed with the neutral water at a given share, with 8-bit colors, face shade and Sodium's darkening, then decoded.
- **With vanilla's 11 water colors kept, about 6 keys is the limit.** Telling plain water apart needs a check for blends of any two vanilla waters (an ocean meeting a river, far from any fluid). Without that check, 10–47% of such blends were read as a fluid. With it, the check swallows most colors a 7th to 22nd key could use: only 1 of 218 candidate colors stayed usable.
- **With one water color everywhere, 22 and more work.** The server sends every biome with the neutral water color (`world-fluids.uniform-water: true`). Plain water is then a single color, and the neutral ring is no longer needed. Wrong fluid shown, by share:

  | Keys | 5–10% | 10–20% | 20–40% | 40–100% | Plain water read as a fluid |
  |---|---|---|---|---|---|
  | 12 | 1.4% | 0.2% | 0% | 0% | never |
  | **22** (all Mekanized fluids) | 8.6% | 1.7% | 0.07% | 0% | never |
  | 32 | 19% | 8.6% | 1.7% | 0.1% | never |
  | 48 | 33% | 14% | 2.6% | 0.2% | never |

- **The price:** swamps and warm or cold oceans lose their own water tint for players on the server. Underwater fog keeps each biome's color.
- **The fade's faint tail is cut** below a share that grows with the number of keys, so wrong fluids stay under about 0.1%. The engine picks the threshold from the table: 5% up to 12 keys, 20% up to 22, 40% up to 48.
- **With `uniform-water: false`**, the neutral ring design of the spike stays available, limited to 6 placeable fluids. The engine refuses more and logs why.
- **What the table counts:** distinct looks, not fluids (§2.2). The threshold only cuts the faint end of a fade, where a key is a small part of the mix. Inside a pool (100% share), even 48 keys are read right (0.2% wrong at 40–100%).
- **Palette:** computed by the engine when the registries freeze, one key per distinct look, with the simulator's greedy choice (directions from the neutral water, far from it). It is deterministic, so the same fluids always get the same keys, and stored in the cache so the pack and the server agree.
- **Texture grid (researched 2026-10-16).** Fluids without their own texture use water's tile, and textures are shared through tints, so the grid counts *distinct textures*.
  - **The position code:**
    - Each texel's alpha holds `128 + v`, where `v = tag × 32 + payload` and `tag` is the texel's place in its 2×2 block.
    - The four texels of a block hold:

      | Tag | Payload |
      |---|---|
      | 0 | x low 4 bits + a still/flow flag |
      | 1 | x high 5 bits |
      | 2 | y low 5 bits |
      | 3 | y high bits |

    - Frames up to 512×512 px.
    - The spike's code (one value per row, frames up to 64×128) is replaced.
  - **Decoding (vertex shader):**
    1. Read one texel; its tag gives the block, so the 4 texels of the block are read.
    2. Rebuild x and y.
    3. Check the frame's origin block (it must read 0, 0) and the block to its right (2, 0). That rejects atlas padding copies.
    - Only 4 candidate texels around the vertex are needed (the spike used 6).
    - Opaque textures are rejected after one read: alpha 255 decodes to tag 3 with a payload no frame of 512 px or less uses. This matters because the vertex shader runs this for every terrain vertex.
  - **Simulated:** still 8×8 tiles of 16 px, flow 8×8 and 16×16 tiles of 32 px; random atlas offsets; 0, 1 or 4 texels of edge padding; 20,000 points each, with frame corners. **0 wrong.** And **0 false matches** in 280,000+ texels of vanilla block textures placed all around.
  - **Size:**
    - Since 26.2 (GPU animation) the client keeps one GPU texture per animation frame (`SpriteContents.AnimationState.frameTexturesByIndex`), not one tall strip, so the limit is the frame size: 512×512 is far below any GPU's maximum.
    - Before 26.2 one frame is copied into the atlas per animation step.
    - Memory per sprite is about 4 bytes × width × height × 32 frames:

      | Frame | Memory |
      |---|---|
      | flow 256×256 | about 8 MB |
      | flow 512×512 | about 32 MB, plus mipmaps |
  - **Capacity:**

    | Grid | Still (16 px tiles) | Flow (32 px tiles) | Distinct textures |
    |---|---|---|---|
    | 8×8 (default) | 128×128 frame | 256×256 frame | 63 |
    | 16×16 | 512×512 frame | — | 255 still, but flow only up to 16×16 tiles = 255 |

    The pack builder picks the smallest square grid that holds the textures in use. 8×8 is the default ceiling; a config key raises it.
  - **Still to measure in game** (stage 5): the frame rate with the decoder in every terrain vertex, vanilla and Sodium, compared with the pack off.

### 4.4 Vanilla files

- **The problem:** the pack needs vanilla's water textures and terrain shaders for each client version. They can't be in git (Mojang's), and the server has no client jar.
- **Proposal:** download the official client jar for the configured client versions from Mojang's version manifest, check its SHA-1, and keep it in `plugins/MachineEngine/cache/`. A config key can point to a local jar instead.
- **Sodium:** its shader is LGPL-3.0. The plan keeps our patched copy per Sodium version in the repo, with its license notice, instead of reading a player's jar.

### 4.4 Uniform water, whole chain (researched 2026-10-16)

- **What was tested:** the spike's whole-chain simulation, with every cell outside a fluid sent as the neutral water and no neutral ring. The 22-key palette was used, with a share cut of 20%. The chain covered the server cells, the client's exact zoom (vanilla jitter), the box blend, the face shade with Sodium-like darkening, 8-bit colors and the shader's decode. There were 88 pools (radius 3, 6 and 9) at random places and heights, and about 114,000 blocks per row.
- **One fluid:**

  | Biome Blend | Dispersion | Pool blocks shown | at full strength | Wrong fluid | Plain water read as a fluid |
  |---|---|---|---|---|---|
  | 1×1 | 0 / 1 / 2 | 100% | 100% | 0 / 1 / 5 | never |
  | 5×5 (default) | 0 / 1 / 2 | 100% | 82% / 91% / 91% | 1 / 5 / 19 | never |
  | 15×15 | 0 / 1 / 2 | 99.9% / 100% / 100% | 12% / 23% / 20% | 15 / 15 / 22 | never |

  - "Full strength" matters only with `edge: soft`. With `edge: sharp`, a shown block is drawn fully.
  - Wrong fluids come from the weak tail (shares close to the cut).
  - Dispersion 2 reaches 12–16 blocks around a pool, because the 25% ring is above the 20% cut.
- **Two different fluids, gap between pools** (blocks wrong / shown):

  | Biome Blend | 4 blocks | 8 blocks (2 areas) | 12 | 16 | 20 |
  |---|---|---|---|---|---|
  | 5×5 | 103 / 6,243 | 22 / 6,656 (0.3%) | 3 | 1 | 1 |
  | 15×15 | 871 / 7,136 | 563 / 8,758 (6.4%) | 41 | 0 | 4 |

  - **A pool never shows the wrong fluid.** Every error is a third fluid's color in the open water between the two pools: a mix of keys A and B with the neutral points in a direction between them, where another key lies. The shader can't avoid it, because a two-key fit is ambiguous with 22 keys.
  - These wrong pixels are faint. At 15×15 with an 8-block gap, none is above a 60% share, and half are under 30%.
- **Decision:** `fluid-gap: 2` (8 blocks) stays the default, clean at the default 5×5 blend. Players with 15×15 can see a faint third color between two different fluids closer than 16 blocks. A server that wants none sets `fluid-gap: 4`. The docs page says so.
- **The neutral ring is not needed** with uniform water (`neutral-rings` only applies to `uniform-water: false`).

## 5. Stages

1. **API.** Done (2026-10-16), branch `feature/fluid-types`. Names in the code (they differ from the draft above):
   - reactions are `FluidReaction` / `FluidReactionType` (registry `FLUID_REACTION_TYPE`);
   - texture sources are `FluidTexture` / `FluidTextureType` (`FLUID_TEXTURE_TYPE`);
   - the service is the static `api.paper.WorldFluids`, installed by the platform (headless: vanilla water and lava only);
   - events are `WorldFluidPlaceEvent` (cancellable, cause `BUCKET`/`SPREAD`/`MACHINE`/`COMMAND`/`API`; it replaces the planned spread event), `WorldFluidRemoveEvent` and `EntityFluidEvent.Enter`/`Leave`;
   - `ResourceType`'s `equals`/`hashCode` are made final instead of being guarded by a test.

   The draft for this stage:
   - `ResourceType` opened (protected constructor; the D9/D41 text updated).
   - `FluidType`, the records, the builder, `WorldMode`, `Fluids` changes, registries, `WorldFluids` interface, events.
   - Tests: identity, builder defaults, the built-ins as `FluidType`, an old addon's plain fluid type still accepted.
2. **Definitions and config.** Done (2026-10-16), branch `feature/fluid-types`. In the code:
   - `FluidSpecParser` reads the `fluid:` section (world, bucket, look, physics, mixing, effects); unknown fields are refused with their full path (`physics.viscosity.sped`). A resource type with a `fluid:` section defaults to the fluid category.
   - Built-in types (`BuiltInFluidTypes`, namespace `machineengine`): effects `potion`, `damage`, `fire`, `freeze`; reactions `stop`, `flow`, `block`, `fluid`; textures `file`, `vanilla`, `recolor`, `crystals`, `bubbles`.
   - `fluids.yml` (`FluidSettingsFile`, `FluidOverrides`): written on first start with every placeable fluid's values as comments; `world` and `bucket` can't be overridden there (definition only, restart). An unreadable file keeps the previous overrides. `/machineengine reload fluids` reloads it.
   - `world-fluids.*` keys in `WorldFluidConfig` (enabled, uniform-water, neutral-color, neutral-rings, shader, sodium, edge, conflict, fluid-gap, padding, dispersion, client-jar, texture-grid).

   The draft for this stage:
   - The `fluid:` section in `DefinitionCompiler`, with validation (unknown keys and invalid values reported, the D30 style).
   - `fluids.yml` overrides (generated, reloadable parts), the `world-fluids.*` keys.
   - Tests: parsing, overrides, reload of physics.
3. **Research before the runtime:**
   - key palette size: **done** (§4.3);
   - texture grid code for more than 7 textured fluids: **done** (§4.3, 63 by default, up to 255);
   - uniform water: the whole-chain simulation re-run without the neutral ring: **done** (§4.4).
4. **Runtime.** Done (2026-10-16), branch `feature/fluid-types`; headless dev checks done, the owner's in-game check is open. In the code:
   - `common` `paper.fluid`:
     - `WorldFluidRuntime`: tracking, persistence, flow, sources, buckets, `WorldFluids` answers, chunk updates;
     - `FluidEntities`: viscosity, damping, drag, effects, enter/leave events; players every 2 ticks, other entities every 4 ticks in chunks that hold fluids;
     - `FluidRules`: gap, conflict, padding, dispersion;
     - `FluidChunkCodec`: format 1; saves fluid keys, so a missing addon's fluids stay saved as they are (D3), and data in a newer format is never written over;
     - `FluidAdminCommand`: `/machineengine fluid info|show|fill|bucket|clear`.
   - `common` `paper.fluid.client`: `FluidKeys` (fluids numbered by key, one key per resolved look), `KeyPalette` (the simulator's greedy choice, pinned by a test), `FluidCells`, `BiomeZoom`, `FluidBiomeRemapper`.
   - **Version access:** an internal `api.platform.WorldFluidNms` behind `NmsAccess.worldFluids()`:
     - `nms:v26_3` (also loaded on 26.1 and 26.2);
     - `nms:v1_20_6` (no water movement efficiency: viscosity there is speed, gravity, jump and damping);
     - `nms:common` `ReflectiveWorldFluids` for 1.21.x, with every handle looked up at start.
     - `BiomeSections` writes the biome sections for all of them; each checks them against the server's own reader at startup.
     - `-Dmachineengine.reflectiveWorldFluids=true` forces the reflective one, for dev checks.
   - **Older CraftEngine** (no biome hook): a chunk a player loads gets its fluid colors right after, as a biome-only update.
   - **Failures:** a world fluids failure at start never stops MachineEngine. It is reported, and fluids stay in tanks and pipes.
   - **Dev fixture:** `devcheck.yml` has two placeable fluids (`devcheck:fluid/oil`, `devcheck:fluid/brine`).
   - **Checked headless:** Paper 26.3, Paper 1.20.6 and Folia 26.2 start world fluids, and the biome format check passes. The reflective version starts on Paper 1.21.4 and 1.21.11 (new dev servers `runPaper-1.21.4`, `runPaper-1.21.11`), and when forced on 1.20.6 and 26.2.
   - **Open:**
     - custom bucket items (`bucket: <item>`): only `auto` and `none` work; stage 6;
     - looks changed in `fluids.yml` get new keys only at the next start;
     - `shader`, `sodium`, `edge`, `client-jar` and `texture-grid` wait for stage 5. Until then, players see the raw key colors.

   The draft for this stage, ported from the spike into `common`:
   - tracker, codec, flow, mixing (effect and mixing registries), buckets, viscosity, effects;
   - key biomes, neutral ring, remapper, biome-only updates, conflict and gap rules;
   - admin commands.

   Tests in the `test` module: codec, palette, mixing rules, gap and conflict rules (the `canPlace` reasons).
5. **Pack builder in Java.** Done (2026-10-16), branch `feature/fluid-types`; checked headless, the owner's in-game check is open. In the code (`common` `paper.fluid.pack`):
   - `VanillaClient`: the official client jar of the server's version, downloaded once from Mojang's version list into `plugins/MachineEngine/cache` and checked by SHA-1 (`world-fluids.client-jar: download`), or a path. Shaders, water textures, biome water colors and the pack format (`version.json`) all come from it, so there is no table to keep up to date.
   - `TextureGrid`: the water sprites as grids. A frame holds `columns × columns` tiles, and tile 0 is vanilla water. The alpha of every texel carries the 2×2 position code (§4.3). Only fluids with a texture take a tile; frames are fitted to the water animation by each look's `frame-time`.
   - `ShaderPatch`: one patcher for every version. It relies only on the skeleton all of them share (`out vec2 texCoord0`, `vertexColor = Color * <light>`, `vec4 color = <sample> * vertexColor[ * ColorModulator]`), and finds the rest in the file:
     - explicit varying locations (26.3);
     - the lightmap expression;
     - the alpha-only translucency pass (26.3 `OIT_ALPHA_ONLY`).

     A shader that doesn't match is refused, never guessed.
   - **Version bands found** (13 versions extracted, grouped by identical files): `rendertype_translucent` 1.20.6–1.21.1; `terrain` 1.21.2–1.21.5, 1.21.6–1.21.8, 1.21.9–1.21.10, 1.21.11, 26.1–26.2, 26.3.
   - `FluidPackBuilder` writes `CraftEngine/resources/machineengine/resourcepack/machineengine_world_fluids/` (grids, `.mcmeta`, patched shaders) and the overlay entry in `pack.mcmeta`, for exactly the server's pack format. A client of another version gets no overlay and sees key colors.
   - `FluidPackService`:
     - at enable, writes the pack at once when the jar is cached, so CraftEngine sends it as usual; on the first start, writes it after the download, then `/ce reload pack`;
     - `/machineengine reload fluids` rebuilds it;
     - `world-fluids.shader: false` removes it;
     - warns when CraftEngine's `resource-pack.exclude-core-shaders` is on.
   - **Checks:**
     - `FluidPackTest` covers the grid code round trip in an atlas with padding, the patch, locations, and refusals. With `-Pmachineengine.vanilla`, it also patches all 13 versions.
     - `tools/world-fluids/` holds the vanilla extractor and a glslang check: 13 versions, 88 define sets (cutout; on 26.3 multidraw and every OIT pass), vertex and fragment linked, **0 failures**.
     - Headless: Paper 26.3 and Folia 26.2 download the jar and write the pack; CraftEngine validates and merges the overlay, and nothing else in the pack touches the terrain shader or water textures.
   - **Not covered:** the Vulkan backend of 26.2+ (glslang checks OpenGL rules; in game only). Bucket textures wait for stage 6.
   - **Sodium (added 2026-10-16, owner):** without it, Sodium players saw the raw texture grid on all water, because Sodium draws terrain with its own shader. That's why "later" was not an option.
     - `SodiumShaders` takes the newest Sodium for the server's version from Modrinth (a release, else a beta), checked by SHA-1 and cached. Only Sodium 0.9 and later (26.2, 26.3) read shaders from resource packs; older Sodium loads them from its jar (checked in each jar's `ShaderLoader`), so 1.20.6–26.1 Sodium players still see the grid. `world-fluids.shader: false` avoids that.
     - `ShaderPatch` has two dialects (vanilla, Sodium) sharing one GLSL body. Sodium's names are `v_TexCoord`, `_vert_color`, `v_Color` and `u_BlockTex`, and its color is set over two lines. Under Sodium's Vulkan backend the fluid code is skipped (`#ifndef VULKAN`), not checked.
     - glslang: 68 Sodium define sets (vertex compression, fog, cutout, every 26.3 OIT pass), 0 failures; the vanilla 88 are still 0 failures.
     - The owner's Sodium 0.9.1 has the same shaders as 0.9.2. A future 0.9.x that changes them would break Sodium players until the pack is rebuilt (`/machineengine reload fluids`).
   - **Dev servers:** the spike's old pack (`resources/machineengine_fluids`, overlay `me_fluids_88`) was removed from `run/folia-26.2` and `run/paper-26.2`. It replaced the same files and came after ours.

   The draft for this stage:
   - Grid, generated textures, shader templates per version band, the Sodium overlay;
   - the client jar download and cache;
   - checks (the startup format checks of the spike, a GLSL check in CI if `glslangValidator` is present).
6. **Mekanized.** Done (2026-10-16), branches `feature/fluid-types` in both repos; checked headless, the owner's in-game check is open.
   - **Engine:**
     - Machines' world access (`CraftEngineMachineHost`: `fluidAt`, `placeFluid`, `takeFluid`) goes through `WorldFluids`, so the pump and the plenisher handle placeable fluids with no change in Mekanized. Their events carry `MACHINE`: `WorldFluids.place`/`take` gained overloads with a cause.
     - `bucket: <item>` works: a CraftEngine item on a water bucket, recognized by its id (`PaperItems`).
     - The pack builder draws a texture for each such bucket from the vanilla water bucket, the water recolored to the fluid's color, at `machineengine:item/fluid_bucket/<namespace>/<path>`. Water is found by color, because 26.3's empty bucket is a greyscale PNG that Java reads back with other values.
     - A build still downloading at shutdown gets 5 seconds, and a leftover `.part` file is truncated.
   - **Mekanized:**
     - Its 22 fluids are placeable `FluidType`s, made in code from `fluids.txt`, not a definitions file as drafted: `config.yml` `colors` keep working, and `fluids.yml` changes the rest.
     - Look: Mekanism's liquid texture (`mek_liquid/liquid`, `liquid_flow`; steam has its own), tinted with the fluid's color, frame time 2. The texture objects are shared, so the grid holds 2 textures for 22 looks.
     - Buckets: `configuration/buckets.yml` (one `config_factory`, items `mekanized:<fluid>_bucket`, tags `c:buckets` and `mekanized:fluid_buckets`); names in `lang.yml` (en_us `<Name> Bucket`, fr_fr `Seau de …`); 44 tank bucket recipes in `definitions/recipes_buckets.yml`.
   - **Checks:**
     - Mekanized's Paper 26.3 dev server: 22 placeable fluids, 22 looks, 66 key biomes; pack written with 2 textures and 22 bucket textures.
     - glslang on that 22-key shader: every 26.3 variant compiles.
     - `ceSnapshot`: the 22 buckets expand as intended. Its reference (2026-10-09) was already out of date before this stage (ghost items, module `max-level`, block state numbers), so it was not accepted; the owner decides.
   - **Known limit:** an `auto` bucket is a tagged water bucket, so item recipes that match `minecraft:water_bucket` (tank bucket slots) take it as water. Use `bucket: <item>` for fluids that go into tanks.

   The draft for this stage:
   - `fluids.txt` replaced by a definitions file with `fluid:` sections for the placeable fluids;
   - textures from Mekanism's fluid textures (MIT; the notice is already shipped);
   - bucket recipes; pump and plenisher checked with custom fluids.
7. **Docs.** ARCHITECTURE decision entries, §9.5 and §29 updated, the docs site page "World fluids", CONTRIBUTING (what to do for a new Minecraft version: shaders, NMS, format checks).

Each stage ends with its tests and a dev server check, and with the owner's in-game check where something is visible (stages 4–6).

## 6. Decisions to record (after the owner's answers)

- **D46:** world fluids as in WORLD_FLUIDS_PLAN §2 Findings: water carrier, key biomes, neutral water, shader overlays, gap between different fluids.
- **D47:** `ResourceType` is open for subclasses that keep identity semantics. `FluidType` is the first: fluid world settings live on the type, set by code, definitions YAML and `fluids.yml`.

## 7. Owner answers (2026-10-16)

1. **Subclass:** yes, as recommended. `ResourceType` opens, and `FluidType` extends it.
2. **Vanilla files:** download the official client jar.
3. **Reload:** physics, effects and mixing at once; looks after a pack rebuild; fog after a rejoin.
4. **Versions:** every supported version at once (1.20.6 to 26.3), not 26.x first.
5. **Placeable fluids:** all of Mekanized's fluids. That needs `uniform-water` (§4.3).
6. **Gap between different fluids:** 2 areas (about 8 blocks).
7. **Added by the owner:** a fluid without its own texture can use the water texture with any color (§2.2).

New question raised by the research:

8. **`uniform-water: true` by default?** Yes (owner, 2026-10-16).
