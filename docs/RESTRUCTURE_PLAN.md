# Restructure plan (draft, 2026-10-06)

Moves the build to the module layout decided in `ARCHITECTURE.md` D24–D27:

```text
api ← nms:common ← common ← hooks:*    ← plugin ← test ← mekanized (tests only)
          ↑                              ↑
          └──── nms:v1_20_6, nms:v26_3 ──┘
api ← mekanized (compileOnly); plugin runs mekanized as a dev addon
```

**Rules for every stage:**
- Each stage is one or more commits that build and pass `./gradlew test`.
- No gameplay change. The test count must not go down, and the generated Mekanized pack must
  come out byte-identical (diff `build/generated/mekanized` before and after).
- Moves keep Java package names (`fr.robie.machineengine.core`, `.paper`, `.craftengine`, …), so
  each move is a directory move plus build-file changes. Renaming packages is a separate,
  optional last stage.
- Never build jars while the dev server runs; `compileJava` and `test` are fine.
- Use `git mv` so history follows the files.

## Where things go

| Today | Becomes |
|---|---|
| `machineengine-api` | `api` |
| `machineengine-core` | `common` |
| `machineengine-paper` | `common` |
| `machineengine-craftengine` | `common` |
| `machineengine-zmenu` | `hooks/zmenu` |
| reflection on version differences (`ClassPreloader`, `SuitTicker`, `GearModules`, `ZMenuMachineMenus`, `CraftEngineProtection`, the `InventoryView` lookup) | `nms/common` interfaces + `nms/v1_20_6`, `nms/v26_3` (only where it is a real version difference) |
| `machineengine-plugin` | `plugin` |
| `machineengine-test` | `test` (now depends on `plugin`) |
| `machineengine-mekanized` | `mekanized` |
| `machineengine-example` | deleted |

## Stage R1: build conventions (done 2026-10-06)
**Result:**
- Every module now uses `machineengine.platform`.
- `verifyPaperBaseline` checks bytecode with ASM, not by recompiling. A source recompile against 1.20.6
  misses enum → interface changes (`Sound`, `Attribute`, `InventoryView`), which compile both ways but
  fail at runtime.
- On the current code it found two real 1.20.6 breakages, both fixed:
  - `Messages` used `TranslatableComponent.Builder.build()`, whose erased return type differs in
    Adventure 4.17;
  - `PaperAutomation` called `InventoryView.getTopInventory()` directly. It now goes through the
    shared `machineengine-paper` `InventoryViews` helper, which zMenu also uses (moves to `nms` in R5).
- A probe class using `Player.getCurrentInput()`, `Attribute.MAX_HEALTH` and `Sound.getKey()` was
  rejected with four errors.
- The plugin jar holds 315 classes, all major version 65. Tests: 280 of 280 pass.

**What was planned:**
- `libs.versions.toml`:
  - `paper = "26.3.build.156-beta"` (the compile API; Paper 26.x versions have this form, not
    `-R0.1-SNAPSHOT`) and `paper-baseline = "1.20.6-R0.1-SNAPSHOT"`;
  - add the paperweight userdev plugin;
  - drop `adventure-key`, which now comes from Paper.
- Java (D25): toolchain JDK 25 everywhere and `--release 21` everywhere, as today. Paper 26.x
  publishes `org.gradle.jvm.version = 25`, so a Java 21 module refuses to resolve it. The
  conventions call `java { disableAutoTargetJvm() }`. javac on JDK 25 reads the Java 25 API
  classes and still emits Java 21 bytecode.
- `buildSrc`:
  - replace `machineengine.pure-java` and `VerifyPureJava` with a `machineengine.platform`
    convention: `compileOnly` Paper API and CraftEngine (core, bukkit), plus a
    `verifyPaperBaseline` task that checks the compiled classes against the 1.20.6 Paper API, wired
    into `check`;
  - add a `machineengine.nms` convention for userdev modules (dev bundle version,
    `paperweight.reobfArtifactConfiguration = MOJANG_PRODUCTION`, and `--release` per module:
    21 for `v1_20_6`, 25 for `v26_*`).
- **Checks:**
  - `verifyPaperBaseline` passes on the current code, and fails if a 26.x-only call is added on
    purpose;
  - `javap -v` on a class from the plugin jar shows major version 65 (Java 21).

## Stage R2: delete `example` (done 2026-10-06)
**Result:**
- The module is gone. Its engine checks moved to Mekanized `EngineCheckCommands`: `/mekanized
  check-drop`, `check-hopper`, `check-comparator` and `fill <size>`, on a basic bin instead of the
  crate.
