# MachineEngine — Master Architecture & Implementation Specification

> Revision 2 — 2026-10-01.
> Supersedes `MachineEngine_Master_Architecture_Prompt.md` (revision 1).
> Revision 2 records binding design decisions, integrates findings from the CraftEngine `dev` branch (26.10-SNAPSHOT), and resolves the open questions of revision 1.

## 0. How to Use This Document

This document is the single source of truth for MachineEngine's architecture.

It is written for people working on MachineEngine. Server owners and addon developers have the
[documentation site](https://1robie.github.io/plugins/machineengine/) (its source: the `1robie.github.io`
repository); building, dev servers and releases are in [CONTRIBUTING.md](../CONTRIBUTING.md).

- **MUST / MUST NOT** are binding. **SHOULD** is the default unless a documented reason exists. **MAY** is optional.
- §1 (Decision Log) overrides any other section if they ever disagree.
- Items marked **[VERIFY]** depend on CraftEngine behavior that was not fully confirmed in source; the implementer MUST check them against the pinned CraftEngine snapshot before relying on them, and update this document.
- Changing a decision requires a new entry in §1 with a date and rationale. Do not silently diverge.

---

# 1. Decision Log

| ID | Decision | Rationale |
|----|----------|-----------|
| D1 | **Cross-region transfers use asynchronous handoff with escrow.** Same-thread transfers are synchronous transactions. | Folia region threads cannot share a synchronous transaction. See §16. |
| D2 | **Machine state is persisted in the CraftEngine block-entity data** (`BlockEntityController.saveCustomData/loadCustomData`). Chunk/world `PersistentDataContainer` is used only for data not owned by a single machine block (e.g. network metadata, world-level ID allocators). | CraftEngine stores custom block states and their block entities together in its own region storage. Co-locating machine state with the block avoids block/data desync on crash. (Revision 1 tentatively chose chunk PDC; changed after reviewing CraftEngine's storage model.) |
| D3 | **Missing content is kept as opaque placeholders**, never deleted: missing machine types, resource types, components and codecs. | Uninstalling an addon must not destroy world data. See §21. |
| D4 | **Unloaded machines are frozen in time by default** (no catch-up). Machine types MAY opt in to a bounded, closed-form catch-up. Networks never catch up. Chunks are never loaded by MachineEngine to process or transfer. | Matches vanilla expectations, keeps simulation deterministic, prevents unloaded-chunk exploits and load-time lag spikes. See §18. |
| D5 | **Two registry kinds:** *static* registries (types, codecs, categories) freeze after registration; *reloadable* registries (recipes, data-driven definitions) are swapped atomically as validated immutable snapshots. | Types must be stable for the server lifetime; content like recipes must be reloadable. |
| D6 | **Identity uses Adventure `net.kyori.adventure.key.Key`** in the public API. CraftEngine's own `net.momirealms.craftengine.core.util.Key` appears only inside the CraftEngine integration (`common`, package `craftengine`; D26 lets any module see CraftEngine, but identity stays Adventure `Key`). | Paper ships Adventure; `adventure-key` is a tiny, server-free artifact usable in pure unit tests. |
| D7 | **CraftEngine is a hard runtime requirement** of the MachineEngine plugin. MachineEngine builds against the **latest CraftEngine dev snapshot** (currently `26.10-SNAPSHOT`). The `api` and `core` modules still MUST NOT depend on CraftEngine. | Product decision. The adapter boundary is preserved for testability and to contain CraftEngine API churn. |
| D8 | **Paper only** (no Spigot). Folia compatibility is designed in from day one and validated in milestone M4. | CraftEngine is Folia-capable; the threading model must not need a rewrite later. |
| D9 | **Resource hierarchy is not generic.** `ResourceCategory`, `ResourceType`, `ResourceStack` carry runtime identity (`Key`), not Java type parameters. | Generics are erased; they would give false safety. Runtime identity is the contract (§6). |
| D10 | **Amounts are `long`** in category-defined base units. Fractional quantities use fixed-point scaling declared by the category. No floating point in transfers. | Determinism, no rounding drift, no overflow surprises (saturating math). |
| D11 | **Transactions follow a nested snapshot/rollback model** (inspired by the Fabric Transfer API). `simulate` = open transaction, then abort. | Proven model; simpler and safer than reserve/commit bookkeeping. |
| D12 | **MachineEngine has its own event bus** in the API module; it does not use Bukkit events internally. No per-transfer events in hot paths. | Core must be server-free and fast. |
| D13 | **Addons register through a callback submitted in their `onLoad`** (or Paper bootstrap). MachineEngine runs callbacks in dependency order during its own enable at `STARTUP` and then freezes static registries — before worlds load. | All plugins' `onLoad` run before any `STARTUP` enable; worlds load after `STARTUP` enables. |
| D14 | **CraftEngine block definitions reference machine types lazily by key.** The `machineengine:machine` block behavior stores the machine-type `Key` at config-parse time and binds it after MachineEngine's registry freeze (and again after every CraftEngine reload). | CraftEngine parses block configs during its own enable, before MachineEngine's addons have registered; it also re-parses on `/ce reload`. |
| D15 | **Inputs of a process are consumed atomically when the process starts** and held in a persisted in-flight buffer; cancelling a process returns them. | No resource is ever silently lost (§10). |
| D16 | **Modifier stacking follows Minecraft attribute semantics:** `base → +ADD → ×(1+ΣADD_MULTIPLIED_BASE) → ×Π(1+MULTIPLY_TOTAL) → clamp`. | Familiar to the ecosystem and order-independent within each stage. |
| D17 | **License: GPL-3.0.** | Project owner decision (2026-10-01); compatible with CraftEngine (GPL-3.0). |
| D18 | **Java package root `fr.robie.machineengine`**, Maven group `fr.robie`. | Project owner decision (2026-10-01). |
| D19 | **Supported Minecraft versions: 1.20.6 to 26.3.** Compile against the 1.20.6 Paper API; test on both ends of the range. | Project owner decision (2026-10-01). 1.20.6 is the first version with item data components and Paper's lifecycle command API, both of which MachineEngine relies on. |
| D20 | **Machine GUIs use zMenu** (latest, 1.1.1.9) as an optional dependency: menus are zMenu inventory files, machine values are zMenu placeholders, interactions are custom zMenu button types. | Project owner decision (2026-10-02): everything customizable by server owners in YAML. |
| D23 | **Player-facing text uses CraftEngine client-side translations** (`lang` entries in a `machineengine` CraftEngine pack, MiniMessage, `%s` arguments) instead of a messages file: each player sees their own language, server owners edit one YAML file, and every message has an English fallback for players without the resource pack. Admin command output stays English. | Project owner suggestion (2026-10-02). |
| D22 | **Keep-contents is configured per block** (`keep_contents` on the CraftEngine behavior), not per `MachineType`: whether a block keeps its contents is a server/pack choice, and the same machine type can back both kinds of blocks. | Delegated (2026-10-02). |
| D21 | **YAML definitions are a built-in pseudo-addon `definitions`**, registered after every addon. Static content (resource categories and types, recipe types, machines) is defined at startup and freezes with the rest; recipes are a reloadable provider. Machine behavior comes from registered `BehaviorType`s (built-in `machineengine:processing` and `machineengine:storage`; addons add their own). | Delegated (2026-10-02): server owners can add whole machines without Java, while D5 (freeze) still holds. |
| D24 | **Module layout: `api` ← `common` ← `hooks:*` / `nms:*` ← `plugin` ← `test`.** `api` holds only public types. `common` implements it (today's `core`, `paper` and `craftengine` merged). `hooks:<name>` holds one optional third-party integration each (`hooks:zmenu` first). `nms:common` declares interfaces for server internals; `nms:v1_20_6` and `nms:v26_3` implement them with paperweight userdev, and `common` calls only `nms:common`. Versions without a module of their own (1.21.x, 26.1, 26.2) use the reflective `ReflectiveNms` from `nms:common`. `plugin` assembles and shades everything and owns the run-paper dev servers. `test` depends on `plugin`. `machineengine-example` is removed. Supersedes the module list in §5. | Project owner decision (2026-10-06). |
| D25 | **Compile against the latest Paper API (26.3), use only API that exists in 1.20.6.** Supersedes the compile clause of D19; the supported range (1.20.6 – 26.3) is unchanged. A `verifyPaperBaseline` check (part of `check`, every module except `nms:v*`) reads the compiled bytecode and fails the build when a reference into Paper, Bukkit, Adventure, Guava, Gson, JOML, SnakeYAML or Brigadier does not link against the 1.20.6 Paper API: a missing class or member, a class/interface mismatch (e.g. `Sound` is an enum in 1.20.6 and an interface in 26.x, so a call compiled against 26.x fails on 1.20.6), or a static mismatch. It also rejects class files newer than Java 21. Recompiling the sources against 1.20.6 would not catch the mismatches. Anything that differs between versions (newer API, or a type whose binary shape changed, such as `InventoryView` class → interface) goes behind an `nms:common` interface instead of reflection. **Bytecode is Java 21** everywhere (the plugin jar must load on 1.20.6), built with JDK 25 because Paper 26.x is Java 25; only `nms:v26_*` modules emit Java 25 bytecode, and their classes MUST NOT be loaded on a 1.20.6 server. | Project owner decision (2026-10-06). |
| D26 | **Every module may use Paper and CraftEngine, including `api`.** Supersedes the last sentence of D7 and the "pure Java" rule of §5; `verifyPureJava` is removed. CraftEngine stays a hard runtime requirement (D7). Identity in the public API stays Adventure `Key` (D6) by default. Headless tests (`TestEngine`, `FakeWorld`) keep running without a server as long as they do not touch Bukkit statics. | Project owner decision (2026-10-06): CraftEngine is required anyway, so hiding it behind an adapter costs more than it saves. |
| D27 | **Mekanized is a separate addon jar that compiles against `api` only** (plus Paper and CraftEngine) and is a dev addon of `plugin`. The internals it uses today (`CoreMachineEngine`, `MachineInstanceImpl`, `LoadedMachine`, `PaperItems`, `PaperWorlds`, `MachineLocator`, …) are promoted to `api` as needed; until then it MAY compile against `common`. | Delegated (2026-10-06): it proves third parties can build content on the public API, and replaces `example` as the reference addon. |
| D28 | **Version-specific calls are public API:** `NmsAccess`, `PlayerInput` and `Nms` live in `api` (`fr.robie.machineengine.api.platform`). `nms:common` keeps only the reflective fallback and depends on `api`. **D27 applies to Mekanized's main code:** its tests may keep using the `test` harness, which exposes engine internals; making the harness return API types is a later, separate step. | Project owner decision (2026-10-06): `api` stays the one public module addons compile against. |
| D29 | **MachineEngine provides shared energy and fluids:** `machineengine:energy` (one category, one type) and the global fluid category `machineengine:fluid` with `machineengine:fluid/water` and `machineengine:fluid/lava`, registered by the engine itself and always present. Their unit, scale and bucket size are read from `config.yml` (`resources.*`, restart-only) when they are built in `onLoad`, and are then fixed (`Energy`, `Fluids`, `BuiltInResources` in `api.resource`). Addons add their own fluids to the global category (`Fluids.create(key)`, keys `<namespace>:fluid/<name>`), so a port or pipe accepting the category moves every registered fluid; MachineEngine registers no other fluid. A `*:fluid/*` type outside the category is a startup warning. Renamed entries keep loading through **registry aliases** (`WritableRegistry.alias(from, to)`, static registries only, no chains): saved data, definitions and tags resolve the old key, data is saved under the new one. Narrows §4 and §33: the engine now ships these meanings, but nothing requires an addon to use them, and addons may still define other energy or fluid systems. | Project owner decision (2026-10-06), docs/DEFAULTS_AND_CONFIG_PLAN.md: Mekanized's energy, water and lava moved into the engine, `mekanized:*` keys kept as aliases. |
| D30 | **Settings are typed `ConfigKey`s, never raw `getConfig()` calls or constants.** A key holds its path, default, range and comment; `ConfigFile` (`api.config`) reads a file through them, writes every missing key back with its default and comment, keeps invalid values in the file (warning, default used) and keys it does not know, and saves only when it added something. `config.yml` is generated from `EngineConfig.SPEC`: there is no bundled copy. `/machineengine reload config` swaps the values atomically; restart-only keys keep their value and are reported. Addons read engine settings with `MachineEngine.config()` and may use `ConfigFile` for their own files. | Project owner decision (2026-10-06), docs/DEFAULTS_AND_CONFIG_PLAN.md. |
| D31 | **Mekanized's content is kept by hand, not imported at build time.** Textures, models, CraftEngine configuration, definitions, `chemicals.txt`, `fluids.txt` and `LICENSE-MEKANISM.txt` live in `mekanized/src/main/resources/mekanized/` and ship as they are. They were imported once from Mekanism commit `4718860` (branch 26.1) and are edited since; the import tooling (`fetchMekanism`, `importMekanism` and its buildSrc code) is removed. The only build step left is `contentManifest`, which lists the `.yml` files of `pack/configuration/` and `definitions/` in `mekanized/manifest.yml`, so a file added there is installed without a hand-kept list. Supersedes K4 of MEKANIZED_PLAN.md. The MIT notice and the "based on Mekanism" headers stay. | Project owner decision (2026-10-07): the content is maintained by hand from now on. |
| D32 | **Item logic uses CraftEngine item behaviors, and per-item numbers live in the CraftEngine item config.** MachineEngine registers the generic behaviors any addon can use from YAML: `machineengine:energy_item` (`max-energy`, `max-input`, `max-output`) and `machineengine:resource_item` (`resource`, `capacity`, `fill-from-machines`, …), `machineengine:storage_reader`, `machineengine:wrench`, the `keep_contents` loot function, and the item processor `machineengine:resource_lore` (lore drawn per player from `client-bound-data`, translated on the client; addons add their own processors for their own data). Behaviors written with Bukkit types extend `api.paper.PaperItemBehavior`. Addons register their own gameplay types in their `onLoad` (`ItemBehaviors.register`; Mekanized: `mekanized:module_holder`, …). Engine and addon behaviors find each other through `api.item` interfaces with `ItemBehavior#let`, never by class or item id: `ResourceItem`, `ItemCapacityModifier`, `MachineTool`. **Amounts live in CraftEngine item data**, `minecraft:custom_data` → `machineengine.resources.<type>` (`PaperItemResources`), not in Bukkit PDC; addons declare how their old PDC keys convert (`migratePdcLong`), on first read. Machines see items through `ItemResources` (`api.item`, platform-installed `Access`, faked in headless tests). A machine block offers a right-click to the held item's `useOnBlock` first when the item has a `MachineTool` behavior, and opens its menu when the item passes (CraftEngine asks the block first). Resource keys in item behaviors bind lazily (D14) and unknown ones are reported with unbound blocks. | Project owner decision (2026-10-07), docs/CE_BEHAVIORS_PLAN.md: generic behaviors in the main plugin, Mekanized's in Mekanized, numbers next to the item, CraftEngine item data; simple costs stay Java with YAML options (delegated). |
| D33 | **Multiblocks are an engine feature** (`api.multiblock`), no longer Mekanized's. A `MultiblockType` has a `MultiblockShape`: a `Box` (a hollow box within size limits, casings on its edges, an allowed interior, the anchor at its lowest corner) or a `Pattern` (layers of rows and a character key, one anchor character, matched in four horizontal rotations unless `rotate: false`). Types are registered in `Registries.MULTIBLOCK_TYPE`; data-driven definitions declare them in a `multiblocks:` section and members with the behavior type `machineengine:multiblock_member` (`MultiblockDefinitions`). Members are machines with `MultiblockMember` (frame, valve, port, modal; an addon may keep its own port-mode data key). A structure forms automatically when a member loads or is placed and finds it complete, and unforms when a member is removed; the anchor sizes its storages from the type's `capacities` and re-checks itself every few seconds. `Multiblocks.detect` only reads blocks through a `blockAt` function, so it is tested without a server. | Project owner decision (2026-10-07): engine, layer patterns, four rotations, automatic forming. |
| D34 | **Per-block numbers live in the CraftEngine block config** (or in the block's `config_factory` row, D36). The `machineengine:machine` behavior takes `storages: {<id>: {capacity: N}}`, which replaces the machine type's capacity for that block (upgrades apply on top, a runtime `setStorageCapacity` still wins), `ports: {<id>: {rate: N}}` (the same for a port's rate per tick, readable by behaviors with `BlockOptions#portRate`), and `options:`, which the machine reads with its own `ConfigKey`s through `MachineContext#options()` (`api.config.BlockOptions`: missing is the key's default, invalid is reported once and replaced by the default or clamped). `machineengine:transport` takes `rate:` (`TransportType#withRate`). The host gives a machine its block's options (`MachineHost#options`); Java keeps every number as the default. A multiblock's numbers are its anchor block's options: `MultiblockType.Sizes` gets them with the structure, an `AnchorTask` reads them from the anchor's context, and a port's `MultiblockMember.PortRate` gets the port block's. Options follow a CraftEngine reload: the host reads them from the block's current state (a reload builds new states for the same ids, `LiveBlockStates`), and on `CraftEngineReloadEvent` every loaded machine forgets its copy (`MachineInstanceImpl#optionsReloaded`) and every loaded transport rejoins its network with its new rate. World-wide rules stay in the plugin's `config.yml` (D30). | Project owner decision (2026-10-08), docs/BLOCK_NUMBERS_PLAN.md: CraftEngine block config over a definitions section. |
| D35 | **Commands use Paper-Dispatch, scheduling uses FoliaLib; both are shaded and relocated.** MachineEngine bundles Paper-Dispatch and FoliaLib in `common` (`bundledLibraries`) and relocates them to `fr.robie.machineengine.libs.paperdispatch` and `fr.robie.machineengine.libs.folialib`. Neither library's types appear in `api`: addons schedule through a MachineEngine-owned `api.platform.Scheduler` (`MachineEngine.scheduler(plugin)`: tasks belong to that plugin; nothing is scheduled once the server is stopping; implemented with FoliaLib), and an addon that wants Paper-Dispatch bundles and relocates its own copy (Mekanized: `fr.robie.machineengine.mekanized.libs.paperdispatch`). `PositionArguments` stays in `api` with Brigadier types only. `MachineHost` and `FakeWorld` are unchanged. D25 applies to bundled code too: `verifyBundledPaperBaseline` checks the libraries against the 1.20.6 API, and the problems our code never reaches are listed in `gradle/paper-baseline-allowed.txt`, each with the rule that keeps it unreachable (e.g. never ask Paper-Dispatch for confirmation, which uses `Commands#restricted`); 1.20.6 problems on paths we need are fixed in the library itself; `verifyRelocation` fails when a jar holds an unrelocated library. | Project owner decision (2026-10-08), docs/DISPATCH_AND_FOLIALIB_PLAN.md: the owner's command library and a common scheduler wrapper; relocation so other plugins' copies cannot clash. |
| D36 | **Mekanized's CraftEngine content is written with CraftEngine templates and config factories.** Generic templates live in MachineEngine's `templates.yml` (`machineengine:block/machine`, `machineengine:block/transport`, `machineengine:block_state/solid`, `facing`, `active`, `facing_active`, `display_rendered`, `display_rendered_facing`, `display_rendered_facing_6`), Mekanized-only ones in Mekanized's `configuration/templates.yml` (settings, whole-block shapes, family templates, item templates); both are rewritten at every start, and an owner who wants another template copies it under another name. Items and blocks stay separate entries (no inline `block:` in `block_item`). Families of identical blocks (transports, tiered storage, factory machines, basic machines, storage blocks) are `config_factory` sections, one row per block, each making the block, its item and its display items; one-of-a-kind blocks are short `blocks:` entries naming a shape template. **A template never holds a per-block number (D34).** Amends D34: a block's numbers may sit in its factory row (`rate: 8000`, or `behavior: {storages: …, ports: …}` merged into the family template) instead of on a written-out block; they stay in the file owners edit, next to the block's id. Each family stays in the file it was in, so a server that kept an edited old file never defines an id twice. `:plugin:ceSnapshot` checks on a dev server that CraftEngine expands every block and item as in `mekanized/ce-snapshot.json`. | Project owner decision (2026-10-08), docs/CE_TEMPLATES_PLAN.md: split engine/Mekanized templates, separate items and blocks, templates rewritten at start, numbers in factory rows. |
| D37 | **Per-player data is an engine feature (`api.player`).** One `MachinePlayer` per online player (`MachineEngine.get().players()`) holds the values of registered `PlayerDataKey`s: `persistent` (saved, through a `VersionedCodec`) or `session` (dropped on quit), optionally reset on death, optionally migrated once from an old PDC entry (`migratePdc`). `PlayerSetting`s (`bool`, `choice`, `whole`, `decimal`) are values players change themselves with `/machinesettings` or the zMenu settings menu; the server owner sets their defaults and locks them in `config.yml` (`players.setting-defaults`, `players.locked-settings`). Keys and settings are Adventure `Key`s in the addon's namespace, in static registries (`PLAYER_DATA`, `PLAYER_SETTING`) that freeze with the others; saved values of unregistered keys are kept and saved again (D3). Saving goes through a pluggable `PlayerDataStorage` chosen with `players.storage` (restart-only): `machineengine:pdc` (the player's PDC, default; cannot serve offline players, since 1.20.6 Paper cannot read an offline player's PDC) and `machineengine:files` (one file per player, loaded in `AsyncPlayerPreLoginEvent`, written atomically off-thread); addons register more in `PLAYER_STORAGE`. Data is saved every `players.save-interval` seconds when changed, on quit and on disable, each player on their own thread. Switching storage does not move data. `DataBinary` moved to `api.data` for storages. | Project owner decision (2026-10-08), docs/PLAYER_DATA_PLAN.md: engine API, pluggable storage, settings by command and zMenu; the rest delegated. |
| D38 | **A transport is drawn with 4 items: `center`, `arm`, `arm_pull` and `arm_push`.** They are named by the `connection_items` prefix of `machineengine:transport`. The arm models point north, and `CableArmsElement` turns each arm's item display to its side (left rotation, `turn(Direction)`); pull and push sides use `arm_pull` / `arm_push`. A prefix without `arm` items may still name one item per side (`<prefix>/<direction>[_pull\|_push]`), for addons. Mekanized generates its 80 parts with the item template `mekanized:item/cable_part` from 8 shared shape models (`block/cable/shape/{thin,thick}/<part>`) and one texture per transport (factory rows `shape`, `texture`); the 380 per-side models and the unused Mekanism transmitter models are gone. | Project owner decision (2026-10-09), docs/CABLE_ARMS_PLAN.md: one rotated arm instead of a model per side; old models deleted outright (no other servers yet); per-side fallback kept. |
| D39 | **Machine menus are drawn with CraftEngine images in the zMenu title and CraftEngine items in slots.** The title holds what does not move (panel, slot frames, bar frames) as `<image:…>` and `<shift:…>` tags that CraftEngine replaces in the open-screen packet. What moves is a CraftEngine item in a slot, named by placeholders in the button's `material` (zMenu resolves them before its CraftEngine loader) and colored with zMenu's `dyed-color` component. A fill bar covers 3 slots (52 px; cell k draws `[18k, 18k + 18)`, never below or left of its slot, which clients clip). Placeholders: `storage_<id>[_slot_<n>]_cell_<k>`, `process_cell_<k>`, `storage_<id>[_slot_<n>]_color` (`api.paper.ResourceColors`, colorers like `ResourceNames`), `data_<id>_cell` (a percent as 0–16), `side_<group>_<side>_mode`. `ZMenuMachineMenus` redraws every button whose material holds a machine placeholder, all in the same tick, at the menu's `update-interval` and on `machineChanged` (zMenu's `update` would refresh only name and lore, each button on its own timer). Mekanized ships the textures (Mekanism's, MIT), `gui.yml` and `MenuLayout`, which writes each title from the widgets it places; players need the resource pack. | Project owner decision (2026-10-09), docs/MENU_TEXTURES_PLAN.md: require the pack, Mekanism's textures, layered images in the title; the rest delegated. |
| D40 | **Upgrades and modules are added and removed from menus, through machine actions and lists.** A `MachineType` declares named `MachineAction`s (a click: left, right, shift, drop, or a shift-click from the player's inventory, with the held stack as a `ResourceStack` and a row index; the outcome says what it used, what it gives and a message) and `MachineList`s (rows of text fields). `MachineInstance#runAction` runs one on the owner thread, refuses unknown or failing ones, and posts `MachineEvents.ACTION` when it did something. zMenu shows them with the `machineengine_action` button (`action`, `index`, `list`, `input`, `empty`: what a row past the end shows) and the placeholders `list_<id>_size` and `list_<id>_<index>_<field>`; the live redraw clears buttons that hide themselves. Every type with upgrade slots gets the action `upgrade` and the list `upgrades` (`UpgradeActions`) unless it declares its own; `MachineInstance#upgradeRoom` says how many more fit or why none. Upgrade items get the CE item behavior `machineengine:upgrade` (`UpgradeItem`, `UpgradeItems`; an item whose id is an upgrade key stays a reported, deprecated fallback), right-clicking a machine with one installs it (sneak: the stack), and `machineengine_upgrades` opens an upgrades menu (Mekanized: one shared `mekanized_upgrades`). Gear modules get levels and an on/off switch (`name[:level[:off]]`; old lists read as level 1, on), scaled as in Mekanism, with every module number in its item config. The modification station is a powered machine with gear and module slots and a menu: installing costs energy and time (block config, D34), its module list removes and toggles, and removing an energy unit that would lose energy needs the same click again within `confirm-seconds`; the sneak-right-click install stays as a shortcut. | Project owner decision (2026-10-09): the proposals of docs/UPGRADES_MODULES_PLAN.md accepted as written; module numbers configurable (2026-10-10). |
| D41 | **A resource's menu color is part of its type.** `ResourceType` has an optional color, `0xRRGGBB`, set once when the type is built (`ResourceType.of(key, category, color)`, `Fluids.create(key, color)`) and never changed, so identity by reference (D9) holds. `ResourceColors.of` reads the type's color first, then the colorers (addons color types by rule), then white. Water and lava take `resources.fluid.water-color` and `lava-color` in config.yml (restart); a definition's `resource-types` entry takes `color: "#RRGGBB"`; Mekanized's chemicals and fluids take their list's color, overridable by the `colors` map of its config.yml (restart); heat's color is fixed in code. The fluid tank's and heat bar's labels take the storage's color (`<color:…_color%>`). Mekanized no longer registers a colorer. | Project owner decision (2026-10-10): plan docs/RESOURCE_COLOR_PLAN.md, with addon YAML colors, colored menu labels and heat on the type all accepted. |
| D42 | **Mekanized is a separate repository** (`1robie/Mekanized`, package `fr.robie.mekanized`), built against the published `machineengine-api` and `machineengine-test` (JitPack, group `com.github.1robie.MachineEngine`), or against a MachineEngine checkout next to it (a composite build). Supersedes the "dev addon of `plugin`" part of D27; D31 and D36 now belong to Mekanized's own docs. Every engine module is published (`machineengine.publishing`). The engine checks that needed no Mekanized content are `/machineengine check drop|hopper|comparator|fill`, on blocks only the dev servers install (`plugin/src/dev`). | Project owner decision (2026-10-11), with the docs website (DOCS_PLAN.md): two public repositories, Mekanized's history kept (`git filter-repo`). See MEKANIZED_SPLIT_PLAN.md. |
| D43 | **The Bukkit events are public API:** `MachineEvent` and its subclasses, `MachineEngineReloadEvent` and `PlayerMachineInteractEvent` move from `common` (`fr.robie.machineengine.paper.event`) to `api` (`fr.robie.machineengine.api.paper.event`), so a plugin listening to them compiles against `machineengine-api` alone. `BukkitEventBridge`, which fires them, stays in `common`. A breaking package change, made right after 0.1.0 while no third-party plugin is known to use them; it ships in 0.1.1. | Project owner decision (2026-10-11), after the docs showed listeners needed `machineengine-common`. |
| D44 | **Chat and action bar messages go through MessageFlow** (`com.github.1robie.MessageFlow:message-flow`, bundled in `common` and relocated to `fr.robie.machineengine.libs.messageflow`; never in `api`). Amends D23. `EngineMessage` (`common`, `paper.message`) holds every message under its `machineengine.*` key with an English default; `EngineMessages` owns the `MessageManager`. Each player reads `plugins/MachineEngine/lang/<their client's language>.yml`, else the same language in another country, else `messages.default-language`; a missing key falls back to the default language, and missing keys are only written into it. `en_us.yml` is written from the code, `fr_fr.yml` is bundled, server owners add others. Admin command output is translatable too. MessageFlow's translator renders `Component.translatable("machineengine.<key>")` server-side, so `api` and core code (refusals, position arguments) send translatable components with an English fallback. Item lore and the settings menu stay CraftEngine client-side translations (an item or a menu is shared by its viewers). `messages.prefix` in config.yml. | Project owner decision (2026-10-13), plan docs/MESSAGEFLOW_PLAN.md; MessageFlow is the owner's library, extended for it (per-player languages, translator, 1.1.x). |
| D45 | **Logging goes through MessageFlow everywhere** except the `api` signature `ConfigFile.load(Path, ConfigSpec, java.util.logging.Logger)` (addon-facing; the engine passes `Logger#toJul()`). Core classes take an injected MessageFlow `Logger` (`MachineEngineBuilder.logger`, or `julLogger` for a plain JUL one, so callers need no MessageFlow on their classpath); the Paper side and hooks use its global logger. Every line is a template with `%placeholders%` and a context tag (`[core]`, `[players]`, `[craftengine]`…). On Paper lines go through the plugin's `ComponentLogger` (levels kept). `logging.debug` in config.yml turns debug lines on. | Project owner decision (2026-10-13), plan docs/MESSAGEFLOW_PLAN.md. |
| D46 | **Custom fluids in the world are vanilla water tracked by MachineEngine.**<br>• **Look:** the client recognizes each fluid by a biome water color code (client-side key biomes, injected into the registry data and placed per 4×4×4 cell with the client's exact biome lookup). A terrain core shader per version band (and a Sodium one) draws each fluid's texture, tint, opacity and glow, and fades it into the water around it.<br>• **Water colors:** every biome's water color is one neutral color (`world-fluids.uniform-water`, default on), which lets the shader tell 22+ looks apart.<br>• **Physics:** attribute modifiers and damping while inside, and spreading steered by the engine.<br>• **Spacing:** different custom fluids keep 2 cells apart.<br>• **Not used:** fake blocks, and changes to server biomes.<br>• **Renderers (2026-10-17):** vanilla, and Sodium 0.9+ (26.2+, the only Sodium that reads shaders from resource packs) through a patched Sodium shader. `world-fluids.textures: auto` uses the texture grid only where Sodium takes the shader (26.2+) and tints the water texture before, so no player sees the grid.<br>• **Edges (2026-10-21):** padding never puts a fluid's key within 2 cells of another fluid (two keys blended are no fluid), and the soft edge draws a fluid fully from half its key: Sodium blends each corner from the blocks around it. Owner accepted the slight fade of tips pointing at another pool (`fluid-gap: 3` removes it). | Project owner decisions (2026-10-14 to 2026-10-21), from the stage 0 spike measured in game and by simulation (docs/WORLD_FLUIDS_PLAN.md §2 Findings, docs/FLUID_TYPES_PLAN.md §4.3). |
| D47 | **`ResourceType` is open for subclasses that keep identity:** `equals`/`hashCode` are final and Object's (D9), and every field is final. **`FluidType extends ResourceType`** is the first:<br>• it carries a fluid's world mode, look, physics, mixing rules, effects and bucket;<br>• it is set from code (`FluidType.builder`), from definitions YAML (`fluid:` section) and from the server owner's `fluids.yml`;<br>• effect, reaction and texture types are registries, so definitions can use addons' kinds by key;<br>• water and lava are `FluidType`s (`VANILLA`), and `Fluids.create` returns a `FluidType` that lives only in tanks. | Project owner decision (2026-10-16), plan docs/FLUID_TYPES_PLAN.md. |
| D48 | **Work on `develop`, release from `main`.** `develop` is the default branch; every push to it (not docs-only) is a dev build `<next>-dev.<run>` on the rolling GitHub pre-release `dev` and the Hangar Snapshot channel. `main` moves only by a pull request opened by the owner from `develop` or `hotfix/*` (the required `Release source` check), merged with a merge commit after `Build (CraftEngine pinned)`; the merge commit is then tagged by hand. `release.yml` and `maven.yml` refuse a tag not on `main`. Everyone else's pull requests target `develop`. Mekanized follows the same model (its README "Branches and releases"). See CONTRIBUTING.md "Branches" and docs/BRANCHING_PLAN.md. | Project owner decision (2026-10-22): `main` protected and equal to the last release; dev builds available to testers without a release. |

---

# 2. Technical Baseline

| Item | Value |
|------|-------|
| Server platform | Paper (and Folia from M4). Spigot unsupported. |
| Minecraft version | **1.20.6 – 26.3** (D19). 26.3 matches CraftEngine `dev`'s `latest_supported_version`. |
| Paper API (compile) | Compile against the **26.3** Paper API, restricted to API present in **1.20.6** and enforced by `verifyPaperBaseline` (D25). Version-specific code lives only in `nms:v*` modules (paperweight userdev, Mojang-mapped jars, no reobf). |
| Runtime test matrix | Smoke tests on Paper 1.20.6 and on the latest supported 26.3 build (and Folia equivalents from M4). |
| Java | Bytecode `--release 21` (required by 1.20.6 and the same floor as CraftEngine) for every module, including the plugin jar and `api`; build toolchain JDK 25, which Paper 26.x requires (its API jar is class version 69). Exception: `nms:v26_*` emit `--release 25` and are only loaded on 26.x servers (D25). Tests run on JDK 25. |
| Build | Gradle (Kotlin DSL), multi-module. `xyz.jpenilla.run-paper` for dev servers, `net.minecrell.plugin-yml.paper` for `paper-plugin.yml`, Shadow for the plugin jar. |
| CraftEngine repository | `https://repo.momirealms.net/snapshots/` (dev builds) — releases at `https://repo.momirealms.net/releases/`. |
| CraftEngine artifacts | `net.momirealms:craft-engine-core:26.10-SNAPSHOT` and `net.momirealms:craft-engine-bukkit:26.10-SNAPSHOT`, both `compileOnly`, available to every module (D26). |
| CraftEngine version policy | Track the newest dev snapshot. The version is a single property in `gradle.properties` (`craftengine_version`). CI runs one job against the pinned snapshot and one against the newest snapshot so breakage is detected early. |
| Annotations | `org.jetbrains:annotations` (`@ApiStatus.*`, `@Nullable`, `@NotNull`). |
| API dependency footprint | `api`: Paper API and CraftEngine (`compileOnly`, D26) + JetBrains annotations. |
| License | GPL-3.0 (D17), same as CraftEngine. |

---

# 3. Project Goal

Design and implement **MachineEngine**, a server-side Minecraft machine and infrastructure framework, inspired by the extensibility philosophy of NeoForge, implemented as a Paper plugin, and integrated with CraftEngine for custom blocks.

MachineEngine provides infrastructure for: machines, resources, resource storage, resource transfer, ports, capabilities, recipes, processes, networks, transports, upgrades and modifiers, persistence, scheduling, events, developer APIs, validation, and debugging/observability.

Third-party plugins (addons) MUST be able to add completely new resource systems and machine systems without modifying MachineEngine source code. No client-side mod is required.

---

# 4. Core Architectural Principle

> **MachineEngine provides mechanisms, not meanings.**

MachineEngine owns the infrastructure to register, store, transfer, process, network, persist, schedule and execute resources and machines. Addons define what those resources and machines mean.

The core MUST NOT assume a resource is FE, EU, mana, fluid, item, gas, heat, steam, water, oil, electricity or magic. These are examples of resource systems that addons may define. Even first-party resource systems (e.g. the Paper item category, §23.4) are registered through the same public SPI as any addon.

Exception (D29): MachineEngine ships a shared energy (`machineengine:energy`) and a global fluid category with water and lava (§9.5), so addons and definitions agree on them without depending on each other. Nothing in the engine requires them: machines, ports and networks treat them like any other resource, and addons may still define their own systems.

---

# 5. Modules and API Boundaries

Layout decided in D24–D28 (2026-10-06). Every module compiles against the Paper API 26.3 and CraftEngine (`compileOnly`), restricted to API present in 1.20.6 except in `nms:v*` (D25, D26). Gradle paths are short (`:api`, `:hooks:zmenu`); jars keep the `machineengine-` prefix.

```text
api            Public API + SPI only. Includes the Paper-facing services addons use
               (api.paper: items, menus, machine blocks, command arguments) and the
               version access interfaces (api.platform: Nms, D28).
common         Implementation of the API: engine, Paper integration (bootstrap, lifecycle,
               commands, scheduler bridge, PDC, item resource category, vanilla interop) and
               the CraftEngine integration (block behavior, block-entity controller, ticker,
               persistence bridge). Reaches version-specific code only through Nms.get().
hooks:zmenu    Optional machine menus on zMenu (D20). One hooks:<name> module per optional
               third-party plugin.
nms:common     ReflectiveNms: the fallback for versions without an nms:v* module (1.21.x,
               26.1, 26.2). The interfaces are in api.platform (D28).
nms:v1_20_6    paperweight userdev implementation for 1.20.6 (bytecode Java 21).
nms:v26_3      paperweight userdev implementation for 26.3 (bytecode Java 25; never
               loaded on 1.20.6). Every other module emits Java 21 bytecode.
plugin         Shaded plugin jar = common + hooks + nms. Picks the nms implementation at
               startup. Owns the run-paper / run-folia dev servers.
test           Headless test harness (fake worlds, fake regions, fake clock, in-memory
               DataView) and the engine's integration tests. Published for addon authors.
```

Mekanized, the Mekanism-style content addon, is its own repository since D42
(https://github.com/1robie/Mekanized), built against the published `api` and `test`.

Future modules: more `hooks:*`, and official content plugins (Energy, Logistics, Chemistry, Industry, Utilities) as separate addons.

Dependency direction (MUST be enforced by Gradle, no cycles):

```text
api ← nms:common ← common ← hooks:*    ← plugin ← test
          ↑                              ↑
          └──── nms:v1_20_6, nms:v26_3 ──┘
```

### API stability tiers

| Tier | Marking | Guarantee |
|------|---------|-----------|
| Public API | default in `api` | Binary compatible within a major version. |
| SPI (implement-me interfaces) | `@ApiStatus.OverrideOnly` | Same as public API; new methods only with defaults. |
| Experimental | `@ApiStatus.Experimental` | May change in any minor release. |
| Internal | `@ApiStatus.Internal` or outside `api` module | No guarantee. Addons MUST NOT use it. |

Non-extendable API types are marked `@ApiStatus.NonExtendable`. Implementation classes live in `common` and are never returned with their concrete type. Exception: the `test` harness hands them out to tests (D28).

### API versioning

- One semantic version for the API (`MachineEngineApi.VERSION`). Pre-1.0 (`0.x`): breaking changes allowed in minor versions, documented in `CHANGELOG.md`.
- Addons declare the API range they require when registering (§8.3). Mismatch → clear startup error naming the addon.
- CI runs `japicmp` (or equivalent) against the last released API jar; a binary break without a major bump fails the build.
- Persistence formats are versioned separately (§20.3).

---

# 6. Identity and Keys

- All IDs are Adventure `Key`s (`namespace:value`). Namespace rules follow Adventure (`[a-z0-9_.-]`). MachineEngine's own namespace is `machineengine`.
- Examples: `machineengine:machine` (CE block behavior type), `energyplugin:fe`, `mymod:steam`, `mymod:refinery`.
- **Naming:** `RegistryKey<T>` = a typed key of an entry in a registry (`registry key + entry key`). This avoids confusion with the "resource" concept (revision 1 used `ResourceKey` for both).
- Runtime identity checks use `Key` equality plus reference identity of the registered object. Hot paths compare registered objects by reference (they are canonical singletons after freeze), never by string.
- Each registered object exposes its `Key` and its owning addon (`AddonId`) for diagnostics.

---

# 7. Registries

### 7.1 Kinds

```java
interface Registry<T> {                 // read view, available after freeze
    RegistryKey<Registry<T>> key();
    Optional<T> get(Key id);
    T getOrThrow(Key id);
    boolean contains(Key id);
    Optional<Key> keyOf(T value);
    Stream<T> stream(); Set<Key> keys();
    Optional<AddonId> owner(Key id);
}

interface StaticRegistry<T> extends Registry<T> {}          // frozen after registration
interface ReloadableRegistry<T> extends Registry<T> {       // snapshot-based
    long generation();                                       // increments on every swap
}
```

- Registration happens only through a `RegistrationContext` handed to the addon's callback (§8). There is no global mutable `register(...)` after freeze; calling registration methods after freeze throws `RegistryFrozenException` with the addon name.
- Duplicate key → validation error naming both owners.
- Values MUST be immutable definitions.
- New registries can be created by addons (`ctx.createRegistry(...)`), with the same lifecycle rules.
- **Aliases (D29):** `WritableRegistry.alias(from, to)` makes an old key of a static registry mean an entry, after a rename or a move to another addon. `get` and `contains` follow aliases; `keys()` and iteration do not include them; `aliases()` lists them. An alias may not be an entry, may not point to another alias, and may not use a reserved namespace (validation error, the alias's addon is disabled). Saved data, definitions and tags resolve keys through the registry, so they follow aliases; data is saved under the entry's own key.

### 7.2 Built-in registries

Static: `ResourceCategory`, `ResourceType`, `ResourceComponentType`, `CapabilityType`, `MachineType`, `ProcessType`, `RecipeType`, `NetworkType`, `TransportType`, `UpgradeType`, `ModifierType`, `StorageCodec` (as `VersionedCodec`), `ResourceConverter`, `EventType`.

Reloadable: `Recipe`, `ResourceTagContribution` (tag contents, §9.3), recipes from data-driven definitions (§29).

Implemented in M1: resource category/type/component type, converter, storage codec, event type (static) and resource tag (reloadable). The machine-related registries arrive with M2.

### 7.3 Reloadable registry semantics

1. A reload builds a complete new snapshot off to the side.
2. The snapshot is fully validated (§24). If validation fails, the reload is rejected and the old snapshot stays live.
3. The swap is atomic (single volatile reference). Readers see either the old or the new snapshot, never a mix.
4. **Initial load vs. reload.** At startup there is no previous snapshot, so a provider whose addon causes errors is left out and the others load. On a later reload, any error rejects the whole reload.
5. Runtime objects hold `Key` references and re-resolve when the `generation` changes. If a referenced entry disappeared, the dependent object degrades safely and an error is recorded on the machine. Exception, decided in M2: a **running process captures its outputs, per-tick costs and duration when it starts**, so a recipe reload never changes or cancels a process already running; only processes started afterwards see the new recipes.

---

# 8. Lifecycle, Load Order and Addon Registration

### 8.1 Plugin descriptor

MachineEngine is a Paper plugin (`paper-plugin.yml`):

- `load: STARTUP` (MUST: static registries must be frozen before worlds load, and CraftEngine itself is `STARTUP`).
- `dependencies.server.CraftEngine: { required: true, load: BEFORE, join-classpath: true }`.
- `folia-supported: true` once M4 passes; until then `false`.

Addons declare `MachineEngine` as a required server dependency with `load: BEFORE` and `join-classpath: true`. Paper's dependency system handles presence and load order; MachineEngine does not re-implement it.

### 8.2 Phases

```text
[Paper bootstrap]   MachineEngine bootstrapper: create service locator, logging.
[All onLoad]        CraftEngine.onLoad, MachineEngine.onLoad, Addon.onLoad …
                    MachineEngine.onLoad registers the CE block behavior type
                    `machineengine:machine` (CE registry is still open here).
                    Addons call MachineEngine.registration(...) to SUBMIT callbacks.
[STARTUP enable]    CraftEngine.onEnable → parses block configs (machine-type keys stored
                    unresolved, D14).
                    MachineEngine.onEnable:
                      1. Registry creation (built-ins)
                      2. Addon dependency resolution (API range, inter-addon requires)
                      3. Run registration callbacks in topological order
                      4. Validation (whole registry graph)
                      5. Freeze static registries
                      6. Build first reloadable snapshots (recipes …)
                      7. Bind CE block definitions to machine types (D14)
[World load]        Worlds load; CE loads chunks and block entities → MachineEngine
                    controllers decode machine state (Machine loading).
[POSTWORLD enable]  Other plugins enable (addon onEnable may use the frozen API).
[Runtime]           Ticking, transfers, reloads of reloadable registries, CE reloads
                    trigger re-binding (D14).
[Shutdown]          Stop scheduling, flush dirty machine state, mark CE chunks unsaved,
                    let CE save.
```

If validation fails at step 4, MachineEngine logs a full diagnostic report (§24), and **disables the offending addons' content**, not the whole framework. Blocks of disabled content load as placeholders (D3).

### 8.3 Addon registration API (sketch)

```java
public final class RefineryAddon extends JavaPlugin {
    @Override public void onLoad() {
        MachineEngine.registration(this, Registration.builder()
            .requiresApi("[0.1,1.0)")
            .requires("energyplugin", "[2.0,)")       // registration ordering + version check
            .register(ctx -> {
                ResourceCategory fluid = ctx.resourceCategories().register(key("mymod:fluid"),
                        ResourceCategory.builder().baseUnit("mB").build());
                ctx.resourceTypes().register(key("mymod:oil"), ResourceType.of(fluid));
                ctx.machineTypes().register(key("mymod:refinery"), RefineryMachine.TYPE);
            })
            .build());
    }
}
```

- `MachineEngine.registration(...)` and `MachineEngine.api()` are the only static entry points; they delegate to a single instance set once at bootstrap (the only permitted static mutable state, documented as such). They belong to the Paper integration (M3). In M1 the engine is started with `MachineEngineBuilder` (core) or `TestEngine` (test harness), both taking `AddonRegistration`s.
- Implemented API (M1): `AddonRegistration.builder(name, version).requiresApi(range).requires(addon, range).after(addon).register(ctx -> …)`. Definitions carry their own key (`ResourceType.of(key, category)`) and are registered with `ctx.resourceTypes().register(type)`.
- Ties in the dependency order are broken by addon name, so the order does not depend on plugin load order.
- Optional ordering (`after`) does not disable an addon when the other one is missing.
- Dependency errors (missing, incompatible version, cycle) are reported per addon with the full chain.

---

# 9. Resource System

### 9.1 Hierarchy

```text
ResourceCategory      e.g. energyplugin:energy, mymod:fluid, machineengine:item
  └── ResourceType    e.g. energyplugin:fe, mymod:oil
        └── ResourceVariant = (ResourceType, ResourceComponents)   what it is
              └── ResourceStack = (ResourceVariant, long amount)     how much
```

- `ResourceVariant` is the unit storages and transfers work with: two equal variants always stack.

- `ResourceCategory` defines: base unit name, fixed-point display scale (D10), and its `MergePolicy`. (Allowed component types and a display formatter hook are deferred until a use case needs them.)
- `ResourceType` belongs to exactly one category and carries default components. Tag membership lives in the reloadable tag registry, not on the type.
- `ResourceStack` is an immutable value object. Storages SHOULD NOT hold `ResourceStack` objects internally in hot paths; they store `(type, components, amount)` in primitive-friendly structures and materialize stacks on demand.
- Amount math is saturating (`Math.addExact` guarded); overflow is a validation/transfer rejection, never a wrap-around.

### 9.2 Components (metadata)

Revision 1's "metadata" is modeled as typed components, similar to Minecraft data components:

```java
ResourceComponentType<T>   registered, has Key + StorageCodec<T> + equality semantics
ResourceComponents         immutable map ResourceComponentType<?> → value
```

Examples: `mymod:temperature` (long, milli-Kelvin), `mymod:pressure`, `magicplugin:purity`.

- Two stacks are **stackable** iff same type and their components are equal under the category's `MergePolicy`.
- `MergePolicy` is category-defined. Default: `STRICT` (all components equal). Categories MAY provide a custom merger, e.g. steam averaging temperature weighted by amount. Merging MUST be deterministic and integer-based.

### 9.3 Matching, predicates, tags

```java
ResourcePredicate         (ResourceVariant, ResourceTags) → boolean, composable (and/or/negate)
ResourceMatcher           ResourcePredicate + optional amount requirement; used by ports, recipes
ResourceTagContribution   adds ResourceTypes to a tag Key; reloadable; several addons may
                          contribute to the same tag, each with its own contribution key
ResourceTags              snapshot of tag → members, rebuilt on every tag reload
```

Predicates take the tag snapshot as an argument, so they stay valid across reloads. `predicate.bind(tags)` turns one into a plain `Predicate<ResourceVariant>` for storage filters.

Built-in predicate builders: by type, by category, by tag, by component (equals / range). Example:

```java
ResourceMatcher coolant = ResourceMatcher.of(
    ResourcePredicate.category(FLUID)
        .and(ResourcePredicate.tag(key("mymod:coolant")))
        .and(ResourcePredicate.component(TEMPERATURE, t -> t < 500_000)));
```

**Matching never implies conversion.**

### 9.4 No implicit conversion

MachineEngine MUST NEVER convert one resource into another automatically (FE→EU, water→steam, mana→FE, heat→energy …). Conversion happens only through:

- an explicitly registered `ResourceConverter` (pure, deterministic `(input stack) → (output stack, consumed amount)`), invoked by a machine, a port with an explicit converter binding, or a transport that declares it; or
- an explicit machine/process.

`ResourceConverterRegistry` lookups are by `(from type, to type)` and return all registered converters; the caller chooses. Nothing in core calls a converter on its own.

### 9.5 Built-in energy and fluids (D29)

- `Energy` (`api.resource`): category and type `machineengine:energy`; `Energy.type()`, `Energy.category()`, `Energy.is()`.
- `Fluids`: the global category `machineengine:fluid`, `Fluids.water()`, `Fluids.lava()`, `Fluids.any()`, `Fluids.bucket()`, `Fluids.create(key)` for an addon's fluid, and the world mapping `worldFluid(type)` / `fromWorld(minecraft:water|lava)`.
- Unit, scale and bucket size come from `config.yml` (`resources.energy.*`, `resources.fluid.*`). `MachineEnginePlugin.onLoad` loads the config and calls `BuiltInResources.install(config)` before any addon loads; without a server the getters build them from the defaults (`J`, `mB`, scale 0, bucket 1000). Installing again with other settings throws: registries cannot change while the server runs.
- The engine registers them, and the energy cost modifier `StandardModifiers.processCost(Energy.category())`, as `MACHINE_ENGINE`.
- YAML definitions name them like any resource (`{ type: machineengine:energy }`, `{ category: machineengine:fluid }`) and may declare fluids in the global category (`custom:fluid/oil: { category: machineengine:fluid }`).
- Every fluid is a `FluidType` (D47, `api.resource.fluid`): `WorldMode` (`NONE` in tanks only, `VANILLA` water and lava, `PLACEABLE` in the world, D46), `FluidLook`, `FluidPhysics`, `FluidMixing`, effects and `FluidItems`. `Fluids.worldFluid` gives a placeable fluid's own key, and `Fluids.fromWorld` finds it while the engine runs. The values in effect (with `fluids.yml`) come from `api.paper.WorldFluids`.

### 9.6 World fluids (D46)

Placeable fluids (`WorldMode.PLACEABLE`) live in `common` `paper.fluid`; plans: docs/WORLD_FLUIDS_PLAN.md (spike), docs/FLUID_TYPES_PLAN.md (product).

- **Carrier.** A placed fluid is vanilla water. `FluidCells` remembers which water block holds which fluid (numbered by `FluidKeys`), saved in chunk PDC (`FluidChunkCodec`). `WorldFluidRuntime` follows buckets, flow, level changes, machines and commands: decisions at HIGH (a refusal cancels), tracking at MONITOR. `FluidRules` holds the placement rules (gap, conflict) and the padding.
- **What the client sees.** Each fluid look gets a key: client-only biomes whose water color is a code (`KeyPalette`). `FluidCells` gives each 4×4×4 biome cell a key: the fluid most of its readers hold (`BiomeZoom`, the client's own lookup), else padding or neutral water. The keys go into chunk packets (CraftEngine's biome remapper, `FluidBiomeRemapper`) and into biome-only updates, resent for every chunk a changed cell can color (the client redraws only the chunk an update is for).
- **Version access.** `api.platform.WorldFluidNms` (internal), from `NmsAccess.worldFluids()`: registry data injection of the key biomes, biome sections and updates, viscosity modifiers. `nms/v26_3` serves 26.x, `nms/v1_20_6` 1.20.6, `ReflectiveWorldFluids` 1.21.x.
- **Resource pack.** `FluidPackService` builds the overlay `machineengine_world_fluids` in CraftEngine's resources from the official client jar (`VanillaClient`, cached) and, on 26.2+, the newest Sodium (`SodiumShaders`). `ShaderPatch` patches the terrain shader of each version band, in two dialects (vanilla, Sodium): the vertex reads the key out of the water color, the fragment draws the fluid's texture (`TextureGrid`) or tint. Built aside and swapped in whole; a shader import that would not resolve fails the build.
- **Checks.** glslang on every version and define set (tools/world-fluids, CONTRIBUTING.md); simulations of the whole color chain (plan §4.3, §4.4).

---

# 10. Storage and Transactions

### 10.1 Interfaces

```java
interface ResourceHandler {
    long insert(ResourceVariant variant, long maxAmount, TransactionContext tx);
    long extract(ResourceVariant variant, long maxAmount, TransactionContext tx);
    List<StorageView> views();                      // snapshot: (variant, amount, capacity) per slot
}
abstract class SnapshotParticipant<S>               // base for state that takes part in transactions
```

Extraction is by exact variant; filtered extraction goes through `views()` (see `Transfers.move`). The API ships `SingleVariantStorage` (one slot, merge-policy aware) and `CombinedResourceHandler` (slots, fills existing stacks first) as building blocks; they live in the API, not core, because addons construct them directly.

Storages are composable (slotted, single-type tank, filtered, capped-rate wrappers). Third parties can implement their own; the transaction contract is mandatory.

### 10.2 Transactions (D11)

```java
try (Transaction tx = engine.transactions().openOuter()) {
    long moved = Transfers.move(source, target, filter, 1000, tx).moved();
    if (moved == 1000) tx.commit();          // otherwise auto-abort on close
}
```

- Nested: `tx.openNested()`; aborting a nested transaction rolls back only its changes.
- Participants snapshot state lazily on first modification in a transaction and restore on abort.
- `simulate` = open a transaction, perform, read the result, abort. There is no separate code path, so simulation and execution cannot diverge.
- A transaction is **confined to one owning thread** (Paper main thread or one Folia region). Touching a participant owned by another thread inside a transaction throws `WrongThreadException` (debug) / is rejected (production). Cross-region movement uses handoff (§16).
- Commit callbacks (`tx.onCommit(...)`) are used for side effects such as wake-ups, events and dirty marking; they never run on abort.
- Multi-resource operations (consume 100 FE + 10 water + 2 iron, produce 1 steel) are one transaction: if any step fails, everything aborts.

### 10.3 Results

```java
record TransferResult(Status status, long requested, long moved, @Nullable MachineError reason) {
    enum Status { COMPLETE, PARTIAL, REJECTED }
}
```

Simulation is a mode, not an outcome (revision 1's `SIMULATED`/`ROLLED_BACK` statuses are removed).

**Invariant (tested by property tests):** for any sequence of inserts/extracts/aborts/commits, the total amount of each resource type across all participants changes only by explicit creation/destruction calls (process outputs, voiding ports), never by transfers.

---

# 11. Capabilities and Ports

### 11.1 Capabilities

```java
CapabilityType<T>      registered, Key-identified, carries the Java interface T
CapabilityProvider     machine-side: <T> Optional<T> getCapability(CapabilityType<T>, @Nullable Side)
```

- Lookups are by `CapabilityType` reference (O(1) array index assigned at freeze), never by class scanning.
- Addons define new capability types freely (e.g. `magicplugin:mana_link`).
- Capabilities have a lifecycle: valid while the machine is loaded; holders receive an invalidation callback on unload/remove; caching a capability past invalidation is a bug detected in debug mode.
- Built-in capability types are infrastructure-only: `machineengine:resource_handler`, `machineengine:redstone_control`, `machineengine:comparator_output`, `machineengine:inspectable`.

### 11.2 Ports

A **port is a configured exposure of a capability on a set of block faces**:

```text
Port
 ├── id (Key, unique within the machine type)
 ├── capability (e.g. resource_handler backed by one of the machine's storages)
 ├── direction: INPUT | OUTPUT | INPUT_OUTPUT | DISABLED
 ├── sides: set of relative faces (FRONT/BACK/LEFT/RIGHT/UP/DOWN), resolved via block facing
 ├── filter: ResourceMatcher
 ├── rate limit (per tick) and capacity view
 └── connection rules (which transports/networks may attach)
```

- Sides are relative to the machine's facing; the CraftEngine adapter maps the block-state facing property to absolute directions (§22.4).
- Port configuration defaults come from the `MachineType`; per-instance overrides (player side configuration) are part of `MachineState`.
- Ports are resource-agnostic.

---

# 12. Machines: Definitions vs Instances

```text
MachineType        immutable definition: key, ports, storages layout, capabilities,
                   behavior factory, scheduling hints, catch-up policy, break policy, codecs
MachineInstance    one machine in the world (runtime object, owned by one thread)
MachineState       persistent mutable state (storages, process, config, upgrades, custom data)
MachineContext     what the behavior may touch: own state, own storages, clock, scheduler
                   handle, neighbor capability lookup, event bus, diagnostics
MachineBehavior    addon-provided logic (SPI): onLoad, onTick, onWake(reason), onUnload,
                   onRemove, catchUp (optional)
MachineController  framework-owned glue between a world host (CE block entity) and the
                   instance; addons never implement it
```

- Definitions are immutable after freeze; runtime state is mutable and persistent.
- The same principle applies throughout: `RecipeType/Recipe`, `ProcessType/ProcessInstance`, `NetworkType/Network`, `TransportType/Transport`, `UpgradeType/UpgradeInstance`.
- `MachineBehavior` MUST NOT access the Bukkit/CraftEngine world directly in core logic. World access (reading blocks and fluids, placing and breaking blocks, redstone, particles, sounds, block properties) goes through `MachineContext.world()` (`MachineWorld`). Its calls happen at once, on the owner thread, and never touch blocks of another region (§17). Other machines are reached through `MachineContext.machines()` (`OtherMachines`).
- Machines are not required to use recipes, processes or networks.

---

# 13. Recipes and Processes

- `Recipe` describes a transformation (inputs as `ResourceMatcher`s, outputs as stack templates, optional conditions). It does not define execution.
- `RecipeType` defines recipe shape and matching; `Recipe`s live in a **reloadable** registry (D5).
- `RecipeMatcher` finds candidate recipes for a machine's inputs; machines SHOULD cache the last matched recipe and re-match only on input change.
- `ProcessType` defines execution (duration, per-tick or up-front resource costs, parallelism). `ProcessInstance` is persisted in `MachineState` with progress, recipe key, and the **in-flight input buffer** (D15).
- **M2 implementation:** one built-in timed process behind `MachineContext.process()` (`ProcessControl`: `start`, `advance`, `cancel`) for `ProcessingRecipe`s (inputs consumed at start, per-tick costs, outputs at the end), and a ready-made `ProcessingBehavior` for recipe machines. A `ProcessType` registry is deferred until a second kind of process is needed. A process that cannot pay a per-tick cost pauses (`WAITING_FOR_COST`); cancelling returns the in-flight inputs, keeping in the buffer whatever does not fit.
- Process start = one transaction that consumes inputs into the in-flight buffer. Process end = one transaction that inserts outputs; if outputs do not fit, the machine goes `BLOCKED` and the process holds its outputs until space is available. Nothing is voided implicitly.

---

# 14. Upgrades and Modifiers

```text
UpgradeType / UpgradeInstance   installed into a machine's upgrade slots
ModifierType                    Key-identified numeric target (e.g. machineengine:process_duration)
Modifier                        (ModifierType, operation, value, source)
```

- Core defines modifier types only for mechanisms core owns: `process_duration`, `process_cost`, `storage_capacity`, `transfer_rate`, `parallel_operations`. Addons add any other (e.g. range, efficiency).
- Stacking per D16; operations: `ADD`, `ADD_MULTIPLIED_BASE`, `MULTIPLY_TOTAL`. Integer/fixed-point math.
- Upgrade changes wake the machine (`WakeReason.UPGRADE_CHANGED`) and invalidate cached modifier totals.
- Menus add and remove upgrades (and addons' own things, such as gear modules) through the machine type's actions and lists (D40): `MachineAction`, `MachineList`, `MachineInstance#runAction`/`#list`, posted as `MachineEvents.ACTION`.

---

# 15. Networks and Transports (post-MVP, constraints fixed now)

- Networks, transports and resources are three separate concepts. A resource does not imply a network; a transport defines how something moves, not what it means.
- `NetworkType / Network / NetworkNode / NetworkConnection`; `TransportType / Transport / TransportConnection`.
- Network state is owned by a per-world `NetworkManager`, not by machines. Machines participate through ports.
- Graph updates are incremental (split/merge on add/remove), never full rebuilds per change.
- **Partially loaded networks:** only loaded segments participate. Unloaded segments are inert; transfers to them fail with `NetworkUnavailable` and are retried on chunk load (CE `subscribeChunkLoad`). No chunk loading.
- **Folia:** a network segment is confined to one region; links between segments in different regions use the handoff protocol (§16).
- Network metadata not tied to a single block (network IDs, segment index) is stored in chunk/world PDC (D2).

---

# 16. Threading Model and Cross-Region Handoff

### 16.1 Thread categories

Every public API method is documented with one of:

| Tag | Meaning |
|-----|---------|
| `@ThreadSafe` | Callable from any thread (registries after freeze, immutable definitions, metrics reads). |
| `@OwnerThread` | Must run on the thread owning the machine/chunk (Paper main thread, or the Folia region thread). |
| `@AnyThreadAsync` | Returns a future; work is scheduled onto the correct owner. |

Debug mode asserts owner-thread rules (`Bukkit.isOwnedByCurrentRegion` on Folia, `isPrimaryThread` on Paper).

### 16.2 Simulation vs application

```text
simulation (pure, owner thread or test harness)
    ↓ result + the world calls it made
application (owner thread): block state updates, particles, sounds, drops
```

CraftEngine also offers async block-entity tickers (`createAsyncBlockEntityTicker`). MachineEngine MUST NOT use them in the MVP; they are reserved for a possible future pure-simulation offload.

### 16.3 Handoff protocol (D1)

Used when source and target are owned by different threads (Folia). On Paper, every machine is on the main thread, so the synchronous fast path always applies; the handoff code path is still exercised in tests with the fake multi-region scheduler.

```text
Source (owner A)                                Target (owner B)
1. tx: extract ≤ N into Escrow(transferId)
   (Escrow is a participant persisted in the
   SOURCE machine's state) → commit
2. post Deliver(transferId, stacks) ─────────▶  3. if transferId in dedupe set → ack previous result
                                                 4. tx: insert what fits → commit;
                                                    record transferId in dedupe set (persisted
                                                    with TARGET, last 16 per source; an id
                                                    older than the source's latest is stale:
                                                    answered with nothing, never inserted)
                    ◀───────────────────────────  5. post Ack(transferId, accepted, remainder)
6. tx: clear accepted from escrow; return
   remainder to source storage (or keep in
   escrow and retry/return later) → commit
```

Rules:

- **At most one in-flight handoff per route** (backpressure). A route with non-empty escrow does not start a new transfer.
- **Transfer ids grow per position:** a machine's sequence starts at its game time × 65536 when it is created or loaded (never below its saved value), so a machine placed where another stood never reuses its predecessor's ids. With one handoff per route, the target needs only the latest ids from each source, so traffic from other neighbours can never push an unanswered id out of its dedupe set.
- **Backoff:** a handoff answered with nothing accepted rests its route (20 ticks if the target is missing, 5 if it is full), so a source never ping-pongs messages every tick. Not persisted.
- **Removed source:** if a machine is removed while a handoff is in flight, the remainder of the answer is dropped into the world (`MachineHost.dropResources`), even if another machine was placed there since. This record is in memory only, so a crash in that window loses the remainder.
- If the target is unloaded, removed, or rejects: ack with full remainder; source returns it.
- Escrow contents are visible in inspection and count toward the source's contents (nothing is "in the air" unaccounted).
- Messages are scheduled with Paper's `RegionScheduler` for the target location, which also works on non-Folia Paper and follows region migration.
- **Guarantees:** no loss and no duplication on every non-crash path (unload, region merge/split, removal, shutdown — shutdown drains or returns escrows before the final save). On a hard crash, duplicates are suppressed by the dedupe set; a loss window exists only if the target's chunk was not saved after the source's chunk recorded the ack. This is the same class of risk as vanilla's non-atomic multi-chunk saves and is documented, not hidden.

---

# 17. Scheduler, Sleeping and Waking

MachineEngine does not tick every machine every tick. It builds on CraftEngine's block-entity ticker infrastructure, which already keeps sleeping tickers out of the active list (`SleepingBlockEntityTicker`, `ActiveBlockEntityTickersList`).

### 17.1 States

| State | Meaning | CE ticker |
|-------|---------|-----------|
| `ACTIVE` | Has work this tick | awake |
| `SCHEDULED` | Will wake at a known game tick | sleeping + timed wake-up |
| `WAITING` | Needs input (wakes on insert / input change) | sleeping |
| `BLOCKED` | Output full or dependency missing (wakes on extract / neighbor change) | sleeping |
| `SLEEPING` | Nothing to do until an external signal | sleeping |
| `DISABLED` | Redstone-disabled, placeholder, or errored | sleeping |

### 17.2 Wake reasons

`RESOURCE_INSERTED`, `RESOURCE_EXTRACTED`, `INPUT_CHANGED`, `OUTPUT_AVAILABLE`, `NEIGHBOR_CHANGED`, `NETWORK_CHANGED`, `UPGRADE_CHANGED`, `RECIPES_RELOADED`, `CONFIG_CHANGED`, `TIMER`, `CHUNK_LOADED` (via CE `subscribeChunkLoad`), `EXTERNAL` (addon-defined `Key`).

- Wake signals are emitted from transaction commit callbacks, so aborted simulations never wake anything.
- `wake()` is `@OwnerThread`; cross-thread wakes arrive through handoff messages or the region scheduler.
- **Elapsed time:** a machine woken from `SCHEDULED` sees the ticks it slept as elapsed (planned work, e.g. a running process). One woken from `WAITING`/`BLOCKED`/`SLEEPING` sees 1 tick, because waiting is not working. Scheduling state and timers are persisted relative to the save.
- Timed wake-ups use `RegionScheduler.runDelayed`, coalesced per chunk per target tick to bound the task count. (Implementation detail to benchmark in M3; a per-world timer wheel is the fallback on non-Folia Paper.)

### 17.3 Budgets and fairness

- Configurable per-tick time budget per world/region; machines exceeding it are deferred round-robin to the next tick (no starvation).
- A slow-machine watchdog logs machine types/positions exceeding a configurable microsecond threshold.
- CraftEngine's latest dev also added stricter tick conditions (ticking only when the chunk is block-ticking); MachineEngine follows CE's chunk-ticking state and does not tick machines in non-ticking chunks.

---

# 18. Unloaded Chunks and Catch-Up (D4)

- Default `CatchUpPolicy.NONE`: a machine in an unloaded or non-ticking chunk is frozen; on load it resumes exactly where it stopped.
- Each machine persists `lastTickedGameTime` (world game time, never wall-clock).
- Opt-in `CatchUpPolicy.bounded(maxTicks)`: on load, `elapsed = min(gameTime - lastTickedGameTime, maxTicks)` and `MachineBehavior.catchUp(ctx, elapsed)` is called **once**. Requirements:
  - closed-form (no tick replay loop);
  - uses only the machine's own storages and state (no neighbors, no networks, no world access);
  - deterministic.
  A default implementation for process-based machines advances progress and completes at most as many cycles as inputs and output space allow.
- Networks never catch up. Transfers never target unloaded chunks. MachineEngine never loads a chunk.

---

# 19. Deterministic Simulation

Given machine state, inputs, configuration and game tick, the same operation produces the same result unless explicit randomness is involved.

- Randomness only through `MachineContext.random()`, a seeded per-machine source (seed derived from position + world seed + tick), so tests can reproduce runs.
- No iteration over unordered hash collections in simulation logic where order affects results; registries expose stable (registration) order.
- No floating point in resource math (D10).

---

# 20. Persistence (D2)

### 20.1 Where

- **Machine state** → CraftEngine block-entity data, written by the MachineEngine controller in `saveCustomData(CompoundTag)` and read in `loadCustomData(CompoundTag)`, under the root key `machineengine:machine`.
- **Chunk/world PDC** → only data not owned by one machine block (network metadata, world ID allocators).
- If MachineEngine itself is missing, CraftEngine keeps the block entity's raw tag through `InactiveBlockEntityController`, which re-emits it unchanged on save (verified in CE source). MachineEngine data therefore survives MachineEngine being temporarily removed.

### 20.2 Dirty tracking (critical CE finding)

CraftEngine only writes a chunk when `CEChunk.isUnsaved()` is true, and modifying block-entity data does **not** set that flag by itself. Therefore:

- Every committed change to machine state MUST mark the machine dirty, and the adapter MUST call `setUnsaved(true)` on the owning `CEChunk` (once per tick at most, coalesced).
- On `saveCustomData`, if the machine is clean, the adapter re-emits the cached encoded tag instead of re-encoding (dirty-state persistence).
- **[VERIFY]** the thread `saveCustomData` runs on (the CE world save path builds tags then encodes/writes on an async executor). If it can run off the owner thread, the controller MUST hand over an immutable pre-encoded snapshot produced on the owner thread.

### 20.3 Format and codecs

```text
machineengine:machine
 ├── format: int                       MachineEngine envelope version
 ├── type: "mymod:refinery"            machine type key
 ├── typeVersion: int                  MachineType data version (owned by the addon)
 ├── lastTick: long                    lastTickedGameTime
 ├── state: { … }                      MachineState encoded by codecs
 ├── escrow: [ … ]                     pending handoffs (§16.3)
 ├── dedupe: [ … ]                     recent incoming transfer IDs
 └── unknown: { … }                    opaque data preserved for missing content (§21)
```

- `StorageCodec<T>` encodes to a format-neutral immutable `DataValue` tree (`DataMap`, list, string, long, double, boolean, bytes). The CE adapter maps it to sparrow-NBT `CompoundTag`; the test harness uses an in-memory implementation. MachineEngine does not adopt DataFixerUpper.
- Every codec has a version. Addons register migrations `(fromVersion → toVersion)` that run on load. A missing migration is a validation error at startup, not a runtime crash.
- Persisted: machine state, storages, process state + in-flight buffer, upgrades, side configuration, custom addon state, escrow, dedupe set.

### 20.4 Machine as item

Implemented in M5 (D22). The break policy is set per block: `keep_contents: true` on the `machineengine:machine` behavior. The default drops the contents.
- **Core:** `CoreMachines.pack(machine)` removes a machine and returns its state plus what it held. It returns empty when there is nothing to keep (storages empty, no process, no upgrades), so empty machines stay plain stackable items. It also returns empty while a handoff is in flight, because the escrowed resources are not in the machine. The packed state leaves out the old position's handoff bookkeeping and the save time. `CoreMachines.restore(fresh, state, host)` replaces a just-placed machine with that state, at the new position and facing. Time spent as an item does not count, so there is no catch-up. A state for another machine type is ignored. `DataBinary` is a compressed, versioned byte form of `DataValue` trees.
- **Breaking (CraftEngine host):** the machine is packed when its block is removed. The player's `BlockDropItemEvent`, fired in the same tick at `LOWEST` so auto-pickup plugins see the full item, writes the state into the dropped block item. That item has the custom id of the block and an amount of 1. The state goes into the PDC (`machineengine:contents`) and a lore summary is added. A break without that event (explosion, creative mode, a drop-less loot table, `breakNaturally`) drops the contents one tick later, so nothing is lost.
- **Placing:** CraftEngine calls `BlockEntityController.loadCustomDataFromItem` after the block is placed and after every cancellable place event. That is where the state is restored, so a cancelled placement cannot duplicate contents. Non-item resources (energy, fluids) survive in the item, whereas a normal break voids them.

---

# 21. Missing Content and Placeholders (D3)

| Missing | Behavior |
|---------|----------|
| MachineEngine plugin | CE `InactiveBlockEntityController` preserves raw data (§20.1). |
| Machine type (addon removed or disabled) | Controller creates a `PlaceholderMachine`: keeps the full raw tag verbatim, never ticks, exposes no capabilities, shows in inspection and `/machineengine placeholders`. Breaking it by non-admins is cancelled by default (configurable) so data is not destroyed. Admins can purge. |
| Resource type inside a known machine | Stored as `UnknownResourceStack` (raw encoded form). Its slot is locked (unusable by other resources) until the type returns or an admin purges it. |
| Component type | Raw encoded component is kept opaque and re-emitted on save; the stack is treated as non-stackable with anything else. |
| Codec / migration | The machine loads as a placeholder with an `SerializationError` diagnostic; raw data preserved. |

When the missing content returns, placeholders rehydrate on next load with no data loss.

---

# 22. CraftEngine Integration (D7, D14)

### 22.1 Layering

```text
api  →  common: engine (package core)  →  common: CraftEngine integration (package craftengine)
                                               ├── MachineBlockBehavior   (CE BlockBehavior + EntityBlock + WorldlyContainerHolder)
                                               ├── MachineBlockEntityController (CE BlockEntityController)
                                               ├── MachineTicker          (wraps CE SleepingBlockEntityTicker)
                                               └── KeyAdapter, DataView↔CompoundTag, facing mapping
                                                        ↓
                                               CraftEngine block / block entity
                                                        ↓
                                               MachineInstance (core)
```

The adapter is the only place CraftEngine classes appear. CraftEngine's extension points used by MachineEngine live in `craft-engine-core` and are not a formally versioned API; churn is expected, hence the dual CI job (§2).

### 22.2 Block behavior registration

- In `MachineEngine.onLoad`: `BlockBehaviors.register(Key.of("machineengine", "machine"), MachineBlockBehavior.FACTORY)` (CraftEngine's `BlockBehaviors.register` writes into its built-in behavior-type registry; extension plugins such as *craft-engine-blocks* do this in `onLoad`).
- Server owners (or addons shipping CE resource packs) attach the behavior in CraftEngine block config, referencing a machine type by key. Verified schema (V1):

```yaml
items:
  example:generator:
    model: minecraft:block/iron_block
    behavior: { type: block_item, block: example:generator }
blocks:
  example:generator:
    behavior:
      type: machineengine:machine
      machine: example:generator       # MachineType key, resolved lazily (D14)
      facing_property: facing          # optional; defaults to "facing" if the block has it
    settings:
      template: default:settings/solid_1x1x1
      overrides: { push_reaction: immoveable }
    state: { auto_state: solid, texture: minecraft:block/iron_block }
```

- The factory stores the unresolved key and every controller resolves it when its block entity loads, so CraftEngine reloads need no re-binding (V6). After CraftEngine loads or reloads block configs (`CraftEngineReloadEvent`), MachineEngine logs a warning for every block whose machine key is not registered. Such blocks stay inert, and blocks that already hold machine data load as placeholders.
- CraftEngine parses its block configs in its delayed init, after MachineEngine has enabled; lazy resolution makes this order irrelevant.
- The published CraftEngine jars relocate sparrow NBT to `net.momirealms.craftengine.libraries.nbt` (inside `craft-engine-core`); the adapter compiles against that package.

### 22.3 Block entity lifecycle mapping

| CraftEngine hook | MachineEngine action |
|------------------|----------------------|
| `EntityBlock.createBlockEntityController` | create `MachineBlockEntityController` (holds the controller id from `initControllerId`, needed for composite controllers via `controller.let(...)`) |
| `loadCustomData` | decode `MachineState` (or build placeholder) |
| `onLoad` | create `MachineInstance`, register capabilities, subscribe to neighbor chunk loads (`subscribeChunkLoad`), apply catch-up policy |
| `createBlockEntityTicker` | return the machine's `SleepingBlockEntityTicker` (one per controller) |
| `preBlockStateChange` | update facing → side mapping, wake `CONFIG_CHANGED` |
| `saveCustomData` | write cached or freshly encoded state (§20.2) |
| `onUnload` | invalidate capabilities, cancel handoffs-in-start (escrows remain persisted), stop timers |
| `onRemove` | break policy (drops / item), return escrow and in-flight buffers to drops, fire `MachineRemoveEvent` |
| `BlockBehavior.neighborChanged` | wake `NEIGHBOR_CHANGED`, invalidate neighbor capability cache |
| `BlockBehavior.useWithoutItem` / `useOnBlock` | `MachineInteraction` SPI (open GUI, configure sides) after protection check |
| `BlockBehavior.getSignal` / `getAnalogOutputSignal` | `redstone_control` / `comparator_output` capabilities |
| `WorldlyContainerHolder.getContainer` | expose item ports to hoppers/droppers as a vanilla `WorldlyContainer` view (§23.2) |

### 22.4 Facing and sides

The behavior config names the block-state property that stores facing. The adapter converts the CE property value to an absolute direction and resolves relative port sides. Machines without a facing property use absolute sides.

### 22.5 Events used from CraftEngine

`CustomBlockPlaceEvent`, `CustomBlockBreakEvent`, `CustomBlockInteractEvent` (protection and item-data restore), `CraftEngineReloadEvent` (re-binding, D14).

---

# 23. Paper Integration

### 23.1 Responsibilities of the Paper integration (`common`, package `paper`)

Bootstrap and service locator, lifecycle (§8), Brigadier commands (Paper lifecycle command registration), region scheduler bridge, PDC for non-block data, protection service, vanilla interop, item resource category, metrics export, configuration file.

### 23.2 Vanilla interop

- **Hoppers/droppers:** item ports are exposed through CE's `WorldlyContainerHolder` as a `WorldlyContainer` whose operations run as MachineEngine transactions, respecting port sides and filters.
- **Redstone:** optional `redstone_control` capability (ignore / active-high / active-low / pulse); comparator output through `comparator_output`.
- **Pistons:** machine blocks MUST be immovable: CE block settings have a `push-reaction`; the machine behavior requires `BLOCK`, and validation warns otherwise. **[VERIFY]** config key name.
- **Explosions:** treated as removal; break policy applies; addons can veto via `MachineRemoveEvent` (PRE).
- **WorldEdit / chunk regen:** CraftEngine handles WorldEdit compatibility for its blocks. Machines created without data start empty; machines removed this way follow `onRemove` without drops (no duplication).

### 23.3 Players, GUIs, protection, limits

- **GUI:** MVP ships no GUI toolkit. It provides the `MachineInteraction` SPI so addons can open their own inventory menus, plus a generic read-only inspection menu. A first-party GUI module is M5. Visual feedback MAY use CE block-entity render elements (display entities) later.
- **Protection:** opening/configuring a machine checks a `ProtectionService`. The default implementation delegates to CraftEngine's anti-grief provider (an internal CE API, accessed only in the adapter); placement and breaking are already protection-checked by CE.
- **Ownership:** `MachineState` records the placing player's UUID (optional). Permissions: `machineengine.admin.*`, `machineengine.inspect`, `machineengine.use`.
- **Limits:** configurable caps on machines per chunk and per player, and per-tick transfer budgets, to prevent lag machines.
- **Players (D37):** `MachinePlayer` (`api.player`) holds what MachineEngine and addons keep per player: `PlayerDataKey`s (persistent or session) and `PlayerSetting`s players change with `/machinesettings` (permission `machineengine.settings`) or the zMenu menu `player_settings`. Admins read and change any value with `/machineengine player <player> [get|set|reset <key> [value]]` (offline players only with a storage that can read them). Data is loaded at join (`LOWEST`) and saved at quit (`MONITOR`), every `players.save-interval` seconds when changed, and on disable.

### 23.5 Configuration file (D30)

- `plugins/MachineEngine/config.yml` is generated from `EngineConfig.SPEC` (`api.config`) on first start; missing keys are added with their defaults and comments at every start and on `/machineengine reload config`. `api/src/test/resources/config/engine-config.yml` is the file a new server gets; `EngineConfigTest` fails when it changes, so a change shows in review.
- Keys today: `limits.max-machines-per-chunk`, `menus.default`, `menus.by-type`, `events.bukkit-bridge` (restart-only), `placeholders.allow-breaking`, `players.storage` (restart-only), `players.save-interval`, `players.load-timeout`, `players.setting-defaults`, `players.locked-settings` (D37), `messages.default-language`, `messages.prefix` (D44), `logging.debug` (D45), `resources.energy.unit|scale`, `resources.fluid.unit|scale|bucket` (restart-only, §9.5).
- Code reads values with `config.get(KEY)` when it needs them, never caching them, so a reload applies at once. A new setting is a `ConfigKey` added to the spec, never a raw `getConfig()` call or a constant.

### 23.6 Messages (D44, D45)

- Chat, action bar and admin text: `EngineMessage` / `EngineMessages` (`common`, `paper.message`); files in `plugins/MachineEngine/lang/`, one per language; see D44 for the language chain. Code that can't reach `EngineMessages` sends `Component.translatable("machineengine.<key>", englishFallback, args…)`; argument `i` fills `%i%`.
- Item lore and the settings menu: `Messages` (`common`, `paper`), CraftEngine `lang` entries in the `machineengine` pack, translated on the client.
- A value inserted in a message is text (or a component), never parsed as MiniMessage. Data lines (inspection, diagnostics, `check` results) pass through as they are.
- Logs: MessageFlow `Logger` with a context tag and placeholders (D45).

### 23.4 Item resource category

`machineengine:item` is registered by the Paper module through the public SPI (not core). `ResourceType` per item id (vanilla or CE item); remaining item data stored as an opaque item-data component; stackability = `ItemStack.isSimilar` semantics. Amount = item count.

---

# 24. Validation

```java
record Diagnostic(Severity severity, Key code, String message, AddonId source,
                  @Nullable Key subject, @Nullable String location, Map<String, Object> context) {}
final class ValidationReport { List<Diagnostic> errors(); List<Diagnostic> warnings(); … }
```

Validated at startup and on every reload: IDs and namespaces, duplicates, missing dependencies and API ranges, ports (unknown capability, impossible direction/sides), storages (zero/negative capacity), recipes (unknown types, unsatisfiable matchers), processes, codecs and migration chains, network definitions, capability compatibility, CE block bindings (unknown machine type, missing facing property, wrong push reaction), modifier targets.

Fail early, report everything in one pass (not first error only), and disable only the offending addon's content.

---

# 25. Error Model

Operations that can fail in expected ways return results, not exceptions:

```java
sealed interface Result<T> permits Ok, Err {}
sealed interface MachineError {
    Key code(); String message(); Map<String, Object> context();
}
```

Error families (each a record implementing `MachineError`): `InvalidDefinition`, `MissingResource`, `InsufficientResource`, `TransferRejected`, `MachineBlocked`, `NetworkUnavailable`, `SerializationError`, `DependencyMissing`, `InvalidConfiguration`, `CapabilityUnavailable`, `WrongThread`, `RegistryFrozen`. Addons may define more (the code is a `Key`).

Exceptions are reserved for programmer errors (contract violations). Each machine keeps its last error (`machine.lastError()`) for inspection.

---

# 26. Events (D12)

- `EventType<E>` is registered (addons can add types). `EventBus` with priorities and two phases: `PRE` (may cancel or modify where the event allows) and `POST` (observe only).
- Listeners are owned by an addon and are unregistered automatically when it disables.
- Events fire on the owner thread of the operation; listeners MUST NOT block.
- Simulated transactions fire no events. Commit callbacks fire POST events.
- No per-transfer events in hot paths: `ResourceTransferEvent` is aggregated per machine per tick, and only emitted if a listener is subscribed for that machine type.
- Initial set: `MachineCreate/Load/Unload/Remove`, `ResourceInsert/Extract/Transfer` (aggregated), `MachineProcessStart/Complete`, `MachineUpgrade`, `RegistryFrozen`, `RecipesReloaded`, later `NetworkCreate/Change`.
- An optional bridge MAY mirror selected events as Bukkit events in the Paper module. Implemented in M5 (`fr.robie.machineengine.paper.event`); the event classes are in `api` since D43 (`fr.robie.machineengine.api.paper.event`), the bridge that fires them stays in `common`.

---

# 27. Observability, Debugging and Inspection

- Metrics: per-region counters aggregated on read (`scheduler.active_machines`, `scheduler.sleeping_machines`, `machine.processes`, `machine.resource_transfers`, `handoff.in_flight`, `tick.time_us` …). Hot counters behind a config flag.
- Inspection API: `machine.state()`, `ports()`, `storages()`, `process()`, `schedulerState()`, `lastWakeReason()`, `lastError()`, `escrow()`.
- Commands (Brigadier):

```text
/machineengine inspect                 machine you are looking at
/machineengine machine <x y z>         same by position
/machineengine registry <name> [filter]
/machineengine placeholders list|purge
/machineengine profile start|stop      slow machines, budgets, handoffs
/machineengine debug                   in-world view: state, port and network particles, action bar (implemented)
/machineengine debug <flag> on|off     thread assertions, capability-cache checks
/machineengine reload recipes
```

Debug output must be useful without attaching a debugger.

---

# 28. Testability (`test`)

Core logic MUST be testable without a Minecraft server. The harness (module `test`, which depends on `plugin` and `common`, D24) provides:

- `TestRegistrationEnvironment` — simulates multiple addons registering, dependency ordering, freeze, validation.
- `FakeWorld` with chunks, load/unload, and a deterministic game clock.
- `FakeRegionScheduler` — multiple simulated regions with deterministic interleaving, used to test handoff, including injected crashes at every protocol step.
- In-memory `DataView` for persistence round-trips and migrations.
- Assertions for resource conservation (§10.3 invariant).

Real-server tests: `run-paper` with the pinned CraftEngine snapshot jar for smoke tests (M3), run on Paper 1.20.6 and 26.3 and Folia 1.20.6 and 26.2 (`:plugin:runPaper-*`, `:plugin:runFolia-*`), with the `/machineengine check` commands (on dev-only blocks, D42). MockBukkit is not used (it cannot host CraftEngine).

---

# 29. Data-Driven Definitions (YAML)

Implemented in M5 (D21):

```text
YAML → Parser → Reference resolution → Templates/Inheritance → Validation → Definition compiler
     → MachineType / Recipe / ProcessType … registered through the same public API
```

- YAML is a declarative layer over the public API, not a separate runtime.
- YAML references registered IDs only: no class loading, reflection, method invocation, scripts or filesystem access.
- Behavior comes from registered `MachineBehavior` factories referenced by key; configuration parameters are passed to them.
- Static definitions (resource categories and types, recipe types, machines) are registered at startup by the `definitions` pseudo-addon, after every addon, and freeze with the rest (D5). Recipes are reloadable and re-read from disk on `/machineengine reload recipes`.
- Behaviors are `BehaviorType`s in the `machineengine:behavior_type` registry. Each parses its own YAML section into a `MachineBehavior.Factory`. Built-in: `machineengine:processing` (keys `recipes`, `inputs`, `output`, `costs`, `push`, `retry-ticks`) and `machineengine:storage`.
- Filters: `type`, `types`, `category`, `tag`, `item`, `any`, `all`, `any-of`, `not`. `item:` ids are resolved by the platform (vanilla materials on Paper).
- An invalid entry is skipped with a `machineengine:definition_skipped` warning located at `<file> > <section>.<key>`; the rest still loads. A file with `enabled: false` is ignored.
- Files: `plugins/MachineEngine/definitions/*.yml`. A commented `example.yml` (disabled) is installed the first time.
- CraftEngine block YAML already references machine types by key (§22.2) and is the first, minimal data-driven surface.
- A resource type's `fluid:` section makes it a `FluidType` (D47, `FluidSpecParser`): world mode, bucket, look, physics, mixing, effects. Texture sources, mixing results and effects are types in registries (`machineengine:fluid_texture_type`, `fluid_reaction_type`, `fluid_effect_type`), referenced by key. Unknown fields are refused with their full path.
- `plugins/MachineEngine/fluids.yml` overrides placeable fluids (any addon's) on top of their definition, except `world` and `bucket`. `/machineengine reload fluids` applies physics, mixing and effects at once, looks after the pack rebuild, fog after a rejoin.

---

# 30. Ownership Rules

| Thing | Owner | Others may… |
|-------|-------|-------------|
| Machine state & storages | the machine instance (owner thread) | access only via capabilities/transactions on the owner thread |
| Escrow of a handoff | source machine until ack | read via inspection |
| Network state | `NetworkManager` (per world, per region on Folia) | attach/detach through ports |
| Scheduled tasks / timers | the scheduler | request via `MachineContext.sleep()`, `waitForInput()`, `block(...)`, `wakeIn(...)` |
| Persistence | the controller/adapter | provide codecs; never write CE tags directly |
| Registrations | the registering addon | read after freeze |
| World mutation | the host (owner thread) | machines call `MachineContext.world()` on the owner thread |

A machine owns its internal state but not external network state. Addons cannot mutate infrastructure they do not own except through the API.

---

# 31. Plugin Isolation and Cross-Plugin Interaction

An addon can register resource categories, resources, components, machines, ports, capabilities, recipes, processes, networks, transports, upgrades, modifiers, codecs, converters, event types, and its own registries, without modifying MachineEngine.

```text
Plugin A: energyplugin:energy, energyplugin:fe, energyplugin:generator
Plugin B: mymod:fluid, mymod:oil, mymod:refinery (consumes energyplugin:fe)
Plugin C: converter energyplugin:fe → magicplugin:mana
```

Cross-plugin interaction works because compatibility is decided at runtime from registered identity: a port of Plugin B's machine whose matcher accepts `energyplugin:fe` interacts with Plugin A's generator; no shared code beyond the MachineEngine API is required. When B requires A's types at registration time, B declares `requires("energyplugin", …)` so its callback runs after A's (§8.3).

First-party content plugins have no privileged access; they use the same public API.

---

# 32. Performance

Avoid: ticking every machine every tick, per-tick allocation in hot paths, unnecessary serialization (dirty caching §20.2), network rebuilds, unnecessary client sync, registry lookups by string in hot loops, reflection, global locks.

Prefer: sleeping tickers, coalesced wake-ups, canonical immutable definitions compared by reference, index-based capability lookup, primitive storage layouts, batched transfers, per-region data structures, profiling hooks.

Performance acceptance targets are set in M3 with a benchmark world (e.g. 10,000 idle machines must cost ~0 tick time; 1,000 active simple machines within a stated budget).

---

# 33. Non-Goals

MachineEngine MUST NOT (narrowed by D29, which ships an optional shared energy and fluid category): assume FE is default, assume fluids are water-based, assume one universal resource system, convert resources automatically, hardcode resource categories or machine types in core, require a particular energy or fluid system, require CraftEngine in the `api`/`core` modules, use CraftEngine outside the adapter module, modify third-party plugins, require a client mod, require every machine to use recipes/networks, tick every machine every tick, load chunks, or expose internal classes as public API.

---

# 34. Naming and Namespaces

- Namespaced IDs everywhere. MachineEngine's namespace: `machineengine`. Examples in this document use `energyplugin:`, `mymod:`, `magicplugin:` for addons.
- Avoid Forge-specific branding or terminology that could be confused with Minecraft Forge/NeoForge.
- Java package root: `fr.robie.machineengine` (D18), with one sub-package per module: `fr.robie.machineengine.api`, `.core`, `.test`, `.paper`, `.craftengine`. Maven group: `fr.robie`.

---

# 35. Milestones and Acceptance Criteria

Build stable foundations first; do not implement every advanced system at once.

The status notes below are history. The `machineengine-example` addon (`/mexample`) that several of them were verified with was removed on 2026-10-06 (D24). Its engine checks became `/mekanized check-drop`, `check-hopper`, `check-comparator` and `fill`, run on a Mekanized basic bin instead of the example crate, and since D42 `/machineengine check drop|hopper|comparator|fill` on dev-only blocks. Likewise, `verifyPureJava` was replaced by `verifyPaperBaseline` (D25, D26).

### M0 — Scaffold
Gradle multi-module layout (§5), version catalog, `craftengine_version=26.10-SNAPSHOT`, CI (build, tests, dual-CE job, API compatibility placeholder), `paper-plugin.yml` with required CraftEngine dependency, `run-paper` tasks for Paper 1.20.6 and 26.3 that install the CE snapshot.
**Accept:** plugin loads on Paper with CE present; refuses to load with a clear message when CE is absent; `api`/`core` have no Paper/CE on the classpath.
**Status: done (2026-10-01).** Verified on Paper 1.20.6 (build 151) and 26.3 (build 141) with CraftEngine `26.10-SNAPSHOT` built from `dev`; without CraftEngine, Paper rejects MachineEngine with `UnknownDependencyException`; `verifyPureJava` fails the build if Paper is added to `core`.

### M1 — Foundations (headless, `api` + `core` + `test`)
Keys/`RegistryKey`, static + reloadable registries, lifecycle phases, addon registration & dependency ordering, validation/diagnostics, error model, resource category/type/stack/components/merge policies, predicates/matchers/tags, storages, transactions, converter registry, codecs + `DataView` + migrations, event bus.
**Accept:** unit tests for all; property tests proving the resource-conservation invariant under random operation/abort sequences; duplicate/missing/cycle diagnostics tested.
**Status: done (2026-10-01).** 63 tests pass (API module and `machineengine-test`). The conservation property test runs 8 seeds × 200 random rounds with nested commits/aborts across 6 storages, and fails as expected when rollback is deliberately broken. The format-neutral data tree is `DataValue`/`DataMap` (named `DataView` in earlier drafts).

### M2 — Machines (headless)
Capabilities, ports & sides, `MachineType/Instance/State/Context/Behavior`, scheduler states & wake reasons, recipes (reloadable) & processes with in-flight buffer, upgrades/modifiers (D16), placeholders (D3), persistence round-trip, handoff protocol over `FakeRegionScheduler`, inspection API.
**Accept:** the §36 integration test passes fully headless, including persistence, scheduler, a cross-region variant, injected crash points, and the missing-addon variant.
**Status: done (2026-10-01).** 90 tests pass. `MachineIntegrationTest` covers the two-addon scenario (same region, cross-region over the handoff protocol, save/reload with identical continued simulation, missing addon → placeholder → rehydration, runtime identity at ports). `HandoffCrashTest` restores mixed saves at each protocol step; resources are conserved in every case except the documented window, which loses exactly the one in-flight transfer. Deliberately disabling dedupe or the in-flight buffer makes these tests fail. The host SPI is `core.machine.MachineHost`; the test harness provides `FakeWorld` (regions, clock, timers, message queues, crash simulation).
Deferred from M2: per-instance port side configuration, `WorldEffect`s (no world mutation needed before M3), automatic wake of loaded machines on recipe reload (the host does it in M3), and the placeholder break policy (the host decides in M3).

### M3 — Paper + CraftEngine
`machineengine:machine` behavior, controller, sleeping ticker, lazy binding & CE reload re-binding, persistence in CE block-entity data with unsaved-flag handling, chunk-load subscriptions, break/item policy, hopper interop, item category, protection, commands, metrics, config, limits/budgets.
**Accept:** §36 test runs as two real addon jars on a `run-paper` server; state survives restart; removing an addon yields placeholders that rehydrate when it returns; removing MachineEngine and re-adding preserves data; 10k idle machines show negligible tick cost.
**Status: core done (2026-10-01), rest split into M3b.** Verified on Paper 1.20.6 and 26.3 with CraftEngine `26.10-SNAPSHOT`, using the `machineengine-example` addon (a generator and a consumer, placed by `/mexample place`): machines are created in CraftEngine block entities, sleep and wake through CraftEngine's sleeping ticker and Paper's region scheduler, transfer through ports, survive restarts (state stored in the block-entity data, chunks flagged through `CEWorld.blockEntityChanged`), turn into placeholders when the addon is removed, and rehydrate unchanged when it returns. The server run found one bug: region-scheduler timers fire one tick before the world clock reaches the wake time. Early timers now reschedule themselves (regression test added). Commands: `/machineengine status|report|inspect|machine <x y z> [world]|registry <name>|reload recipes|tags`.
**M3b: done (2026-10-02).** Verified on Paper 1.20.6:
- **Items:** one built-in `machineengine:item` resource type; the concrete item is its `machineengine:item_data` component (item id + serialized bytes), so CraftEngine items that load after the freeze work too. `PaperItems` converts to and from `ItemStack`s. A broken crate holding 100 iron ingots dropped exactly 100. Non-item resources are still voided on break (logged at FINE).
- **Idle cost:** 10,000 idle machines in 49 forceloaded chunks: average tick 0.1 ms over 5 s (0.5 ms before placing them, while chunks were still loading). All 10,000 sleep in CraftEngine's ticker.
- **MachineEngine removed, then re-added:** CraftEngine warns about the unknown behavior, keeps the raw data, and the machines resume unchanged.
- Placeholder break protection (`placeholders.allow-breaking`, permission `machineengine.admin.breakplaceholders`), `limits.max-machines-per-chunk`, `neighborChanged` wake-ups, and `MachineEngine.machineAt(position)` for addons.

Deferred to M4/M5: hopper interop (done in M5, through CraftEngine's container bridge), keep-contents-in-item (done in M5), protection checks for machine interactions (there are no interactions before the GUI/interaction SPI), and metrics export beyond `/machineengine status`. Decision recorded: items are registered by MachineEngine itself in core (not by the Paper module) so headless tests and addon recipes can use them; only the `ItemStack` conversion is Paper-specific.

### M4 — Networks, transports, Folia
Network/transport API and implementation (incremental graphs, segment-per-region), bounded catch-up policy, Folia validation (`folia-supported: true`).
**Status: done (2026-10-02).**
- **Networks:** `NetworkType` (a distribution strategy, the `NetworkBehavior` SPI; built-in `NetworkBehavior.distributing()`) and `TransportType` (a cable kind: network type, rate per tick, filter). Adjacent transports of the same network type form a network. Its rate is the slowest transport's, its filter is the intersection of their filters, and its endpoints are the machine ports facing it, sorted for deterministic distribution. Graphs are rebuilt incrementally from loaded transports: add joins or merges, remove splits with a search limited to the old network. Nothing is persisted. Networks sleep when a tick moves nothing and wake on endpoint storage changes and topology changes. A network stays within one owner thread (Folia region). Cross-region flow goes machine-to-machine through handoff; network-to-network links across regions are not supported.
- **CraftEngine:** `machineengine:transport` block behavior (`transport: <key>`), plus `/machineengine network <x y z> <world>`.
- **Catch-up:** `CatchUpPolicy.bounded(maxTicks)` on `MachineType` and `MachineBehavior.catchUp(context, ticks)`; `ProcessingBehavior` runs its processes for the caught-up ticks without pushing. Ticks a scheduled machine slept before unloading count too (uncapped, since they were planned work). Tests check exact equivalence with an uninterrupted run under the cap, and the cap above it.
- **Folia:** verified on Folia 1.20.6 and 26.2 (there is no Folia 26.3 build): direct transfers, networks, item drops and restarts, with no errors. Console commands that touch the world now run on the target region (`RegionScheduler`). Dev servers: `runFolia-1.20.6`, `runFolia-26.2`.

### M5 — Ecosystem
GUI module, data-driven YAML definitions (§29), official content plugins, Bukkit event bridge.

**Comparators, purge, events: done (2026-10-02).**
- **Comparators:** machine blocks have a comparator signal (`comparator: false` turns it off). It uses vanilla's container formula over every slot: 0 when empty, otherwise 1 to 15 from the average fill, in `MachineInstanceImpl.comparatorSignal()`. Neighbors are updated after committed changes, and only when the value changes. `/mekanized check-comparator` checks a real comparator; `ComparatorTest` checks the formula.
- **`/machineengine placeholders list|purge`:**
  - `list` shows loaded machines that keep data of missing content: placeholder machines, and machines with locked slots or unknown data.
  - `purge <x y z>` must be run twice within 10 seconds. It deletes a placeholder machine with its block. On a live machine it deletes the unknown data and unlocks the slots (`purgeUnknown()`). The data is gone for good, even if the addon comes back.
  - Unloaded chunks are not scanned.
- **New events:**
  - `MachineEvents.UPGRADE_CHANGE`;
  - `MachineEvents.RESOURCES_CHANGED`: inserted and extracted amounts summed per resource, at most one per machine per tick, after the changes, and only built while someone listens;
  - `EngineEvents.REGISTRIES_FROZEN` and `REGISTRY_RELOADED` (with accepted and report). `RegistrationContext.events()` lets addons subscribe during registration, so they receive the freeze.
  - The Bukkit bridge mirrors the upgrade and reload events, not `RESOURCES_CHANGED`: a mirror would keep the summing on for every machine.
  - Network events are still to do.

**Hopper interop: done (2026-10-02).** `MachineBlockBehavior` implements CraftEngine's `WorldlyContainerHolder`. CraftEngine's generated block class already implements vanilla's `WorldlyContainerHolder` and delegates `getContainer` to it, so no NMS code is needed. `MachineHopperContainer` exposes the machine slots reachable through ports as a vanilla `WorldlyContainer`:
- **Faces:** the hopper's face selects the ports on that side, and each port's direction and filter apply.
- **No in-place stacks:** vanilla and Paper hoppers merge into and split stacks in place. As in CraftEngine's drawer, each machine slot is therefore two container slots with a maximum stack size of 1: an always-empty "place" slot and a "take" slot that shows one item. Every move is an explicit `setItem` or `removeItem` that becomes a machine transaction.
- **Safety:** an insert is accepted only if all of it fits. Anything the machine cannot take after all is dropped, never lost. A machine that the current thread does not own (Folia) exposes no slots.
- **Opt-out:** `hoppers: false` on the block behavior turns it off.
- **Verified:** `/mexample hopper-test` (hopper with 10 iron → crate → hopper → chest) ends with all 10 in the chest on Paper 1.20.6 and Folia 26.2. Comparator output was added afterwards (below).

**Translations: done (2026-10-02), D23.** The `machineengine` CraftEngine pack (`plugins/CraftEngine/resources/machineengine/configuration/lang.yml`, English and French, never overwritten) holds the keep-contents lore, the missing-addon and chunk-limit messages, and the debug toggle messages. `Messages` builds translatable components with English fallbacks.

**Keep-contents in item: done (2026-10-02), §20.4, D22.** `KeepContentsTest` covers a packed machine resuming elsewhere through the item bytes with no catch-up, empty machines staying plain items, a state for another type being ignored, and corrupt data being rejected. The example crate uses `keep_contents: true`. Verified on Paper 1.20.6: a crate broken without a player (`/mexample crate-test`) still drops all 100 ingots through the fallback. The player path (break, item, place) needs a client and was not automated.

**Bukkit event bridge: done (2026-10-02).** Package `fr.robie.machineengine.paper.event`:
- **Mirrored events:** `MachineCreateEvent`, `MachineLoadEvent`, `MachineUnloadEvent`, `MachineRemoveEvent`, `MachineProcessStartEvent` and `MachineProcessCompleteEvent` mirror the engine's POST events. They expose the machine, its type key, world and location; the process events also expose the process and recipe. A Bukkit event is only built when a Bukkit listener is registered for it. `events.bukkit-bridge` in `config.yml` turns the bridge off.
- **Cancellable interact event:** `PlayerMachineInteractEvent` fires before a machine menu opens, after the permission and protection checks. It is a Bukkit-only event; cancelling it keeps the menu closed.
- **Threading:** events fire on the machine's owner thread, so on Folia that is its region thread. They are synchronous there because Folia treats region threads as primary.
- **Verified** on Paper 1.20.6 and Folia 1.20.6: the example's plain Bukkit listener receives create and remove events.
- **Known issue (dev servers only):** with zMenu installed, Folia on Windows crashes in jansi's native console code after the final save, when stdin is a pipe (automated runs). It is not MachineEngine code and data is already saved.

**YAML definitions: done (2026-10-02), D21.** API `api.definition` (`BehaviorType`, `DefinitionContext`, `BuiltInBehaviors`, `DefinitionFile`), compiler `core.definition.DefinitionCompiler`, Paper loader `YamlDefinitions`. `DefinitionTest` covers a YAML drill feeding a YAML smelter, skipped entries with exact locations, live recipe reload, addon behavior types and disabled files. Verified on Paper 1.20.6: the example machine and recipe register, a broken file is reported with its location, recipes reload.

**GUI: done (2026-10-02), on zMenu 1.1.1.9 (D20).** Module `machineengine-zmenu`, shaded into the plugin; zMenu is an optional dependency (without it, machines have no menus).
- **Opening:** right-clicking a machine block (not while sneaking) opens its menu, after the `machineengine.use` permission (default: everyone) and CraftEngine's anti-grief check (`OPEN_CONTAINER`, reached reflectively because AntiGriefLib is relocated in CraftEngine's plugin jar; V5 resolved). A placeholder machine tells the player its addon is missing.
- **Fully configurable menus:** standard zMenu inventory files in `plugins/MachineEngine/menus/`. A machine type opens `menus.by-type` from `config.yml`, else the file `<namespace>_<value>.yml` (how addons ship menus, e.g. Mekanized's `mekanized_<machine>.yml` files), else `menus.default` (`machine.yml`, shipped).
- **Placeholders** `%zmenu_machineengine_<key>%` work in any zMenu button and in PlaceholderAPI. They cover type, state, facing, position, storages (per storage and per slot: amount, capacity, free, percent, bar, resource), process (status, progress, percent, bar, remaining seconds), upgrades and escrow, and resolve against the machine the player has open. The logic is `MachinePlaceholders` (Paper module, unit-tested); `/machineengine placeholders <x y z> <world>` prints them for a machine.
- **Button types:** `machineengine_slot` shows a storage slot as the real item and lets players put the cursor item in or take the stack out (`storage`, `storage-slot`, `allow-insert`, `allow-take`); `machineengine_cancel_process` cancels the process and returns its inputs (`always-show`). Both act through transactions, so nothing is lost when the player's inventory is full.
- **Textured menus (D39, 2026-10-09):** placeholders in a button's `material` choose CraftEngine items per machine (fill bar cells, icons); `ZMenuMachineMenus#isLive` buttons are redrawn by MachineEngine, together, every `update-interval` of the open menu and on `machineChanged`. Placeholders added for them: `storage_<id>[_slot_<n>]_cell_<k>` and `_color`, `process_cell_<k>` (`BarCells`), `data_<id>_cell`, `side_<group>_<side>_mode`. `menus/machine.yml`'s header shows a bar button.
- **Load order:** MachineEngine enables at `STARTUP`, zMenu after the worlds, so the menus are set up on zMenu's `PluginEnableEvent`.
- **Verified** on Paper 1.20.6: zMenu loads the three menus with the custom button types, and placeholders resolve against a real machine. Clicking menus needs a player client and was not automated. The Folia run of the GUI was interrupted and is still to do.

---

# 36. Initial Integration Test (proves third-party extensibility)

```text
Addon A registers: resource category A-cat, resource types A and B, machine A (outputs B)
Addon B registers: machine B (consumes B), requires Addon A
```

The test MUST prove:

- no MachineEngine source modification;
- resource and machine registration from two independent addons, with dependency ordering;
- ports, storages and transfers work across addons, using transactions;
- runtime identity validation (a port rejects a look-alike type from another namespace);
- scheduler: B sleeps when empty, wakes on insert; A goes `BLOCKED` when B is full;
- persistence: save → reload → identical state (including in-flight process buffer);
- cross-region variant: A and B in different fake regions transfer via handoff without loss, including injected crashes;
- missing-addon variant: unload Addon B's registrations → machine B becomes a placeholder with data intact → restore → rehydrates;
- resource conservation invariant holds throughout.

---

# 37. Development Philosophy

Prefer: interfaces, immutable definitions, composition, registries, constructor injection, explicit ownership, explicit transactions, capabilities, events, validation, testability.

Avoid: global hardcoded systems, static mutable state (except the single documented entry point), resource-specific assumptions, hidden conversions, CraftEngine coupling outside the adapter, reflection-heavy hot paths, uncontrolled plugin access.

When uncertain between a hardcoded feature and an extension point, prefer the extension point if it does not make the core unnecessarily complex.

---

# 38. Final Design Requirement

```text
MachineEngine
 ├── Addon A defines an entirely new resource category
 ├── Addon B defines machines using that resource
 ├── Addon C defines a network for that resource
 ├── Addon D defines a transport for that resource
 ├── Addon E defines a converter/process involving that resource
 └── Addon F defines upgrades for those machines
```

…without changing MachineEngine's source code. MachineEngine remains a generic machine and resource infrastructure layer, not a collection of predefined industrial mechanics. Its quality is judged by how easily an independent developer can extend it without modifying the core.

---

# 39. Open Items to Verify Against CraftEngine Source

| # | Item | Section |
|---|------|---------|
| V1 | ~~Exact CE block YAML schema~~ **Resolved:** `blocks.<id>.behavior.type: machineengine:machine` (§22.2) | §22.2 |
| V2 | ~~Thread of `saveCustomData`~~ **Resolved:** CE serializes on the calling (owner) thread before encoding async; chunks are written before their block entities unload. `loadCustomData` runs while a chunk is read, so the controller only stores the raw tag and decodes in `onLoad`. | §20.2 |
| V3 | ~~`loadCustomDataFromItem` for blocks~~ **Resolved (M5):** current CraftEngine dev calls it from `BlockItemBehavior` after a placement is accepted (older builds did not); keep-contents restores there and stores its state in the item's PDC. | §20.4 |
| V4 | ~~Push reaction key~~ **Resolved:** `settings.push_reaction: immoveable` (`BLOCK` is deprecated). | §23.2 |
| V5 | Anti-grief access: `BukkitCraftEngine.instance().antiGriefProvider().test(player, Flag, location)`; `Flag` comes from AntiGriefLib, not on our compile classpath yet (M3b). | §23.3 |
| V6 | ~~Re-binding on `/ce reload`~~ **Resolved:** not needed; keys resolve lazily per block entity. | §22.2 |

### CraftEngine facts confirmed (dev branch, commit `d50b4c9`, 2026-09-30)

- Version `26.10-SNAPSHOT`, published to `repo.momirealms.net/snapshots`; Paper plugin, `load: STARTUP`, Folia-supported, bytecode `--release 21`.
- Block behaviors: `BlockBehaviors.register(Key, BlockBehaviorFactory)` into `BuiltInRegistries.BLOCK_BEHAVIOR_TYPE`; factories receive the `BlockDefinition` and `ConfigSection`.
- Block entities: behaviors implementing `EntityBlock` create a `BlockEntityController` with hooks `saveCustomData`, `loadCustomData`, `onLoad`, `onUnload`, `onRemove`, `preBlockStateChange`, `createBlockEntityTicker`, `createAsyncBlockEntityTicker`, `subscribeChunkLoad`; multiple controllers per block are composed (`let(Class, index, …)`).
- Ticking: `SleepingBlockEntityTicker` (owner-thread sleep/wake), active/sleeping partitioned ticker list; ticking skipped when the chunk is not block-ticking.
- Storage: CE region files (MCA or linear) with a per-chunk unsaved flag; block-entity data changes do not set it automatically.
- Missing behavior: `InactiveBlockEntityController` preserves and re-emits raw data.
- Hopper interop hook: `WorldlyContainerHolder.getContainer`.
- Public Bukkit events: `CustomBlockPlace/Break/Interact`, `CraftEngineReloadEvent`, `ServerPreShutdownEvent`.
