# Block numbers plan (draft, 2026-10-08)

Gear keeps its numbers in its CraftEngine item config (D32). Mekanized's blocks do not: their numbers are either global settings in `plugins/Mekanized/config.yml` (`MekanizedConfig`) or constants in Java. This plan moves the numbers that belong to one block next to that block, the way D32 did for items, and leaves world-wide rules in `config.yml`.

No gameplay change: every number keeps its current value, and a test reads the shipped YAML to check it (as `SettingsTest` does for gear).

## What is where today

**Per block, in `config.yml` (move):**
- `transport.universal-cable`, `pressurized-tube`, `mechanical-pipe`, `logistical-transporter`: one rate per tier, so one per transport block (`TransportType.of(..., rate, ...)` in `ChemistryMachines`, `EnergyBehaviors`, `LogisticsMachines`).
- `qio.drive-items`, `qio.drive-kinds`: one pair per QIO drive item.
- `qio.array-kinds`, `qio.importer-exporter-rate`: the drive array, importer and exporter.
- `radiation.barrel-capacity`, `radiation.barrel-decay-per-second`: the radioactive waste barrel.
- `digital-miner.*`: the digital miner.
- `chunk-loading.*`: the dimensional stabilizer.
- `dynamic-tank.*`, `thermal-evaporation.*`: the dynamic tank and the evaporation tower.

**World-wide, in `config.yml` (stay):**
- `radiation.*` except the barrel: background, source and dose decay, sickness, damage and lethal doses.
- `teleporter.*`: shared by the teleporter block and the portable teleporter.

**Per block, hard-coded in Java (move):**
- `UtilityMachines.BIN_CAPACITY` (5 bins), `CHARGEPAD_RATE`.
- `HeatMachines.CONDUCTOR_RATES` (4 thermodynamic conductors), `FUELWOOD_HEAT`.
- `MultiblockMachines.CELLS`, `PROVIDERS` (induction cells and providers, 4 tiers each).
- `LogisticsMachines.LANES` (logistical sorter per tier).
- `GaugeDropper.CAPACITY` (an item: belongs in the dropper's item config, D32).
- `Liquifier.TICKS`, `ENERGY_PER_TICK`, `PASTE_PER_FOOD` (nutritional liquifier).
- `ChemistryBehaviors.HEAVY_WATER_PER_BLOCK` (electric pump with a filter upgrade).
- `AutomationMachines.VIBRATOR_ENERGY` (seismic vibrator), `UtilityBehaviors.BEAM_RANGE`, `ENERGY_PER_HARDNESS` (laser).
- `PaperFurnaceRecipes.JOULES_PER_TICK` (furnace recipes run by the energized smelter).

**Multiblock physics, hard-coded in Java (move to the multiblock type):**
- `Boilers.HEAT_PER_MB`, `BOIL_PER_ELEMENT`.
- `Fission.FISSION_HEAT_PER_MB`.
- `Fusion.FUSION_IGNITION`, `FUSION_ENERGY_PER_MB`.
- `Sps.SPS_POLONIUM_PER_ANTIMATTER`, `SPS_ENERGY_PER_POLONIUM`.
- `Turbines.STEAM_ENERGY`, `FULL_BLADES`, `BLADES_PER_COIL`, `VENT_FLOW`, `CONDENSER_RATE`.

**Internal (stay in Java):** save intervals (`SAVE_TICKS`, `SAVE_SECONDS`), `RETRY_TICKS`, `EVERY_TICKS`, `CHARGE_EVERY`, `PER_PAGE`, `SaveLines.*_FIELDS`, `BEAM_COLOR`, `MekanizedConfig.MAX`, `QioSettings.DRIVES`. They are timing or format details, not balance.

## Decision needed: where a block's numbers live

1. **In the CraftEngine block config (recommended).** The `machineengine:machine` and `machineengine:transport` block behaviors take an `options:` map, plus `storages: {<id>: {capacity: ...}}` overrides:
   ```yaml
   mekanized:basic_bin:
     behavior:
       type: machineengine:machine
       machine: mekanized:basic_bin
       keep_contents: true
       storages:
         items: { capacity: 4096 }
   mekanized:basic_universal_cable:
     behavior:
       type: machineengine:transport
       transport: mekanized:basic_universal_cable
       rate: 8000
   ```
   - Same rule as D32 for items: the number sits next to the thing, in the file server owners already edit, and `/ce reload` picks it up.
   - The machine type stays registered from Java; the block hands its options over when it binds to the type (blocks already bind lazily, D14). Java keeps each number as the default, used when the YAML leaves it out.
   - Cost: storage capacity is part of `MachineType` today, so the engine needs per-block storage overrides (sizing an instance from its block, not only its type). Changing a capacity on a loaded machine must keep its contents (clamp, never delete; overflow stays until drained, as a shrunk QIO drive does now).
2. **In the definitions (`definitions/mekanized/*.yml`).** A `machine-options:` section keyed by machine id, read by `DefinitionCompiler` and reloaded with the recipes.
   - Closer to the machine type, no per-block override in the engine.
   - But a second place for block data beside the CraftEngine config, and server owners must know which file holds what.

Multiblock physics goes in the multiblock type in either case: Mekanized's types become `multiblocks:` definitions (D33 already reads `capacities` there) with an `options:` map, or stay in Java with YAML options. This plan assumes `options:` on the type.

## Stages

Each stage is one or more commits that build and pass `./gradlew check`. No gameplay change. Old `config.yml` keys left in a file are kept and ignored, as for `gear.*`.

- **Stage 0: engine support.** `options:` (and `storages:` capacity overrides, if decision 1) on `machineengine:machine`, `rate:` on `machineengine:transport`, read through a typed `BlockOptions` the machine behavior gets at bind time (`ConfigKey`-like: default, range, clamped, reported when invalid). Headless tests with a fake block config.
- **Stage 1: tiered capacities and rates.** Bins, induction cells and providers, thermodynamic conductors, logistical sorter lanes, chargepad, universal cable, pressurized tube, mechanical pipe, logistical transporter. Removes `transport.*` from `MekanizedConfig`.
- **Stage 2: single machines.** Nutritional liquifier, electric pump, fuelwood heater, seismic vibrator, laser, energized smelter furnace recipes, radioactive waste barrel, digital miner, dimensional stabilizer. Removes `radiation.barrel-*`, `digital-miner.*`, `chunk-loading.*`.
- **Stage 3: QIO.** Drive items and kinds go in each drive's item config (D32, `mekanized:qio_drive` item behavior); array kinds and importer/exporter rate on their blocks. Removes `qio.*`.
- **Stage 4: gauge dropper capacity** in its item config (D32), with the other dropper data already in `ItemData`.
- **Stage 5: multiblock physics.** Dynamic tank and evaporation per-block amounts, boiler, fission, fusion, SPS, turbine numbers as options on their multiblock types. Removes `dynamic-tank.*`, `thermal-evaporation.*`.
- **Stage 6: clean-up.** `MekanizedConfig` keeps only radiation and teleporter; `MEKANIZED_GUIDE.md` lists where each number now lives; decision log entry.

## Rules for every stage
- Never build jars while the dev server runs. `compileJava` and `test` are fine.
- D25 still applies: only 1.20.6 Paper API outside `nms:v*`.
- Defaults stay Mekanism's; a test compares the shipped YAML with them.
