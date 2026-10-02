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
| `machineengine-api` | Public API and SPI. Server-free. |
| `machineengine-core` | Implementation. Server-free. |
| `machineengine-test` | Headless test harness for MachineEngine and addons. |
| `machineengine-paper` | Paper integration. |
| `machineengine-craftengine` | CraftEngine adapter (the only module importing CraftEngine). |
| `machineengine-plugin` | The plugin jar. |

`api`, `core` and `test` fail `check` if Paper, Bukkit or CraftEngine reach their classpaths.

## Building

```bash
./gradlew build
```

The plugin jar is written to `machineengine-plugin/build/libs/`.

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

Servers run in `run/paper-<version>/`. On first start Paper stops and asks you to accept the
Minecraft EULA in `run/paper-<version>/eula.txt`.

## Machine menus (zMenu)

With [zMenu](https://modrinth.com/plugin/zmenu) installed, right-clicking a machine opens its
menu. Menus are ordinary zMenu inventories in `plugins/MachineEngine/menus/`:

- `machine.yml` is the default menu; `<namespace>_<value>.yml` (e.g. `example_crate.yml`) is used
  for that machine type; `menus.by-type` in `config.yml` overrides both.
- `%zmenu_machineengine_<key>%` placeholders show live machine values (state, storages, process
  bar …); see the comments in `machine.yml` for the full list.
- Button types `machineengine_slot` (a storage slot players can put items into or take from) and
  `machineengine_cancel_process`.

Dev servers download zMenu automatically (`-PwithZMenu=false` to leave it out).

## Example addon and commands

`machineengine-example` is a small addon built only on the API: a spark generator and a spark
consumer, with the CraftEngine pack that defines their blocks. Dev servers install it by default
(`-PwithExample=false` to leave it out). From the server console:

```text
forceload add 0 -16 15 15
mexample place 0 100 0 world
machineengine machine 0 100 0 world
```

In game, positions are optional: `/mexample place` builds on the block you look at, and
`/machineengine machine` inspects the block you look at. Coordinates accept `~`, and the world (a
name like `world` or a key like `minecraft:overworld`) defaults to yours.

Admin commands (`machineengine.admin`): `/machineengine status`, `report`,
`machine [<x y z>] [world]` (alias `inspect`), `network [<x y z>] [world]`,
`placeholders [<x y z>] [world]`, `registry <name>`, `reload recipes|tags`.

## License

GPL-3.0. See [LICENSE](LICENSE).
