# Cleanup plan (draft, 2026-10-04)

The engine and Mekanized grew in fast waves. This plan tidies them without changing behaviour, apart
from the bugs listed under Stage 0. It comes from a read-only audit of the three areas: the engine
modules, `machineengine-mekanized`, and `buildSrc` with the repository.

**Rules for every stage:**
- Each stage is one or more commits that build and pass `./gradlew test`.
- No gameplay change unless the stage says so.
- Never build jars while the dev server runs; `compileJava` and `test` are fine.

**Decisions** (owner, 2026-10-05): Stage 1 takes option A (`context.world()` and `context.machines()`,
the doc updated to match); Stage 5 moves the gameplay numbers into `config.yml`.

## Where things stand

| Area | Size | Main problems |
|---|---|---|
| Engine (api, core, paper, craftengine, zmenu, test) | ~20k lines | `MachineContext` has 44 methods, 13 of them world calls added on the fly; hosts disagree; no engine tests for the world API; ~250 inline fully-qualified names |
| Mekanized | ~10.7k lines | `PaperGear` 783, `MekanizedMenus` 716, `PaperAutomation` 596 lines; persistence written 4 times; "machine at block" lookup written ~15 times; Folia thread risks |
| buildSrc / build | ~2.3k lines | three hand-kept lists must agree (phases, id sets, enabled files); unphased recipes dropped silently; `fetchMekanism` likely fetches on every build |

## Stage 0: bugs found by the audit (behaviour changes, small)
1. **`breakWorldBlock` can remove machines and transports in production.**
   - Cause: `CraftEngineMachineHost.java:396-403` removes any CraftEngine block, while `FakeWorld` refuses.
   - Fix: refuse machine and transport blocks, as `placeWorld*` already does.
   - Test: an engine test that breaking a machine is refused.
2. **Folia: world access that may run off the owning region thread.**
   - **Vein mining and the blasting unit** (`PaperGear` ~439, blast): skip blocks outside the player's region (`Bukkit.isOwnedByCurrentRegion`).
   - **Meka-Tool teleport unit** (`PaperGear` ~769): limit the ray trace, or check that the destination is owned before reading it.
   - **Assemblicator crafting** (`PaperUtility:79`): `Bukkit.getCraftingRecipe(..., getWorlds().getFirst())` runs on a machine region thread. Use the machine's own world.
   - **`/mekanized build`** and the test commands: check that every block is owned by the scheduling region, or schedule per block.
3. **Blocking file writes on region threads.** `Frequencies.teleporter()` saves `teleporters.txt` synchronously on the machine's thread. Move all saving to one async writer (see Stage 3).
4. **`Entangloporters.takeDirty`** is not atomic and can lose a save. Use an `AtomicBoolean`.
5. **Unphased generated recipes are dropped silently.** `recipes_shaped.yml` is written but never installed. Report these recipes in `import-report.txt` instead of writing an unused file.
6. **`fetchMekanism` has no outputs**, so Gradle never treats it as up to date and it likely fetches over the network on every build. Return early when `HEAD` already is the pinned commit.

## Stage 1: engine API shape and doc (decided: option A)
`ARCHITECTURE.md` is binding, and the code has drifted from it:
- §419 describes world effects queued through `MachineContext.world()`; the code calls them immediately on `MachineContext`.
- §832 names a `MachineContext.scheduler()` that does not exist.
- §5 leaves out several modules (zMenu and others).

Options:
- **A (recommended):** group the methods.
  - `context.world()` returns a `MachineWorld` holding the 13 world calls (fluids, blocks, redstone, sound, particles, block property).
  - `context.machines()` holds `storageAt` and `wakeMachine`.
  - The doc is updated to describe immediate calls on the owner thread.
  - Mekanized call sites change mechanically.
- **B:** keep the flat interface; reorder it into sections, document it, and update the doc only.

Either way:
- Write javadoc for the undocumented `MachineContext` and `MachineHost` methods.
- Order the methods of `MachineInstanceImpl.Context` like the interface, with an `at(BlockPosition)` helper (9 repeats).
- The CraftEngine-only `MachineLocator.replaceBlock` and `cycleTransportSide` move to a CraftEngine-side service, or stay and are documented as optional.