- `place` and `network-line` were dropped: `place-set` and `test-cable` already cover them.
- `-PwithExample` became `-PwithAddons`.
- The new commands still need a run on a dev server.

**What was planned:**
- Remove `machineengine-example` and its `devAddons` entry.
- Move anything the dev servers still need (`/mexample hopper-test`, `crate-test` and similar
  checks) to `/mekanized` test commands, or drop it.
- Rewrite the `ARCHITECTURE.md` milestone notes that name it as history, not as a module.

## Stage R3: `api` and `common` (done 2026-10-06)
**Result:**
- Four commits: R3.1 `api`, R3.2 `common` from core, R3.3 paper into `common`, R3.4 craftengine into
  `common`. Each one builds and passes 280 of 280 tests.
- The jars are `machineengine-api` and `machineengine-common`. `common` keeps CraftEngine's
  `bukkit-proxy` (compile) and `core` (tests) dependencies.
- Test sources moved together with their packages, so step 4 needed no separate work.
- Side changes:
  - `-Xlint:-classfile` silences javac warnings about JOML's class files;
  - the CI japicmp line now names `:api`.

**What was planned:**
1. Rename `machineengine-api` → `api`, keeping its jar name.
2. Create `common` from `machineengine-core`.
3. Move `machineengine-paper` and then `machineengine-craftengine` into `common`, one commit each.
4. Merge the test sources of the three modules into `common/src/test`.
5. Update dependents (`zmenu`, `plugin`, `test`, `mekanized`) to `project(":common")`.

## Stage R4: `hooks:zmenu` (done 2026-10-06)
**Result:**
- The module is at `hooks/zmenu` (Gradle `:hooks:zmenu`); the jar keeps the name `machineengine-zmenu`.
- `MachineEnginePlugin` still enables the menus only when zMenu is installed and enabled.
- The plugin jar still contains the zMenu classes and `menus/machine.yml`.
- 280 of 280 tests pass.

**What was planned:**
- Move `machineengine-zmenu` → `hooks/zmenu`, with `api(project(":common"))` and `compileOnly`
  zMenu.
- `plugin` keeps loading it only when zMenu is present.

## Stage R5: `nms` (done 2026-10-06)
**Result:**
- **`nms:common`** holds:
  - `NmsAccess`: the open top inventory, the player's input keys and item gliding;
  - `Nms.get()`;
  - `ReflectiveNms`, the fallback for versions without a module of their own (1.21.x, 26.1, 26.2),
    which headless tests also use.
- **`nms:v1_20_6` and `nms:v26_3`** use userdev against their own dev bundles. The bytecode
  confirms it: v1_20_6 is Java 21 with an `invokevirtual` on `InventoryView`; v26_3 is Java 25 with
  `invokeinterface` and direct calls to `getCurrentInput` and `setGlider`.
- **`NmsLoader`** (plugin) picks the module by exact version.
- **Gone:** `InventoryViews` and Mekanized's `getCurrentInput` and `setGlider` reflection.
- **Repositories:** `repositoriesMode` is now `PREFER_PROJECT`. Paperweight adds seven repositories
  of its own, so the `nms:v*` modules declare theirs and every other module keeps the settings
  ones.
- **Wrong assumption fixed:** the plan expected `ClassPreloader` to survive the Java 25 class
  (LinkageError). It does not: Paper 1.20.6's class loader reads every plugin class with ASM 9.7
  first and throws `IllegalArgumentException`. The plugin failed to load on the first 1.20.6 run.
  Now the preloader skips `fr/robie/machineengine/nms/v`, and `Nms` is installed before it runs.
- **Server runs** (`forceload`, `/mekanized check-drop`, `check-hopper`, `check-comparator`, `test`),
  all as expected (100 dropped, comparator 8/0, 10 in the chest, 2 dust and 10000 J):

  | Server | Version module |
  |---|---|
  | Paper 1.20.6 | `v1_20_6` |
  | Paper 26.3 | `v26_3` |
  | Folia 26.2 | reflective |
  | Folia 1.20.6 | `v1_20_6` |
- **Check commands made re-runnable:** the `check-*` commands now clear their area first. CraftEngine
  does not place a block over the same block, so leftovers from an earlier run made them fail.

**What was planned:**
1. `nms:common`: one interface per version difference, plus a `Nms` entry point that `plugin`
   fills at startup from the server version.
2. `nms:v1_20_6` and `nms:v26_3` on userdev. Start with the existing reflection sites; only
   code that actually differs between versions moves.
3. `common` depends on `nms:common` only.
4. `plugin` shades both implementation modules and sets the jar manifest attribute
   `paperweight-mappings-namespace: mojang`.
