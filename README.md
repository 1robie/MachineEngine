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
| `api` | Public API and SPI. |
| `common` | Implementation: the engine (`core` packages), its Paper integration and its CraftEngine integration. |
| `machineengine-test` | Headless test harness for MachineEngine and addons. |
| `machineengine-zmenu` | Optional zMenu integration (machine menus). |
| `machineengine-mekanized` | Mekanism-style content plugin (imports Mekanism's data at build time). |
| `machineengine-plugin` | The plugin jar. |

The modules are being reorganised ([docs/RESTRUCTURE_PLAN.md](docs/RESTRUCTURE_PLAN.md)). Every module
compiles against the newest Paper API (26.3) and CraftEngine. `check` fails if the bytecode uses
anything missing from Paper 1.20.6, or is newer than Java 21 (`verifyPaperBaseline`).

## Building

```bash
./gradlew build
```

The plugin jar is written to `machineengine-plugin/build/libs/`. The build also fetches the pinned
Mekanism commit into `.mekanism/` (sparse, a few MB) for Mekanized; later builds skip the network.

## Dev servers

```bash
./gradlew :machineengine-plugin:runPaper-1.20.6
./gradlew :machineengine-plugin:runPaper-26.3
./gradlew :machineengine-plugin:runFolia-1.20.6
./gradlew :machineengine-plugin:runFolia-26.2
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

Dev servers download zMenu automatically (`-PwithZMenu=false` to leave it out).

## YAML definitions

Server owners can define resources, recipes and whole machines without Java in
`plugins/MachineEngine/definitions/*.yml`. A commented `example.yml` (disabled) is installed on
first start. Machines take a `behavior` type: `machineengine:processing` (runs recipes),
`machineengine:storage`, or one an addon registers. Recipes reload with
`/machineengine reload recipes`; machines and resources need a restart. Invalid entries are skipped
and listed in `/machineengine report` with their file and path.

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

Add `keep_contents: true` to a block's `machineengine:machine` behavior, and a player breaking it
gets one item holding the machine's contents, process and upgrades; placing it resumes the machine.
Explosions and other non-player breaks drop the contents as usual. Empty machines drop plain items.

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

Dev servers install Mekanized next to MachineEngine (`-PwithAddons=false` leaves it out). From the
server console:

```text
forceload add 0 -16 15 15
mekanized place-set 0 100 0 world
machineengine machine 0 100 0 world
```

In game, positions are optional: `/mekanized place-set` builds on the block you look at, and
`/machineengine machine` inspects the block you look at. Coordinates accept `~`, and the world (a
name like `world` or a key like `minecraft:overworld`) defaults to yours.

Server checks of engine features (admins; results in the console log, each with its expected value):
- `/mekanized check-drop`: a bin with 100 iron ingots broken without a player drops all 100.
- `/mekanized check-hopper`: hopper → bin → hopper → chest moves all 10 ingots into the chest.
- `/mekanized check-comparator`: a comparator reads 8 from a half-full bin and 0 from an empty one.
- `/mekanized fill <size>`: a grid of idle crushers, to measure the idle cost.

Mekanized's own checks: `/mekanized test`, `test-cable`, `test-chemistry`.

Admin commands (`machineengine.admin`): `/machineengine status`, `report`,
`machine [<x y z>] [world]` (alias `inspect`), `network [<x y z>] [world]`,
`placeholders [<x y z>] [world]` (menu placeholder values), `placeholders list` (loaded machines keeping data of
missing addons or resources), `placeholders purge [<x y z>] [world]` (deletes that data, run twice to confirm),
`registry <name>`, `reload recipes|tags`.

`/machineengine debug` toggles an in-world debug view for you:
- **Machine dots:** a colored dot above each machine shows its state: green active, blue sleeping, orange blocked, red error.
- **Port dots:** blue input, orange output, purple both.
- **Cables:** dots in each network's color, with sparks while the network moves resources.
- **Action bar:** details of the machine you look at.

## Mekanized (Mekanism-style content)

`machineengine-mekanized` is a content plugin built on MachineEngine. Server owners: see
[docs/MEKANIZED_GUIDE.md](docs/MEKANIZED_GUIDE.md); its development plan is
[docs/MEKANIZED_PLAN.md](docs/MEKANIZED_PLAN.md). It is inspired by Mekanism and uses its textures,
models and recipes (Mekanism by Aidan C. Brady, MIT; `LICENSE-MEKANISM.txt` ships with it). It is not
affiliated with Mekanism. The build imports one pinned Mekanism commit:

```
./gradlew :machineengine-mekanized:importMekanism
```

Review `machineengine-mekanized/build/generated/mekanized/import-report.txt` for what was converted
and skipped.

## License

GPL-3.0. See [LICENSE](LICENSE).