## Stage 2: engine tidy (no behaviour change)
1. **Imports instead of inline fully-qualified names**, except where names clash (CraftEngine `Key`/`Direction`). Worst files: `CraftEngineMachineHost` (58), `MachineInstanceImpl` (28), `CoreNetworks` (26), `FakeWorld` (23).
2. **`CraftEngineMachineHost` helpers:** `withController(position, action)` (6 repeats), `world(position)` with a null check (~10), one water/lava ↔ key table, and one world-key lookup shared with `KeptContents` and `MachineEvent`.
3. **`FakeWorld`:** record `playSound`, `particleLine` and `setBlockProperty` so tests can check them. Make ownership checks consistent with the CraftEngine host (`redstonePower`, `placeWorldFluid`).
4. **New `WorldApiTest` in machineengine-test:** fluids, blocks, redstone, `storageAt`, `wakeMachine`, `exposeHandler`, `setStorageCapacity` (including saving it), port direction and sides, `modifiedCost`.
5. **Dead code (checked by grep):**
   - `FakeWorld.brokenDrops`;
   - `MachineInstanceImpl.isDirty` and `resumeClock`;
   - `HandoffService.orphanedCount`, `SimpleRegistry.isFrozen`;
   - `SchedulerState.isSleeping`, `TransferResult.isComplete`, `Transfers.simulateExtract`.

   Keep anything the doc names (`RegistryKey`, `elapsedTicks`), or remove it in the same change as the doc.
6. **zMenu:** replace the deprecated `engine.buildButton(button)` (`ZMenuMachineMenus.java:240`).

## Stage 3: Mekanized shared helpers (no behaviour change)
| Helper | Replaces |
|---|---|
| `paper/PaperWorld`: `machine(Block)`, `position(Block)`, `world(WorldPosition)`, `location(...)`, `give(Player, ItemStack)` | ~15 lookups, 4 world lookups, 4 "give or drop" |
| `paper/persist/LineStore` + `WorldPositions` | the load and save code of teleporters, chunk loaders, entangloporters and radiation; one async save executor |
| `machine/DataKeys`: `string/bool/number` builders, shared `STATUS`/`RUNNING`, `parseInt/Long/Double(value, fallback, min, max)` | 3 `string()` copies, ~10 hand-written keys, 5 parse helpers, duplicated ids |
| `machine/MachineParts.powered()` and `pay(handler, joules, tx)` | 2 `powered()` copies + 4 inline; 11 energy payments |
| `MekanizedContent.resourceType(Key)` and `displayName(type)` | 3 `fluid/` prefix parsers, 3 display-name copies |
| `Frequencies.cost(from, to)` (machine side) | teleport cost written twice |
| `paper/ItemLongs`, `paper/Lore.line()` | PDC long read/write ×3, non-italic lore ×9 |
| `machine/PlatformHooks` | 6 separate static hook setters and volatile listeners |

Also replace the literal `"mekanized"` with `NAMESPACE`, and remove `Entangloporters.channel()` (unused).

## Stage 4: Mekanized structure (moves and splits, no behaviour change)
```
mekanized/                 MekanizedPlugin, MekanizedContent, MekanizedSettings, EditableFiles
  paper/                   PaperWorld, Lore, ItemLongs, PaperResourceNames (from MekanizedPlugin.name)
  paper/persist/           LineStore, WorldPositions
  paper/gear/              GearItems (data, lore, capacity), GearModules (install/remove),
                           SuitTicker (jetpack, flight, effects, magnet), GearTools (disassembler, Meka-Tool, bow, flamethrower)
  paper/automation/        PaperTeleporters, PaperQioDashboard, PaperCardboardBox
  paper/                   PaperTools (+ seismic and network readers), PaperConfigurator (was MekanizedTools),
                           PaperUtility, PaperRadiation, PaperChunkLoaders, PaperEntangloporters, PaperFurnaceRecipes
  paper/menu/              MenuYaml (button DSL), MenuCatalog split by family, SideMenus
  paper/command/           MekanizedCommands, MekanizedDebugCommands (test, test-cable...), MekanizedStructures
  machine/                 DataKeys, MachineParts, PlatformHooks, Frequencies (out of AutomationMachines),
                           BasicMachines + MekanizedRegistry (split MekanizedMachines), DigitalMiner, Qio, ...
  multiblock/              ReactorMachines split: Boilers/Turbines, Fission, Fusion, Sps
```
- `machine/FurnaceRecipes` is the only Bukkit import in `machine/`: move it to the Paper side behind a hook.
- The modification station's registration moves out of `Liquifier.java`.
- The configurator's hand-written multiblock `MEMBERS` set is built from the `Multiblocks.Type` definitions.
- Doc comments name what a class does, not "Phase 6 wave N". The history stays in `MEKANIZED_PLAN.md`.
- Rename `EndgameMachines`, `extrasMenus` and `ExtrasTest` by their content.

