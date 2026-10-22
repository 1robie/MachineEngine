# MachineEngine

Tech-mod machines on Paper and Folia servers, with no client mod.

MachineEngine is a framework: it provides machines that store, process and move energy, fluids and
items, cables between them, multiblock structures, upgrades and machine menus — and leaves the
content to **addons** (such as [Mekanized](https://github.com/1robie/Mekanized), Mekanism's
machines) and to **your own YAML**. Blocks and items are
[CraftEngine](https://github.com/Xiao-MoMi/craft-engine) blocks and items, sent to players in its
resource pack; menus are [zMenu](https://modrinth.com/plugin/zmenu) inventories.

**Documentation:** [1robie.github.io/plugins/machineengine](https://1robie.github.io/plugins/machineengine/)

## Requirements

| | |
|---|---|
| Server | Paper or Folia, Minecraft 1.20.6 – 26.3 (one jar) |
| Java | 21 for 1.20.6, 25 for 26.x |
| Plugins | [CraftEngine](https://modrinth.com/plugin/craftengine) (required), [zMenu](https://modrinth.com/plugin/zmenu) (for menus) |

## Installing

1. Download MachineEngine from [Hangar](https://hangar.papermc.io/1robie/MachineEngine) or the
   [GitHub releases](https://github.com/1robie/MachineEngine/releases).
2. Put CraftEngine, MachineEngine and zMenu in `plugins/`, with any addon, and start the server.
3. Run `/ce reload all` so CraftEngine builds its resource pack and sends it to players.
4. Check with `/machineengine status`.

Details: [installing](https://1robie.github.io/plugins/machineengine/installation/installing).

**Dev builds** of the next version (unstable, not for production servers) are on the Hangar
Snapshot channel and the GitHub pre-release
[`dev`](https://github.com/1robie/MachineEngine/releases/tag/dev), rebuilt on every change to
`develop`.

## Features

- **Machines** that sleep when there is nothing to do, and keep their state with their block in the
  world.
- **One energy and shared fluids** for every addon, so any cable or pipe carries them.
- **Cables and pipes** that connect by themselves, with pull, push and per-side modes.
- **Multiblocks**: hollow boxes (tanks, reactors) or any fixed shape.
- **Upgrades**, installed from the machine's menu.
- **Textured menus** drawn by the resource pack, with live bars and slots.
- **Hoppers and comparators** work with machines like with chests.
- **Keep contents**: a machine broken by a player drops as one item holding everything.
- **Safe uninstalls**: machines of a removed addon keep their data until it comes back.
- **Folia**, designed for regionised threading from the start.

## Make your own machines

Without Java, in `plugins/MachineEngine/definitions/*.yml`: resources, recipes, processing and
storage machines, multiblocks — then a CraftEngine block for each.

```yaml
machines:
  mypack:iron_press:
    storages:
      input:  { slots: 1, capacity: 64, filter: { item: minecraft:raw_iron } }
      output: { slots: 1, capacity: 64 }
      energy: { capacity: 10000, filter: { type: machineengine:energy } }
    behavior: { type: machineengine:processing, recipes: mypack:pressing, inputs: [input], output: output, costs: energy }
```

See [YAML definitions](https://1robie.github.io/plugins/machineengine/building/definitions) and
the rest of [Building with it](https://1robie.github.io/plugins/machineengine/building).

## For developers

Addons depend on `machineengine-api` and test headless with `machineengine-test`, from the Maven
repository `https://1robie.github.io/maven/`:

```kotlin
repositories { maven("https://1robie.github.io/maven/") }
dependencies { compileOnly("com.github.1robie.MachineEngine:machineengine-api:0.2.0") }
```

See the [Developer API](https://1robie.github.io/plugins/machineengine/api) and the
[Javadoc](https://1robie.github.io/maven/javadoc/machineengine-api/latest/). Building MachineEngine
itself, dev servers and releases: [CONTRIBUTING.md](CONTRIBUTING.md). The design and every decision:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## License

GPL-3.0. See [LICENSE](LICENSE).
