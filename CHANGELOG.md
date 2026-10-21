# Changelog

Changes to MachineEngine, newest first. Versions follow ARCHITECTURE §5: before 1.0, a minor
version may break the API (each break is listed here); a patch version never does, and CI checks it
(`./gradlew :api:japicmp`, against the last released `machineengine-api`).

## Unreleased (0.2.1)

### Fixed

- Cross-region handoffs: a target keeps the last ids of each source instead of the last 64 overall,
  so a re-sent transfer is no longer inserted twice after other neighbours sent many; a machine
  removed and replaced before the answer still drops its remainder; a machine placed where another
  stood no longer reuses its predecessor's transfer ids.
- World fluids: a chunk loaded again shows the fluid colors at its border with fluid next to it.
- World fluids: changes made just before a shutdown are saved.
- World fluids: dispensers pour and take placeable fluids by the same rules as players (vanilla
  turned them into plain water); a fluid bucket can't be poured into a cauldron.
- `/machineengine network`: inspecting a network no longer races with region threads on Folia.
- `/machineengine fluid bucket` drops the bucket at your feet when your inventory is full.
- Machine tools (wrench, upgrades, storage reader) and machine clicks (bins, personal storage) check
  `machineengine.use` and claims, as opening the menu does: strangers could empty or take over
  machines in a claim.
- Contents above a capacity that shrank (an upgrade removed, a block config change) are kept on
  load instead of being locked away and lost when the block breaks.
- A failed tier replace no longer leaves a dead machine or loses its contents.
- Cable side modes changed since the last autosave survive a shutdown.
- Chance outputs no longer repeat after a chunk reload: the machine's random is saved.
- A saved process bound to a storage renamed in YAML is kept as unknown data (purge frees the
  machine) instead of failing on every tick.
- Player data loaded for a login that never joins is dropped after a minute.

### Changed

- `/machineengine fluid fill`, `bucket` and `clear` need the new permission
  `machineengine.admin.fluid.edit` (OP by default) on top of `machineengine.admin`. `fill` stops at
  32,768 blocks; `clear` says it reaches loaded chunks only.
- Faster: chunk loads and unloads with much placed fluid, fluid padding checks, upgrade modifiers
  on capacity and rate reads, and slot changes next to networks (no world-wide lock).

### API

- No breaking change.

## 0.2.0 (2026-10-21)

### Added

- Fluid types: `FluidType` extends `ResourceType`; fluid definitions, effects, reactions and
  textures; placeable world fluids with buckets, flow, viscosity and colored water through key
  biomes and a generated resource pack (vanilla and Sodium shaders); machines place and take
  world fluids; `WorldFluids` service and events.
- Bukkit events in `api` (D43).
- Messages and logs through MessageFlow, translatable per player (D44, D45), French bundled.
- Maven repository `https://1robie.github.io/maven/` with the API Javadoc.

### API

Binary breaks from 0.1.0 (source compatible: `FluidType` is a `ResourceType`; recompile addons):

- `Fluids.water()`, `Fluids.lava()` and both `Fluids.create(...)` return `FluidType` instead of
  `ResourceType`.
- `BuiltInResources.Built`: `water()` and `lava()` return `FluidType`, and its record constructor
  takes `FluidType` for them.

## 0.1.0 (2026-10-11)

First release: the machine engine (machines, storages, ports, processes, networks, cross-region
handoffs), CraftEngine blocks and behaviors, upgrades and modules, player data, machine menus, and
the headless test harness (`machineengine-test`).