## Stage 5: settings (decided: into config.yml)
Move gameplay numbers from code to `config.yml` sections:
- `gear`: capacities, jetpack, module costs, vein limits, damage;
- `radiation`: decay, thresholds;
- `teleporter`: cost, cooldown;
- `chunk-loading`;
- `qio`;
- `digital-miner`.

This is a feature (server owners can tune them), so it needs the owner's approval. Without it, the numbers become named constants in one place per family.

## Stage 6: buildSrc and the import
1. **One declarative content table.**
   - A `ContentPhases.kt` with `Phase(key, title, machines, transports, items, plain, gear, recipeTypes, …)` replaces the phase lists, the 9 id sets, the hand-built list in `ImportMekanism.kt:47-50` and `Machine.phase`.
   - The import writes a `manifest.yml` of the files it wrote. `MekanizedPlugin` installs what the manifest lists, instead of its two `Set.of` lists.
   - A new generated file can then no longer be forgotten, as `recipes_gear_shapeless.yml` was.
2. **Split files:**
   - `MekanismContent.kt` into `BlockStates.kt`, `DisplayStates.kt`, `EnergyCube.kt` and `Lang.kt`. `generateBlocks` (130 lines, 10 parameters) becomes small block builders, with typed data classes instead of `Map` casts.
   - `ImportMekanism.kt` into `Report.kt`, `Assets.kt`, `Recipes.kt` and `Tags.kt`.
3. **Shared helpers:** a `MekanismSource` (asset roots, written 6 times), one pretty `Gson` (7 copies), one fluid list (computed twice), and an item-YAML helper (~7 copies).
4. **Task inputs:**
   - `importMekanism` hashes the `.git` folder: take the commit as an input plus only the folders it reads.
   - Mark it `@CacheableTask`.
   - Wire `sourceDirectory` from `fetchMekanism` instead of repeating the path.
5. **Dead code and small fixes:**
   - two detached doc comments (`ImportMekanism.kt:189-198`);
   - a duplicated `.json` check;
   - an unused parameter;
   - the per-chemical `count` loop;
   - `adaptedIngredients` fails on shapeless recipes, and should get its own report section.

## Stage 7: tests
- **`MekanizedTestKit`:** the engine builder, `item`, `type`, `fill`, `amount`, `box`, and a JUnit extension that resets static state. Today this code is copied in 7 classes, with 29 helper copies.
- **Split `MekanizedChainTest` (696 lines)** by family.
- **Move Paper logic into testable machine-side code:**
  - gear capacity and module rules;
  - teleport cost;
  - radiation dose and shielding;
  - configuration card encoding;
  - gauge dropper;
  - QIO aggregation;
  - persistence line codecs.
- **New cheap tests:**
  - every menu names storages, data and slots its machine has;
  - every `/mekanized build` structure forms.

## Stage 8: repository and docs
- README:
  - the module table is missing zmenu, example and mekanized;
  - say that `build` fetches Mekanism;
  - mention `run/folia-*`.
- `.gitignore`: add `*.log` and `.kotlin/`.
- CI: cache `.mekanism/`.
- Gson and SnakeYAML versions go into `libs.versions.toml`.
- Optional: `-Werror` once the build has no warnings left.

## Order and size
| Stage | Size | Risk | Can run in parallel with |
|---|---|---|---|
| 0 bugs | small | low | — (first) |
| 1 API shape | medium | medium (touches every world call) | 6 |
| 2 engine tidy | medium | low | 3, 6 |
| 3 Mekanized helpers | medium | low | 2, 6 |
| 4 Mekanized structure | large (moves) | low if done after 3 | 6 |
| 5 settings | medium | low | after 4 |
| 6 buildSrc | medium | medium (generated output must stay identical: diff `build/generated` before and after) | 1-4 |
| 7 tests | medium | low | after 3-4 |
| 8 repo/docs | small | none | any time |

**Check for Stages 2-4 and 6:** the generated pack and definitions must come out byte-identical (diff
`build/generated/mekanized` before and after), and the test count must not go down.