5. The `nms:v26_*` classes are Java 25 bytecode, so nothing may load them on 1.20.6:
   - the version is picked by class name, and only the chosen module's classes are loaded;
   - `ClassPreloader` skips the `nms` packages it was not given;
   - shadow minimisation, if used, must keep both implementations.
- **Check:** start `runPaper-1.20.6` and `runPaper-26.3` (and the Folia servers). Each loads,
  picks its own `nms` implementation and reports no problems.

## Stage R6: `plugin` and `test` (done 2026-10-06)
**Result:**
- `machineengine-plugin` became `plugin`, and `machineengine-test` became `test`; the jar names are
  kept. Dev servers are `:plugin:runPaper-*` and `:plugin:runFolia-*`; `run/` is unchanged.
- `test` depends on `plugin` (the assembled wiring, nms included) and on `common`, which the harness
  drives directly.
- The engine tests stay in `test`: they run on the harness.
- README, CI artifact path and `gradle.properties` were updated.
- 280 of 280 tests pass.
- No runtime code changed, so no server run was needed.

**What was planned:**
- `plugin` depends on `common`, `hooks:*` and `nms:*`. It owns the bootstrap, the shadow jar and
  the run-paper/run-folia tasks; `run/` is unchanged.
- `test` depends on `plugin` (`api` scope), with the Paper API on its runtime classpath so
  headless tests still run without a server.
- Engine tests that need only the engine stay in `common/src/test`; tests that need the
  assembled wiring live in `test`.

## Stage R7: Mekanized on `api` only (D27, confirmed by the owner 2026-10-06)
**Survey (2026-10-06):** Mekanized's main code uses 8 internal types. `CoreMachineEngine`,
`MachineInstanceImpl` and `DefinitionCompiler` appear only in its tests, through the harness; those
stay as they are (D28).

| Internal | Used for | Replacement |
|---|---|---|
| `LoadedMachine`, `.instance().context()` | machine data and storages from listeners and commands | `MachineEngine.machineAt` + new `MachineInstance.context()` |
| `MachineLocator.machine`, `isOwnedByCurrentThread` | lookups, Folia ownership | `machineAt`, `Bukkit.isOwnedByCurrentRegion` |
| `MachineLocator.replaceBlock`, `cycleTransportSide` | tier upgrades, configurator on cables | API service |
| `PaperItems` | ItemStack <-> resource, CraftEngine item ids | moves to `api` |
| `PaperWorlds.world` | Key -> World | `Bukkit.getWorld` |
| `MachineMenuService.open` | opening a machine's menu | API service |
| `PositionArguments`, `ClassPreloader` | command arguments, jar-swap protection | move to `api` |
| `Nms`, `PlayerInput` | jetpack input, elytra unit | move to `api` (D28) |

The end state is `compileOnly(project(":api"))` only, with Java 21 bytecode. The `common`
dependency in step 1 is a temporary migration step and must be gone when this stage ends.
1. Rename `machineengine-mekanized` → `mekanized`; temporarily `compileOnly(project(":common"))`.
2. Promote what it uses to `api`, one area per commit, with javadoc and stability markings.
   The internals and their import counts today:
   - `CoreMachineEngine` (15)
   - `MachineInstanceImpl` (13)
   - `PaperItems` (11)
   - `LoadedMachine` (7)
   - `PaperWorlds` (3)
   - `MachineLocator` (3)
   - `PositionArguments` (2)
   - `MachineMenuService` (1)
   - `DefinitionCompiler` (1)
3. Remove the `common` dependency. Mekanized's tests use `testImplementation(project(":test"))`.

## Stage R8: docs and cleanup
- Update the README module table, the rest of `ARCHITECTURE.md` (sections that name
  `machineengine-paper`, `-core` or `-craftengine`) and `MEKANIZED_PLAN.md`. This absorbs the
  README item of `CLEANUP_PLAN.md` Stage 8.
- Optional: rename packages to match modules (`fr.robie.machineengine.common.*`, `.hooks.zmenu`,
  `.nms.*`).

## Order and size
| Stage | Size | Risk | Depends on |
|---|---|---|---|
| R1 conventions | medium | medium (new build checks) | — |
| R2 delete example | small | low | — |
| R3 api + common | large (moves) | low | R1 |
| R4 hooks:zmenu | small | low | R3 |
| R5 nms | medium | medium (needs server runs on both versions) | R3 |
| R6 plugin + test | medium | low | R4, R5 |
| R7 mekanized on api | large | medium (API design) | R6 |
| R8 docs | small | none | any time after R6 |
