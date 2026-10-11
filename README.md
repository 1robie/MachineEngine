# MachineEngine

Server-side machine and resource infrastructure framework for Paper, built on
[CraftEngine](https://github.com/Xiao-MoMi/craft-engine).

MachineEngine provides mechanisms, not meanings: registries, resources, storage, transactional
transfers, ports, capabilities, recipes, processes, scheduling and persistence. Addons define the
actual resources and machines. The architecture and every binding decision are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

| | |
|---|---|
| Server | Paper 1.20.6 – 26.3 |
| Java | 21 (1.20.6) or 25 (26.x) at runtime; JDK 25 to build |
| Required plugin | CraftEngine (built against the `dev` snapshot, currently `26.10-SNAPSHOT`) |

## Modules

| Module | Purpose |
|---|---|
| `api` | Public API and SPI, including the Paper-facing services addons use (`api.paper`) and version access (`api.platform`). |
| `common` | Implementation: the engine (`core` packages), its Paper integration and its CraftEngine integration. |
| `hooks/zmenu` | Optional zMenu integration (machine menus). One `hooks/<plugin>` module per optional plugin. |
| `nms/common` | Reflective fallback for Minecraft versions without a module of their own. |
| `nms/v1_20_6`, `nms/v26_3` | Version-specific code, built with paperweight userdev against each version. |
| `plugin` | The plugin jar (everything above, plus the dev servers). |
| `test` | Headless test harness for MachineEngine and addons, built on the assembled plugin. |

```text
api <- nms:common <- common <- hooks:*  <- plugin <- test
                       nms:v1_20_6, nms:v26_3 <- plugin
```

Every module compiles against the newest Paper API (26.3) and CraftEngine, and emits Java 21
bytecode except `nms/v26_3` (Java 25, loaded only on 26.3). `check` fails if the bytecode uses
anything missing from Paper 1.20.6, or is newer than Java 21 (`verifyPaperBaseline`). Why it is laid
out this way: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §5 and decisions D24-D28.

## Building

```bash
./gradlew build
```

The plugin jar is written to `plugin/build/libs/`.

## Dev servers

```bash
./gradlew :plugin:runPaper-1.20.6
./gradlew :plugin:runPaper-26.3
./gradlew :plugin:runFolia-1.20.6
./gradlew :plugin:runFolia-26.2
```

By default the dev servers use CraftEngine built from its `dev` branch (`craftengine_dev_ref`), the
same snapshot MachineEngine compiles against. The first run clones and builds CraftEngine into
`.craftengine/`; rebuild it with `-PrefreshCraftEngine`. To use the published Modrinth release
instead, pass `-Pcraftengine_runtime=modrinth`; `-Pcraftengine_runtime=none` starts a server without
CraftEngine (MachineEngine must then refuse to load).

Servers run in `run/paper-<version>/` (`run/folia-<version>/` for the Folia tasks). On first start Paper stops and asks you to accept the
Minecraft EULA in `run/paper-<version>/eula.txt`.

## Machine menus (zMenu)

With [zMenu](https://modrinth.com/plugin/zmenu) installed, right-clicking a machine opens its
menu. Menus are ordinary zMenu inventories in `plugins/MachineEngine/menus/`:

- `machine.yml` is the default menu; `<namespace>_<value>.yml` (e.g. `mekanized_basic_bin.yml`) is used
  for that machine type; `menus.by-type` in `config.yml` overrides both.
- `%zmenu_machineengine_<key>%` placeholders show live machine values (state, storages, process
  bar …); see the comments in `machine.yml` for the full list.
- Button types `machineengine_slot` (a storage slot players can put items into or take from) and
  `machineengine_cancel_process`.
- `machineengine_upgrades`: the upgrade tab. Clicking with upgrades installs them (right-click: one);
  an empty hand opens the upgrades menu (`menu: <file>`, else `<the machine's menu>_upgrades`).
- `machineengine_action` (`action`, `index`, `list`, `input`, `empty`): runs one of the machine
  type's actions with the click and the held item (D40). Every machine with upgrade slots has the
  action `upgrade` and the list `upgrades`; addons add their own (Mekanized's modification station:
  `module` / `modules`). A list row shows with `%zmenu_machineengine_list_<id>_<index>_<field>%`
  (`list_<id>_size` for its length); past the end the button hides, or shows its `empty:` item:
  ```yaml
  upgrade-0:
    type: machineengine_action
    action: upgrade        # held upgrades: install; empty hand: take one out (shift: all)
    list: upgrades
    index: 0
    slot: 19
    item:
      material: "craftengine:%zmenu_machineengine_list_upgrades_0_item%"
      name: "%zmenu_machineengine_list_upgrades_0_count%/%zmenu_machineengine_list_upgrades_0_max%"
    empty: { material: LIGHT_GRAY_STAINED_GLASS_PANE, name: "<gray>No upgrade" }
  input:
    type: machineengine_action
    action: upgrade
    input: true            # shift-clicking upgrades in the player's inventory installs them
    index: 99
    slot: 40
    item: { material: HOPPER, name: "Put upgrades here" }
  ```

Dev servers download zMenu automatically (`-PwithZMenu=false` to leave it out).

## Configuration

`plugins/MachineEngine/config.yml` is written on first start, with a comment above every setting.
Settings added by an update are filled in with their defaults at the next start; your values and
comments are kept. A wrong value is reported in the log and its default is used until you fix it.
`/machineengine reload config` applies changes without a restart, except the settings marked
"Restart to apply".

MachineEngine provides an energy and fluids every addon and definition can share:
`machineengine:energy`, and the fluid category `machineengine:fluid` with
`machineengine:fluid/water` and `machineengine:fluid/lava`. Their units (`J`, `mB`) and the bucket
size (1000) are set under `resources:` in `config.yml`. Addons add their own fluids to the same
category, so any fluid pipe or port takes them.

## Player data and settings

Addons keep values per player through MachineEngine (`MachineEngine.get().players()`, D37): saved
values such as Mekanized's radiation dose, values kept while the player is online, and settings
players change themselves.
- **Storage:** `players.storage` in `config.yml` (restart to apply). `machineengine:pdc` (default)
  saves with the player's own data file; `machineengine:files` writes
  `plugins/MachineEngine/players/<ab>/<uuid>.dat` and lets admins edit offline players. Addons may
  add others. Switching does not move existing data.
- **Saving:** every `players.save-interval` seconds (60) when something changed, when the player
  leaves, and when the server stops. A player whose data cannot be loaded within
  `players.load-timeout` seconds is refused rather than let in without it.
- **Settings:** players use `/machinesettings` (alias `/msettings`, permission
  `machineengine.settings`, everyone by default): no argument opens the zMenu menu
  `player_settings` (or lists the settings), `/machinesettings <setting> <value>` changes one,
  `/machinesettings <setting> reset` puts it back. `players.setting-defaults` sets the default of
  a setting, `players.locked-settings` keeps the default and stops players from changing it.
- **Menus:** `player_settings.yml` is written at every start with a button per setting, until you
  delete its first line to keep your own version. In any menu,
  `%zmenu_machineengine_player_<namespace>_<id>%` shows a player's value and the button type
  `machineengine_player_setting` (`setting: <namespace>:<id>`) changes a setting.

## YAML definitions

Server owners can define resources, recipes and whole machines without Java in
`plugins/MachineEngine/definitions/*.yml`. A commented `example.yml` (disabled) is installed on
first start. Machines take a `behavior` type: `machineengine:processing` (runs recipes),
`machineengine:storage`, `machineengine:multiblock_member`, or one an addon registers. Recipes reload with
`/machineengine reload recipes`; machines and resources need a restart. Invalid entries are skipped
and listed in `/machineengine report` with their file and path.

## Multiblocks

A `multiblocks:` section in a definitions file declares structures, and machines with the
`machineengine:multiblock_member` behavior are their blocks. Two shapes:
- `box`: a hollow box of 3 to 18 blocks a side, with casings on the edges (Mekanism's tanks and
  reactors are boxes).
- `pattern`: any fixed shape, written as layers of rows with a character key, like a crafting
  recipe in 3D. It is found in any of the four horizontal rotations.

A structure forms as soon as its last block is placed and unforms when one is broken. Its anchor
block holds its storages, sized by `capacities`.

```yaml
multiblocks:
  mypack:altar:
    pattern:
      layers:                     # bottom up; rows north to south; characters west to east
        - ["CCC", "C#C", "CCC"]
        - ["   ", " G ", "   "]   # a space is any block
      key: {C: mypack:altar_casing, "#": mypack:altar_core, G: [minecraft:glass, minecraft:tinted_glass]}
      anchor: "#"
    capacities: {mana: 64000}
machines:
  mypack:altar_core:
    storages: {mana: {capacity: 1}}
    behavior: {type: machineengine:multiblock_member, multiblocks: [mypack:altar]}
  mypack:altar_casing:
    behavior: {type: machineengine:multiblock_member, multiblocks: [mypack:altar]}
```

Members can also be `role: valve` or `role: port`, passing the anchor's `storages` through.
Addons use the same types from Java: `api.multiblock`, `MultiblockMember.of(types)`.

## Items

Items get MachineEngine's CraftEngine item behaviors in their `behaviors:` list:
- `machineengine:energy_item` (`max-energy`, `max-input`, `max-output`);
- `machineengine:resource_item` (`resource`, `capacity`, `fill-from-machines`);
- `machineengine:storage_reader`, which prints a machine's storages;
- `machineengine:wrench`, with `click` and `sneak-click` each `rotate`, `dismantle` or `none`;
- `machineengine:upgrade` (`upgrade`: the upgrade type, the item's own id by default): the item is a
  machine upgrade. Install it from the machine's menu, or right-click the machine with it (sneak:
  as many as fit). An item without it whose id is an upgrade's key still works, with a warning in
  the log; that rule will be removed.

Machines charge or fill any item with these behaviors. Add `machineengine:resource_lore: {}` under the item's `client-bound-data:` to show what it holds, in each player's language.

## Hoppers

Hoppers, droppers and hopper minecarts move items in and out of machines through their ports. The
side the hopper touches selects the port, and the port's direction and filter apply. Add
`hoppers: false` to a block's `machineengine:machine` behavior to turn this off.

## Translations

Player-facing text (item lore, messages) is translated on each player's client. Edit
`plugins/CraftEngine/resources/machineengine/configuration/lang.yml` to change wording or colors
(MiniMessage, `%s` for arguments), or add a language section (`de_de`, `es_es` …). English and French
ship by default. The file is never overwritten.

## Comparators

A comparator next to a machine reads how full it is, like a chest (`comparator: false` on the block
behavior turns this off).

## Keeping contents in the item

Add `keep_contents: true` to a block's `machineengine:machine` behavior and
`loot: {template: machineengine:loot_table/keep_contents}` to the block, and a player breaking it
gets one item holding the machine's contents, process and upgrades; placing it resumes the machine.
Explosions and other non-player breaks drop the contents as usual. Empty machines drop plain items.

## CraftEngine templates

MachineEngine ships CraftEngine templates for blocks that use its types
(`plugins/CraftEngine/resources/machineengine/configuration/templates.yml`, rewritten at every start; each
template is described there). Put one at the top of a block and write what is the block's own next to it:
keys next to `template:` are merged in.

```yaml
blocks:
  example:crusher:
    template: machineengine:block/machine       # machine of the same id, keeps its contents
    behavior:
      options: { energy-per-tick: 40 }
    settings: { template: default:settings/solid_1x1x1 }
    states:
      template: machineengine:block_state/facing_active   # block/<id>, block/<id>_active
```

- `machineengine:block/machine`, `machineengine:block/transport`: the behavior and drops.
- `machineengine:block_state/solid`, `facing`, `active`, `facing_active`: solid models (argument `model`,
  `active_model`).
- `machineengine:block_state/display_rendered`, `display_rendered_facing`, `display_rendered_facing_6`: blocks
  drawn by display entities, sharing one visual state (arguments `display_state`, `display_model`).

Mekanized uses them with CraftEngine config factories (`config_factory`) to declare its tiers once.

## Bukkit events

Plugins that do not use the MachineEngine API can listen to ordinary Bukkit events in
`fr.robie.machineengine.paper.event`:
- `MachineCreateEvent`, `MachineLoadEvent`, `MachineUnloadEvent` and `MachineRemoveEvent`;
- `MachineProcessStartEvent` and `MachineProcessCompleteEvent`;
- `PlayerMachineInteractEvent`, which is cancellable and fires before a menu opens;
- `MachineUpgradeChangeEvent` and `MachineEngineReloadEvent`.

Per-tick resource changes (`MachineEvents.RESOURCES_CHANGED`) are only on the MachineEngine event bus.

They fire on the machine's thread (its region on Folia). `events.bukkit-bridge: false` in
`config.yml` turns the mirrored events off.

## Commands and server checks

In game, positions are optional: `/machineengine inspect` (alias `machine`) inspects the block you
look at. Coordinates accept `~`, and the world (a
name like `world` or a key like `minecraft:overworld`) defaults to yours.

Server checks of engine features on MachineEngine's dev servers (admins; results in the console log,
each with its expected value). They place blocks only the dev servers install (`plugin/src/dev`):
- `/machineengine check drop`: a bin with 100 iron ingots broken without a player drops all 100.
- `/machineengine check hopper`: hopper → bin → hopper → chest moves all 10 ingots into the chest.
- `/machineengine check comparator`: a comparator reads 8 from a half-full bin and 0 from an empty one.
- `/machineengine check fill <size>`: a grid of idle crushers, to measure the idle cost.

Admin commands (`machineengine.admin`): `/machineengine status`, `report`,
`machine [<x y z>] [world]` (alias `inspect`), `network [<x y z>] [world]`,
`placeholders [<x y z>] [world]` (menu placeholder values), `placeholders list` (loaded machines keeping data of
missing addons or resources), `placeholders purge [<x y z>] [world]` (deletes that data, run twice to confirm),
`registry <name>` (with its aliases: old keys still accepted), `reload config|recipes|tags`,
`player <player> [get|set|reset <key> [value]]` (a player's data and settings; offline players with
`players.storage: machineengine:files`).

Changing a machine (admins; the block you look at, or `<x y z> [world]` after the arguments):
- `/machineengine fill <storage> <resource> <amount>`: puts energy, a fluid, a chemical or an item
  (`minecraft:iron_ingot`, a CraftEngine item id) into a storage. It takes only what the storage accepts.
- `/machineengine drain <storage>`: empties a storage.
- `/machineengine wake`: wakes up a sleeping machine.

A wrong storage name lists the machine's storages.

`/machineengine debug` toggles an in-world debug view for you:
- **Machine dots:** a colored dot above each machine shows its state: green active, blue sleeping, orange blocked, red error.
- **Port dots:** blue input, orange output, purple both.
- **Cables:** dots in each network's color, with sparks while the network moves resources.
- **Action bar:** details of the machine you look at.

## Mekanized (Mekanism-style content)

[Mekanized](https://github.com/1robie/Mekanized) is a content plugin built on MachineEngine's API, in
its own repository. It is inspired by Mekanism and uses its textures, models and recipes (Mekanism by
Aidan C. Brady, MIT). It is not affiliated with Mekanism.

## License

GPL-3.0. See [LICENSE](LICENSE).
