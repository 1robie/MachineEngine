# Paper-Dispatch and FoliaLib plan (2026-10-08)

Commands move from hand-built Brigadier trees to [Paper-Dispatch](https://github.com/1robie/paper-dispatch) (`BaseCommand` / `SubCommand`, `CommandManager`). Scheduling moves from direct calls to Paper's region, entity and global schedulers to [FoliaLib](https://github.com/TechnicallyCoded/FoliaLib). Both libraries are shaded **and relocated**: two plugins that shade the same unrelocated package can load each other's classes.

No gameplay change: every command keeps its name, alias, arguments, permission and output, and every task keeps its thread, delay and period.

## What is where today

**Commands (Brigadier through `LifecycleEvents.COMMANDS`):**
- `common`: `paper/MachineEngineCommands` (`/machineengine`, alias `/me`), registered by `MachineEnginePlugin#onEnable`. Root needs `machineengine.admin`.
- `api`: `paper/PositionArguments`, the optional `[<x y z>] [<world>]` arguments, defaulting to the block the player looks at. Used by both plugins.
- `mekanized`: `paper/command/MekanizedCommands` (`/mekanized`), with `MekanizedDebugCommands`, `EngineCheckCommands` and `MekanizedStructures` adding branches. Registered by `MekanizedPlugin#onEnable`.

**Scheduling.** No `BukkitScheduler`, `BukkitRunnable` or Folia detection anywhere. Everything calls Paper's threaded-regions API directly, which exists on every supported server (Paper 1.20.6+ and Folia), so there is no Paper/Folia branch to remove:
- `common`: `CraftEngineMachineHost` (`scheduleWake`: region `runDelayed`; `post`: region `execute`; `isOwnedByCurrentThread`: `Bukkit.isOwnedByCurrentRegion`), `KeptContents` (region, 1 tick), `CraftEngineItemBridge` (global), `MachineEngineCommands#onRegion` (region), `MachineDebugView` (entity timer, keeps `ScheduledTask`s).
- `hooks:zmenu`: `OpenMenuButton`, `ZMenuMachineMenus` (entity `run` / `runDelayed`).
- `mekanized`: `PaperRadiation`, `PaperEntangloporters`, `PaperChunkLoaders` (global and region), `PaperUtility`, `SuitTicker`, `GearTools`, `PaperAutomation` (entity and region), the four command classes (region `execute` / `runDelayed`), and `PaperWorld#isOwnedByCurrentThread` plus direct `isOwnedByCurrentRegion` calls.
- **Stay as they are:** `MachineHost` (`scheduleWake`, `post`, `isOwnedByCurrentThread`) is the engine's own seam and `FakeWorld` fakes it for headless tests; only `CraftEngineMachineHost`'s implementation changes. `MachineEvent`'s `isPrimaryThread()` async flag is a thread check, not scheduling. `LineStore`'s single-thread file writer is plain JDK I/O.

## What the libraries bring

**FoliaLib.** It is not needed for Folia support: the code already runs on Folia. It gives one wrapper (`runNextTick`, `runAtLocation`, `runAtEntity`, `runTimer`, `runAsync`, …) and one task type (`WrappedTask`) instead of three scheduler APIs with different signatures, futures for "run there, then continue", and `isFolia()` for logs and dev checks. The gain is consistency, not reach.

**Paper-Dispatch.** Declarative commands: one class (or builder) per sub-command, typed arguments, flags, permission and player-only requirements, confirmation, `CommandDispatch` accessors, and unregistering on disable.

## Facts checked (2026-10-08)

- Paper-Dispatch **1.1.0** (latest release, 2026-09-25) on JitPack: `com.github.1robie.paper-dispatch:paper-dispatch:1.1.0`. We use commit `2fafd75` of `main` (the 1.20.6 fix below) until it is tagged: JitPack's `main-SNAPSHOT` still served an older build (`2d5b43d`). The jar holds the API and the implementation (`fr/robie/paperdispatch/**`), no other library. Java 21 bytecode (major 65). Built against Paper 26.2.
- FoliaLib **0.5.1** (latest release) on `https://repo.tcoded.com/releases`: `com.tcoded:FoliaLib:0.5.1`. Only `com/tcoded/folialib/**`. Java 8 bytecode.
- **Neither library is fully 1.20.6-safe**, so neither passes D25 as a whole. The JVM links a call only when it runs, so code that uses newer API is harmless as long as we never reach it. Stage 1's check found 14 such references; 13 remain, listed with their rules in `gradle/paper-baseline-allowed.txt`:
  - `Flags` factories for argument types 1.20.6 lacks: `angle`, `axes`, `blockInWorldPredicate`, `columnBlockPosition`, `columnFinePosition`, `finePosition`, `hexColor`, `rotation`.
  - `CommandDispatch#resolveFinePosition` (`FinePositionResolver`).
  - `requiresConfirmation(true)` (`Commands#restricted`).
  - *Fixed upstream:* every command removal in 1.1.0's `CommandManager` read `PluginMeta#namespace`, so `unregisterAll` & co. threw on 1.20.6. paper-dispatch `2fafd75` derives the namespace from the plugin name (as `namespace()` does by default), so unregistering is safe and the entry is gone.
  - FoliaLib: `isGlobalTickThread()` on Folia (`Server#isGlobalTickThread`). Our `Scheduler` does not offer it.
- `CommandManager` also finds Paper internals by reflection to sync commands; that has to be checked on 1.20.6 (Stage 7).

## Decisions (D35, 2026-10-08)

The owner took the recommendations:
1. **Mekanized's scheduling goes through MachineEngine's API**, not through its own FoliaLib. `api` gets a small MachineEngine-owned `Scheduler` interface (no FoliaLib types: they are relocated, so addons cannot compile against them). `common` implements it with the relocated FoliaLib. Mekanized and other addons call `MachineEngine.scheduler(plugin)`. This follows D27 (`api` is the one module addons compile against).
2. **Mekanized shades its own relocated Paper-Dispatch.** Command classes are generic in the plugin (`BaseCommand<T extends Plugin>`) and relocated, so they cannot be shared through `api`. `PositionArguments` stays in `api` and stays Brigadier-only (no Paper-Dispatch types), so both plugins keep sharing it.
3. **Relocation targets:** `fr.robie.machineengine.libs.paperdispatch` and `fr.robie.machineengine.libs.folialib` in MachineEngine, and `fr.robie.machineengine.mekanized.libs.paperdispatch` in Mekanized.

## Stages

### Stage 1: build, shading and relocation (done)
- `libs.versions.toml`: `paper-dispatch = "2fafd75"` (see "Facts checked"), `folialib = "0.5.1"`.
- `settings.gradle.kts`: JitPack limited to `com.github.1robie.paper-dispatch`, the tcoded repository limited to `com.tcoded`.
- `machineengine.platform`: a `bundledLibraries` configuration (non-transitive, extended by `implementation`). Sources use the original packages; the shadow jar rewrites them.
- `common`: `bundledLibraries(libs.paper.dispatch)`, `bundledLibraries(libs.folialib)`. `plugin`'s `shadowJar` relocates both.
- `mekanized`: the shadow plugin; `bundledLibraries(libs.paper.dispatch)`, relocated. `shadowJar` is now `Mekanized-<version>.jar`, the plain jar has the `plain` classifier, and `plugin`'s `devAddons` takes `shadowRuntimeElements`, so dev servers run the shaded jar.
- **Baseline check for bundled code:** `VerifyPaperBaseline` reads jars too and takes an exact allow-list. `verifyBundledPaperBaseline` (in `check`, every platform module, skipped without bundled libraries) checks `bundledLibraries` against 1.20.6, minus `gradle/paper-baseline-allowed.txt`. An allowed entry for a class the task read that no longer shows up fails the build, so the list stays true across library upgrades. Entries for classes the task did not read are ignored, so one list serves both modules.
- **Relocation check:** `VerifyRelocation` (`verifyRelocation` in `plugin` and `mekanized`, part of `check`) fails if the shaded jar has an entry under an original package.
- Checked: `./gradlew check` passes; the plugin jar holds `fr/robie/machineengine/libs/{paperdispatch,folialib}` (39 + 27 entries), Mekanized's `fr/robie/machineengine/mekanized/libs/paperdispatch` (39), and neither has the original packages; `runPaper-1.20.6` builds `:mekanized:shadowJar`.

### Stage 2: scheduler API (`api`) and implementation (`common`) (done)
- `api.platform.Scheduler`, reached through **`MachineEngine.scheduler(plugin)`**: one per plugin, whose tasks belong to that plugin and stop when it is disabled, as Bukkit tasks do (a single MachineEngine-owned scheduler would keep an addon's tasks running after the addon is disabled). It mirrors what the code uses today and nothing more:
  - global: `run`, `runLater`, `runTimer`;
  - region, at a location or a chunk: `run`, `runLater`, `runTimer` (location only), `isOwnedByCurrentThread`;
  - entity: `run`, `runLater`, `runTimer` with a `retired` callback (the entity was removed), and `isOwnedByCurrentThread`;
  - `runAsync`; `isFolia()`.
  - Immediate `run`s return nothing (no caller cancels them). `runLater`/`runTimer` return `api.platform.Task` (`cancel()`, `isCancelled()`).
  - Delays and periods below 1 tick count as 1 (Folia needs one; FoliaLib would round up with a warning).
  - No `isGlobalTickThread()`: FoliaLib's fails on Folia 1.20.6 (allow-list).
- **Entity tasks, as Paper's `EntityScheduler` behaves today:** if the entity is no longer valid (`Entity#isValid`, a player who left), nothing is scheduled and `run` returns false, `runLater`/`runTimer` null. FoliaLib's `Runnable` entity variants disagree here: on Folia they throw (they wrap the scheduler's `null`), on Paper they run `retired` at once. So `FoliaLibScheduler` uses the `Consumer` variants with its own handle (`EntityTask`): usable at once, a cancelled task never runs again (FoliaLib's task is cancelled at its next run), and a cancelled task's `retired` no longer runs.
- `common`: `paper.FoliaLibScheduler implements Scheduler`, one `FoliaLib` per plugin (`perPlugin()`), installed in `MachineEnginePlugin#onLoad` (`MachineEngineAccess.installSchedulers`) next to the config, cleared by `reset()`. No `cancelAllTasks` on disable: the server cancels a disabled plugin's tasks.
- Tests: `FoliaLibSchedulerTest` (the `EntityTask` handle, the tick floor). Headless engine tests keep using `FakeWorld` through `MachineHost`; `MachineEngine.scheduler` throws without a server.
- Not exercised on a server yet: nothing calls it until Stage 3. Stage 7 checks it on Paper and Folia.

### Stage 3: move `common` and `hooks:zmenu` to the scheduler (done)
- `CraftEngineMachineHost`: `scheduleWake` (region `runLater` at the machine's chunk), `post` (region `run`) and `isOwnedByCurrentThread` use `MachineEngine.scheduler(plugin)`. The `MachineHost` interface and `FakeWorld` stay the same.
- `KeptContents` (region `runLater`, 1 tick; its `plugin` field went), `CraftEngineItemBridge` (global `run`), `MachineEngineCommands#onRegion` (region `run`), `MachineDebugView` (entity `runTimer`, keeps `Task`s).
- `ZMenuMachineMenus` holds a scheduler; its `plugin()` accessor, used only by `OpenMenuButton` to schedule, became `scheduler()`.
- Checked: no `getRegionScheduler`, `getGlobalRegionScheduler`, `getAsyncScheduler`, `getScheduler()`, `isOwnedByCurrentRegion` or `ScheduledTask` left in `common` or `hooks` outside `FoliaLibScheduler`; `./gradlew check` passes.
- **Dev servers (2026-10-08, copies of `run/paper-1.20.6` and `run/folia-1.20.6` in a scratch folder, started with the jars run-paper uses):** `mekanized test` (wake timers: 2 osmium dust, 10000 J left), `test-cable` (network posts: 2 dust through the cable), `check-drop` (KeptContents: 100 of 100 dropped), `check-hopper` (10 of 10 in the chest) all give their expected values on both; startup's "Recipes and tags reloaded" (global task) appears; on Folia `machineengine inspect 40 120 8 world` (region `run` from the console) and `machineengine status` answer. No errors or warnings from MachineEngine, FoliaLib or Paper-Dispatch. Paper 1.20.6 hit the known jansi crash after "Closing Server", with every world saved; Folia closed cleanly.
- Not covered there: the debug view and menus need a player online.
- Found on the way: from the console, `me status` runs vanilla's `/me` emote ("* Server status"): vanilla owns the `me` label, so the alias never reached MachineEngine. Not new; Stage 5 decides what to do about the alias (Paper-Dispatch reports aliases the server declines).

### Stage 4: move Mekanized to the scheduler (done)
- Mekanized has one scheduler, `MachineEngine.scheduler(mekanized)`, installed first in `MekanizedPlugin#onEnable` and kept by `PaperWorld` (`PaperWorld.scheduler()`), so static helpers reach it too and its tasks stop with Mekanized.
- 34 call sites: `PaperRadiation`, `PaperEntangloporters`, `PaperChunkLoaders`, `PaperUtility`, `SuitTicker`, `GearTools`, `PaperAutomation`, and the command classes' region tasks. `PaperWorld#isOwnedByCurrentThread` and the direct `isOwnedByCurrentRegion` calls (`MekanizedDebugCommands#place`, `MekanizedStructures`, `GearTools`) go through `Scheduler#isOwnedByCurrentThread`.
- Left unused, so removed: the `plugin` fields of `PaperUtility`, `GearTools`, `SuitTicker` and `MekanizedCommands`, and `PaperAutomation.setFrequency`'s `plugin` parameter.
- `LineStore` keeps its own file-writer thread: ordered writes on one thread matter there, and FoliaLib's async pool does not guarantee order.
- Checked: no direct Paper scheduler call left in any module outside `FoliaLibScheduler`; `./gradlew check` passes.
- **Dev servers (2026-10-08, fresh copies of `run/paper-1.20.6` and `run/folia-1.20.6`):** on both, `test`, `test-cable`, `check-drop`, `check-hopper` and `check-comparator` (half full 8, empty 0: nested region `runLater`s) give their expected values, `mekanized build dynamic_tank` builds (ownership check), and the radiation and entangloporter timers run without errors. No errors or warnings from MachineEngine, Mekanized or FoliaLib. Paper hit the known jansi crash after "All dimensions are saved"; Folia closed cleanly.
- Not covered there: player-bound tasks (suit ticker, radiation exposure, chargepads, dashboards, frequency setting, the electric bow) need a player online.

### Stage 5: MachineEngine commands on Paper-Dispatch (done)
- `MachineEngineCommands` is a `BaseCommand<Plugin>`: `machineengine`, permission `machineengine.admin`, no alias (see below). Sub-commands: `status`, `report`, `debug`, `inspect` (alias `machine`), `placeholders` (`list`, `purge`), `network`, `registry <registry>` (same suggestions), `reload` (`config`, `recipes`, `tags`). Output, return values and the purge confirmation are unchanged.
  - The simple ones use `SubCommand.builder`. Two nested classes reach the `protected` overloads that take a Brigadier builder: `PositionCommand` (`[<x y z>] [<world>]`, the world names suggested) and `RegistryCommand` (the greedy `registry` argument with its suggestions).
  - New: `/machineengine` and `/machineengine reload` alone print their usage (a Paper-Dispatch node always runs); they used to be "incomplete command".
- `PositionArguments` (`api`, Brigadier types only) gains `positionArgument()`, `worldArgument()` and `resolve(context, default)` → `Position` (world, x, y, z), or null after telling the sender what is wrong. Paper-Dispatch logs a thrown `CommandSyntaxException` as SEVERE instead of showing it, so `resolve` reports invalid coordinates itself. `optionalPosition` now uses the same code and stays for Mekanized until Stage 6.
- `MachineEnginePlugin`: a `CommandManager` (`trackCommand`, `flushRegistrations` in `onEnable`, `unregisterAll` in `onDisable`). `plugin` has Paper-Dispatch as `compileOnly`; the classes come with `common`.
- Checked: `./gradlew check` passes (the baseline check covers the new `PositionArguments`).
- **Dev servers (2026-10-08, fresh copies of `run/paper-1.20.6` and `run/folia-1.20.6`), from the console, same results on both:** `machineengine` and `machineengine reload` print usage; `status`, `report`, `inspect 40 120 8 world`, `machine 40 120 8` (the alias, no world: the console's), `network 40 120 21 world` (the test cable: 1 transport, 8000/tick, its 2 endpoints), `placeholders list`, `placeholders 40 120 8 world`, `registry machineengine:machine_type` (145 entries), `reload config|recipes|tags` all answer as before; `inspect` alone says "Give a position", `inspect 40 120 8 nowhere` "Unknown world 'nowhere'", `registry nothing_here` "No registry", `debug` "Only players". Shutdown unregisters without a warning on both (the reflective path works on 1.20.6). Both then hit the known jansi crash in Log4j at "Closing Thread Pool", after every world was saved (the owner's Folia run folder has four earlier crash logs of the same kind).
- **The `me` alias is gone (owner decision, 2026-10-08).** Paper-Dispatch reported it at startup ("the server declined these aliases: me"): vanilla's `/me` owns the label, so `/me status` was always vanilla's emote and the alias never reached MachineEngine, before this plan too.

### Stage 6: Mekanized commands on Paper-Dispatch (done; dev-server check below)
- `MekanizedCommands` is a `BaseCommand<Plugin>` (`mekanized`, no root permission): `frequency <name>` (player only, as before), `build <structure>` (admin, one sub-command per structure), `radiation` (admin: `clear`, `<sieverts>`), and the debug and check sub-commands from `MekanizedDebugCommands#commands()` and `EngineCheckCommands#commands()` (`test`, `place-set`, `test-chemistry`, `test-cable`, `check-drop`, `check-hopper`, `check-comparator`, `check-block-options`, `check-item-data`, `fill <size>`), admin as before. `/mekanized` and `/mekanized build` alone print their usage.
- `PositionSubCommand` (package-private): an admin sub-command at `[<x y z>] [<world>]` (`Default.PLACEMENT`), run on the thread owning the position, with optional required arguments before it (`fill <size>`). The handlers keep their Brigadier `CommandContext` (from `dispatch.getContext()`) to reply.
- `MekanizedPlugin` registers through its own `CommandManager` and unregisters on disable.
- `PositionArguments#optionalPosition` and its `PositionCommand` interface are removed: nothing builds Brigadier trees by hand any more.
- Checked: `./gradlew check` passes; Mekanized's bundled Paper-Dispatch passes the baseline check (12 allowed) and the relocation check.

### Stage 7: dev-server checks
On `runPaper-1.20.6`, `runPaper-26.3`, `runFolia-1.20.6` and `runFolia-26.2` (headless, console commands piped in):
- Both plugins enable; the log shows FoliaLib's detected platform.
- `me status`, `me report`, `me registry machineengine:machine_type`, `me reload config`, and `me inspect <x y z>` from the console. Wrong permission and wrong arguments give the library's messages.
- `mekanized check-drop`, `check-hopper`, `check-comparator`, `check-block-options`: they run multi-tick region tasks, so they exercise the scheduler on every server.
- Shutdown saves cleanly, with no "task still running" warnings. On 1.20.6, Paper-Dispatch's registration, reflective command sync and unregistering do not throw. If they do, that is a fix upstream in paper-dispatch, not a workaround here.
- Results go into this plan, as in BLOCK_NUMBERS_PLAN.md.

## Risks
- **Paper-Dispatch on 1.20.6.** It is built and tested against 26.2. The lazily linked `Flags` factories are covered by the allow-list. The reflective `CommandManager` paths are covered only by the Stage 7 check.
- **Brigadier tree size.** We use no flags today, so the node-count limit in the library's README does not apply. Adding flags later should stay under 4 per command.
- **Behaviour drift in commands.** Paper-Dispatch builds its own tree, so suggestions, usage text and failure return values may differ slightly from today. Stage 7 compares them.
