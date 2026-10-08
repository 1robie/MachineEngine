# CraftEngine templates and config factories plan (2026-10-08)

Mekanized's CraftEngine configuration (`mekanized/src/main/resources/mekanized/pack/configuration/`) writes every block and item out in full. The 200 blocks take 7,396 lines, and the 958 items include 574 model-only `display/` and `cable_part/` items. Most of these entries are copies of each other with one name changed. CraftEngine has two tools for this, and Mekanized uses neither beyond three settings and loot templates:

- **Templates** (`templates:`): a shared piece of configuration with `${arg}` / `${arg:-default}` placeholders, the built-in `${__NAMESPACE__}` / `${__ID__}` (the entry's id) and typed arguments (`condition`, `when`, `map`, `list`, `expression`, …).
- **Config factories** (`config_factory#<name>:`): a `blueprint:` holding any top-level sections (`items:`, `blocks:`, `recipes:`, `categories:`, …) whose keys and values use `${…}`, and a list of `instances:`. Each instance is one set of arguments, and CraftEngine produces one copy of the whole blueprint per instance. CraftEngine itself builds its trees (`default_assets/configuration/blocks/tree.yml`: log, planks, door, fence, … from one row per tree) and its ten destroy-stage items (`internal/configuration/destroy_stages.yml`) this way.

This plan moves Mekanized to both: **factories for families** (one row per block, the block's item, block and display items come from one blueprint), **templates for the pieces families share** (settings, machine behavior, state shapes). No gameplay change: every block and item must come out exactly as it is today, and a dev-server check (Stage 0) proves it.

## What CraftEngine does (checked against its sources)

Checked against CraftEngine `dev` at `5e6c323` (2026-10-04). `config_factory` is in the core jar we compile against (2026-10-01) and in the dev servers' `craftengine-dev.jar` (2026-10-07).

- **Factory** (`AbstractPackManager.ConfigFactoryParser`): section id `config_factory` or `config_factories`. A suffix `#name` lets one file hold several factories. `blueprint` may also be spelled `prototype` or `schema`, and `instances` may be spelled `inputs`. Each instance's keys become template arguments (`TemplateArguments.fromConfig`, so a value can be a typed argument), and every top-level section of the blueprint is handed to its normal parser with those arguments. The factory stage runs after the template stage.
- **Ids and templates inside a blueprint** (`IdSectionConfigParser#loadAll`): `${…}` in an entry's key is resolved first. The entry's templates are then applied with the instance's arguments **plus** `__NAMESPACE__` / `__ID__`, so templates work inside factories exactly as outside.
- **Keys next to `template:`** are deep-merged over the template's result (`TemplateManagerImpl#processTemplates`), like `merges:`. `overrides:` replaces top-level keys instead.
- **`a::b: value`** is shorthand for `a: {b: value}` (`loot::template: default:loot_table/self`), used throughout CraftEngine's own factories.
- **`debug: true`** on an entry makes CraftEngine log the entry's fully expanded configuration as JSON, after templates and factory arguments. Stage 0 builds on this.
- **Version conditions** (`$$>=1.20.3:` around a section) exist if a family ever needs one per version range.
- **New argument types** (`TemplateArguments.register`) are not needed: every pattern below is plain substitution.

## What repeats today

Measured on the shipped files (2026-10-08):

| Family | Blocks | Items per block today | What differs between rows |
|---|---|---|---|
| Transports: universal cable, pressurized tube, mechanical pipe, logistical transporter, thermodynamic conductor × 4 tiers | 20 | item + block + 19 `cable_part/` model items | tier, `rate` (D34) |
| Factories: 9 kinds × 4 tiers | 36 | item + block + 2 `display/` items | kind, tier |
| Energy cubes × 5 tiers | 5 | item + block + frame, 4 core levels, 6 ports | tier, capacity (D34) |
| Fluid tanks, chemical tanks, bins × 5 tiers | 15 | item + block + 1 `display/` item | tier, capacity (D34) |
| Induction cells and providers × 4 tiers | 8 | item + block | tier, numbers (D34) |
| Single machines, solid `facing` + `active` (enrichment chamber, crusher, …) | 15 | item + block (~57 lines) | the id |
| Single machines, display-rendered | ~60 | item + block + `display/` item(s) | the id, the shared visual state id, D34 options |
| Other machines: solid, `facing` only, `active` only, `mode`, pressure plate | ~31 | item + block | the id |
| Storage blocks (`block_bio_fuel`, metal blocks, …) | 25 | item + block | the id |
| Ores | 10 | item + block | already use `default:settings/ore` and `default:loot_table/ore`; left alone |

Shared by nearly all of them: the machine settings (`hardness: 3.5`, `resistance: 8.0`, pickaxe tags and tools, written 163 times), `behavior: {type: machineengine:machine, machine: <its own id>, keep_contents: true}` with the keep-contents loot (~180 times), and the block item (`item_name: <!i><lang:block.mekanized.<id>>`, `model`, `block_item` of the same id; 200 times, 173 of them fully derivable from the id).

## Decisions

Owner, 2026-10-08:
1. **Where the templates live.** Generic ones go in the engine's `templates.yml` (`machineengine:`, rewritten at every start as today). Mekanized-only ones (its hardness values, its lang key format) go in a new Mekanized `templates.yml` (`mekanized:`).
2. **Items and blocks stay separate**: no inline `block:` inside `block_item`. A blueprint has its own `items:` and `blocks:` sections, so this holds inside factories too.
3. **The Mekanized templates file is rewritten at every start**, not installed through `EditableFiles`. A server owner who wants a different template copies it under another name.

Delegated:
4. **A template never holds a per-block D34 number** (`options:`, `storages:`, `ports:`, `rate:`). It may hold numbers a whole family shares (hardness, resistance).

Owner, 2026-10-08:
5. **Where a factory row's D34 numbers go.** *Decided:* in the instance row, as named arguments the blueprint puts into `behavior:` (for example `- {tier: basic, rate: 8000}`). The number still sits next to its block, in the file owners edit, and a tier family reads as a table. This amends D34's wording ("in the CraftEngine block config") and needs a log entry. Rejected: factories only for families without numbers, and blocks with numbers stay written out under `blocks:`. That keeps D34 as written, but it leaves transports, tanks, cubes and cells (the biggest families) unchanged.

## Design

**Engine templates (`machineengine:`):**
- `machineengine:block/machine` (done, Stage 1): the machine behavior with `machine: ${__NAMESPACE__}:${__ID__}`, `keep_contents: true` and the keep-contents loot. Literal values, not `${…:-default}` arguments, so they keep their YAML type; a block with another machine id writes `behavior: {machine: …}` next to the template.
- `machineengine:block/transport`: the transport behavior, `loot: self`, the shared invisible state (`auto_state: {type: sapling, id: ${state_group}}`).
- `machineengine:block_state/facing`, `…/active`, `…/facing_active`: solid states rotated by `facing`, with model `${model:-"${__NAMESPACE__}:block/${__ID__}"}` and active model `${active_model:-"${model}_active"}`.
- `machineengine:block_state/display_rendered`, `…/display_rendered_facing`: one appearance with `auto_state: {type: ${state_type:-leaves}, id: ${display_state}}`. The facing variant maps all four facings to it. The shared state id stays the particle texture's id, as today ([[craftengine-visual-state-pools]]).

Each one gets a comment in `templates.yml` (arguments and an example) like `keep_contents` has, because other addons will use them.

**Mekanized templates (`mekanized:`):** `mekanized:settings/machine`, `…/transparent_machine` (machines drawn by display entities, and transports), `…/storage_block` (done, Stage 1; the two blast-proof personal storage blocks use `overrides: {resistance: 3600000.0}` on the machine template, no template of their own). `mekanized:item/block_item` (name from `lang:block.<ns>.<id>`, model `block/<id>` by default, `block_item` of the same id) and `mekanized:item/named` (name from `lang:item.<ns>.<id>`).

**Factories**, in the file that holds the family today. An example:
```yaml
config_factory#universal_cable:
  instances:
    - {tier: basic, rate: 8000}
    - {tier: advanced, rate: 128000}
    - {tier: elite, rate: 1024000}
    - {tier: ultimate, rate: 8192000}
  blueprint:
    items:
      mekanized:${tier}_universal_cable:
        template: mekanized:item/block_item
      mekanized:cable_part/${tier}_universal_cable/center:
        model: mekanized:block/cable/${tier}_universal_cable/center
      # … the 18 other parts, written once for all tiers
    blocks:
      mekanized:${tier}_universal_cable:
        template: mekanized:block/transport
        arguments: {state_group: mekanized_universal_cable}
        behavior:
          rate: ${rate}
          connection_items: mekanized:cable_part/${tier}_universal_cable
```
Single machines become one factory per shape (rows: `{id: enrichment_chamber}`, `{id: crusher}`, …). A display-rendered machine with its own options keeps a plain `blocks:` entry with `template:` (`mekanized:block/display_machine_facing`) and its `behavior: {options: …}` next to it. A factory row would hide those options among other machines' rows.

Expected size: the 11 content files go from ~10,400 lines (without `lang.yml` and recipes) to roughly 3,000. The 574 model-only items shrink most, because each is written once per family instead of once per tier.

## Checking without unit tests

The Mekanized tests cannot run CraftEngine (it needs a server), so they cannot expand templates or factories. Two consequences:

- **The real check runs on a dev server**: `./gradlew :plugin:ceSnapshot` (buildSrc `CeSnapshot.kt`).
  - `ceSnapshotPrepare` saves the Paper 26.3 dev server's installed Mekanized files and writes the source files in their place, with `debug: true` and `ce_snapshot_id: '<blocks|items> <id>'` added to every block and item entry, inside blueprints too. Mekanized keeps them for that run, because to it they are edited files.
  - The server starts and stops by itself (`SelfStoppingServer`: its console types `stop` once `Done (` is logged and the log has been quiet for 8 s).
  - `ceSnapshotCollect` puts the installed files back, even if the run failed, and writes `plugin/build/ce-snapshot/snapshot.json` (keys sorted). It holds every logged block and item, the Mekanized entries of CraftEngine's `custom_block_states.json` and `visual_block_states.json` (so a new visual state or a renumbered block state shows up), and the server's Mekanized warnings.
  - It compares that file with `mekanized/ce-snapshot.json` and prints each difference as a path (`changed: blocks > mekanized:enrichment_chamber > settings > hardness: 3.5 -> 3.6`). `-PacceptCeSnapshot` replaces the reference.
- **The unit tests keep reading the numbers they check** (`ShippedBlocks`, `ShippedItems`, used by `BlockNumbersTest`, `SettingsTest`, …). Their loader learns `config_factory` sections. For each instance it substitutes `${name}` and `${name:-default}` in the blueprint's keys and `behavior:` values, which is plain text replacement with nothing CraftEngine-specific. It does not expand `template:` references. It doesn't need to, because rule 4 keeps every number out of templates. A unit test fails if a blueprint `behavior:` uses a placeholder the loader cannot resolve.

## Stages

Each stage builds, passes the tests and matches the snapshot on the dev server, and then it is committed.

**Stage 0 — the snapshot. Done (2026-10-08).** `ceSnapshot` and the reference from today's files: 200 blocks, 958 items, no warnings. Two runs give the same result, and a changed hardness is reported. The test loader (`ShippedConfig`, used by `ShippedBlocks` and `ShippedItems`) reads factories (`ShippedConfigTest`).

**Stage 1 — templates. Done (2026-10-08).** The Mekanized `templates.yml` (installed with replace at every start, `MekanizedPlugin.TEMPLATES`) with the three settings templates, and `machineengine:block/machine` in the engine's. The 190 settings overrides and the 145 machines' behavior and loot use them: −2,629 / +335 lines, `ceSnapshot` matches. `ShippedBlocks` fills in what `machineengine:block/machine` gives (type, the block's id, `keep_contents`), since `BlockNumbersTest` needs the machine id. Each later template is added with the stage that first uses it, so none ships untested.

**Stage 2 — transports. Done (2026-10-08).** One factory per file (`energy_transports` in machines.yml, `chemistry_transports`, `item_transports` in logistics.yml, `heat_transports` in reactors.yml), one row per block (`transport`, `rate`): 20 blocks, 400 items (each transport has 19 parts, not 16). Templates `machineengine:block/transport` (engine) and `mekanized:block/transport` (Mekanized's shared invisible state, settings and `connection_items`). −1,220 / +288 lines, `ceSnapshot` matches (rates keep their integer type through `${rate}`). The snapshot marker learned keys with `#` (`config_factory#name`).

**Stage 3 — tiered storage. Done (2026-10-08).** Factories `energy_cubes` (machines.yml), `fluid_tanks` and `chemical_tanks` (chemistry.yml), `bins` (utility.yml), `induction_parts` (multiblocks.yml): 33 blocks, 88 items, −1,180 / +404 lines, `ceSnapshot` matches.
- A row holds its block's numbers as a `behavior:` map written as on a block (`storages:`, `ports:`; creative: `behavior: {}`), and the blueprint writes `behavior: ${behavior}`. A plain number per row (`rate: ${rate}`, Stage 2) cannot express a tier with no number.
- A blueprint entry cannot write both fixed `behavior:` keys and the row's map, so families with fixed keys (`display_item`, `level_display`, …) get a family template: `mekanized:block/energy_cube` (argument `tier`), `…/chemical_tank`, `…/fluid_tank`. Bins have none and keep their whole shape in the blueprint.
- New engine state template `machineengine:block_state/facing_active` (`model`, `active_model`, defaults `block/<id>` and `block/<id>_active`), used by the bins.
- `ShippedBlocks` follows template chains through Mekanized's `templates.yml` (`ShippedConfig.usesTemplate`) to know a block is a `machineengine:block/machine`.

**Stage 4 — factory machines. Done (2026-10-08).** One factory, `factories` in logistics.yml, 36 one-line rows `{ kind: smelting, tier: basic }`: 36 blocks and 108 items (block item, idle and working display items), `ceSnapshot` matches. They have no per-block numbers (their lanes are structural, in Java). New templates, which Stage 5 reuses:
- `machineengine:block_state/display_rendered_facing` (engine): one invisible appearance for the four facings; arguments `display_state`, `display_model`, `state_type` (default `leaves`).
- `mekanized:block/display_machine_facing`: a machine with `display_item: <ns>:display/<id>`, transparent settings and that state. `display_rotations` stays on the block: not every display machine has it (the digital miner does not).
- `mekanized:block/chemical_tank` is rebuilt on it (same snapshot).

**Stage 5 — single machines.** Factories by shape for the ones without options. Templated `blocks:` entries for the ones with options.

**Stage 6 — storage blocks and plain items.** Storage blocks (factory) and `mekanized:item/named` where it fits.

**Stage 7 — docs.** In `MEKANIZED_GUIDE.md`: how to read a factory (one row per block), how to change one block (edit its row, or move it out of the factory into `blocks:`) or a family (edit the blueprint), and where the templates are. `docs/ARCHITECTURE.md` gets D36 with decisions 1–5.

## Existing servers

Files a server owner edited stay as they are (`EditableFiles` keeps them). They are written out in full and keep working. Unedited files are replaced. Block ids, item ids, state ids and models do not change, so placed blocks and items are unaffected. One case needs care. If an id moved from one file to another, a server with the old file edited (kept) and the new file unedited (replaced) would define that id twice. So each family stays in the file it is in today, and every stage checks that no id moves between files.

## Out of scope

`lang.yml`, crafting recipes (they differ per tier), gear items, ores, and new CraftEngine argument types.
