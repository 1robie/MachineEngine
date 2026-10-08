# Block numbers plan (2026-10-08)

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
- `MultiblockMachines.CELLS`, `PROVIDERS` (induction cells and providers, 4 tiers each). They are plain blocks the matrix counts, not machines: they go with the matrix's options (Stage 5).
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

**Structural (stay in Java):** `LogisticsMachines.LANES`, the process lanes of each factory tier (3, 5, 7, 9): they decide the machine type's slots and ports, so they cannot change per block.

**Internal (stay in Java):** save intervals (`SAVE_TICKS`, `SAVE_SECONDS`), `RETRY_TICKS`, `EVERY_TICKS`, `CHARGE_EVERY`, `PER_PAGE`, `SaveLines.*_FIELDS`, `BEAM_COLOR`, `MekanizedConfig.MAX`, `QioSettings.DRIVES`. They are timing or format details, not balance.

## Where a block's numbers live (decided: option 1, D34)

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

## Progress
- **2026-10-08: option 1 chosen (D34).**
- **2026-10-08, Stage 0 (done).** `api.config.BlockOptions` (`get(ConfigKey)`, `capacity(storage, fallback)`), `MachineContext#options()`, `MachineHost#options(position)` (the CraftEngine host asks the block's controller), `storages:` and `options:` on `machineengine:machine`, `rate:` on `machineengine:transport` (`TransportType#withRate`). A block's capacity replaces the type's before upgrades; a runtime `setStorageCapacity` still wins. `FakeWorld#setOptions` for tests (`BlockOptionsTest`). Options are kept per block, so blocks sharing a machine type may differ; still to check on the dev server: whether `/ce reload` reaches machines already loaded (a machine keeps the options it first read).
- **2026-10-08, Stage 1 (done; still to try in game).**
  - `rate:` on the 20 transport blocks (universal cable, pressurized tube, mechanical pipe, logistical transporter, thermodynamic conductor; 4 tiers each), `storages.items.capacity` on the 4 bins (the creative bin keeps its unlimited Java capacity), `options.charge-per-tick` on the chargepad (`UtilityMachines.CHARGE_PER_TICK`, read by `PaperUtility`).
  - The rates are now Java constants next to their transport types (`MekanizedRegistry.CABLE_RATES`, `ChemistryMachines`, `LogisticsMachines`); `MekanizedSettings.*Rates` and `MekanizedConfig`'s `transport` section are gone. Old `transport.*` keys left in a file are kept and ignored.
  - `BlockNumbersTest` checks that every shipped `rate` and `storages` capacity equals the Java default, and that a bin holds what its block says. `ShippedBlocks` reads the shipped block configs.
  - Found on the way: induction cells and providers are plain blocks counted by the matrix, so they move to Stage 5; factory lanes are structural and stay in Java.
- **2026-10-08, Stage 2 (done; still to try in game).**
  - Options on the nutritional liquifier (`ticks`, `energy-per-tick`), electric pump (`energy`, `ticks`, `range`, `heavy-water-per-block`), fuelwood heater (`heat-per-tick`), seismic vibrator (`energy-per-tick`), laser, laser amplifier and tractor beam (`energy-per-tick`, `range`, `energy-per-hardness`), digital miner (`energy-per-block`, `ticks-per-block`), dimensional stabilizer (`energy-per-chunk`); the waste barrel has `storages.waste.capacity` and `decay-per-second`.
  - The pump's and lasers' behavior arguments became options too, the arguments being their defaults (`ConfigKey`s made per factory call).
  - Removed from `config.yml`: `radiation.barrel-capacity`, `radiation.barrel-decay-per-second`, `chunk-loading.energy-per-chunk`, `digital-miner.energy-per-block`, `digital-miner.ticks-per-block`. Old keys are kept and ignored.
  - **Kept in `config.yml`, by choice:** `digital-miner.max-radius`, `digital-miner.scan-per-tick`, `chunk-loading.max-radius`. They are server-load limits, and the menus list the radii from them (one menu per machine kind).
  - **Kept in Java:** `Liquifier.PASTE_PER_FOOD` (the canteen reads the same ratio: a conversion, not a block number) and `PaperFurnaceRecipes.JOULES_PER_TICK` (a recipe number, shared by every smelter).
  - The stabilizer's and barrel's menus no longer print their energy per chunk and decay rate, which can now differ per block.
  - `BlockNumbersTest#everyShippedOptionIsItsDefault` checks every shipped option against its Java default, and that no block has options the test does not know.
  - Found on the way: many other machines take numbers as behavior arguments (energy, ticks, capacities passed to factories). A later stage could turn them into options the same way; nothing changes for them yet.
- **2026-10-08, Stage 3 (done; still to try in game).**
  - The drives' sizes are item config (D32): the item behavior `mekanized:qio_drive` (`items`, `kinds`) on the four drive items. Machine code asks `QioDrives.of(item)`; on Paper the lookup reads the item's CraftEngine behavior (`GearBehaviors.options(Key, Class)`), so any item with the behavior is a drive. Without a platform lookup (headless tests) `QioDrives.DEFAULTS` holds Mekanism's numbers.
  - The importer and exporter have the block option `items-per-second` (`AutomationMachines.IO_RATE`).
  - Removed from `config.yml`: `qio.drive-items`, `qio.drive-kinds`, `qio.importer-exporter-rate` (kept and ignored in old files).
  - **Kept in `config.yml`:** `qio.array-kinds`. It sets how many item slots the drive array's machine type has, fixed at registration, so it cannot change per block (structural, like the factory lanes).
  - `BlockNumbersTest#everyShippedDriveHoldsTheDefault` compares the shipped drive items with `QioDrives.DEFAULTS`.
- **2026-10-08, Stage 4 (done; still to try in game).** The gauge dropper's `mekanized:gauge_dropper` behavior takes `capacity` (16,000 mB, `gear.yml`). `GaugeDropper.take` and `lore` get the capacity from the caller; the dropper behavior and the `mekanized:gear_lore` processor read it from the item. `BlockNumbersTest#theShippedGaugeDropperHoldsTheDefault` checks the shipped value.
- **2026-10-08, Stage 5 (done; still to try in game).**
  - Engine: `MultiblockType` has `Sizes` (structure, anchor options) instead of a structure-only function; the old three-argument constructor still takes one. `MultiblockMember.Builder#port` also takes a `PortRate` (structure, port options). Anchor tasks read `anchor.options()`.
  - Every Box's anchor is its lowest-corner casing, so the options go on the casing blocks: `dynamic_tank` (`fluid-per-block`, `chemical-per-block`), `induction_casing` (`cell-capacity.<tier>`), `induction_port` (`provider-output.<tier>`), `thermal_evaporation_block` (`input-per-level-per-tick`, `input-tank-per-level`, `output-tank`), `boiler_casing` (heat per mB, boil per element, five capacities per block), `fission_reactor_casing` (heat per mB burned, coolant heat per mB, six capacities per assembly), `fusion_reactor_frame` (ignition energy, energy per mB, fuel tank, energy buffer), `sps_casing` (polonium per antimatter, energy per polonium, three tanks), `turbine_casing` (steam energy, full blades, blades per coil, vent flow, condenser rate, two capacities per block).
  - Beyond the plan's list: the structures' per-volume and fixed capacities (boiler, fission, fusion, SPS, turbine) became options too, like the dynamic tank's.
  - The fission reactor has its own `coolant-heat-per-mb` instead of sharing the boiler's constant.
  - Removed from `config.yml`: `dynamic-tank.*`, `thermal-evaporation.*`.
  - `BlockNumbersTest` checks every multiblock option against its Java default (nested tier maps flattened to the keys' paths); `MultiblockTest#aTankIsSizedByItsCasingsBlockConfig` forms a tank sized by its anchor's options.
- **2026-10-08, Stage 6 (done, but for the dev-server checks).**
  - `MekanizedConfig` holds only world-wide rules: radiation, the teleporter, the chunk-loading and miner limits, the QIO array size. The generated `config.yml` header tells owners that each block's and item's numbers are in its CraftEngine config. Unused helpers removed.
  - Headless already: `BlockNumbersTest` fails on an unknown `storages:` id or option name and checks every shipped value against its Java default.
  - D34 in ARCHITECTURE.md covers block options, transport rates and multiblocks; MEKANIZED_GUIDE.md lists every option and the ignored old keys.
  - **Still to check on a dev server** (not done: the owner's dev server was running from the main checkout, and a second would clash with it):
    1. a changed `rate:`, bin capacity and casing option reach a placed block after a restart;
    2. whether `/ce reload` reaches machines already loaded. A machine keeps the `BlockOptions` it first read (`MachineInstanceImpl#options`), from its block entity controller's behavior. If CraftEngine keeps old controllers across a reload, making it live means reading the behavior from the block state's current definition and dropping the cache. The guide says to restart until then.
  - **Candidate follow-up:** numbers still passed as Java behavior arguments in other machines (energy and ticks of processing machines, generators' output, tank capacities in machine types). They can become options the way the pump's and lasers' did in Stage 2.

## Stages

Each stage is one or more commits that build and pass `./gradlew check`. No gameplay change. Old `config.yml` keys left in a file are kept and ignored, as for `gear.*`.

- **Stage 0: engine support.** `options:` (and `storages:` capacity overrides, if decision 1) on `machineengine:machine`, `rate:` on `machineengine:transport`, read through a typed `BlockOptions` the machine behavior gets at bind time (`ConfigKey`-like: default, range, clamped, reported when invalid). Headless tests with a fake block config.
- **Stage 1: tiered capacities and rates.** Bins, thermodynamic conductors, chargepad, universal cable, pressurized tube, mechanical pipe, logistical transporter. Removes `transport.*` from `MekanizedConfig`.
- **Stage 2: single machines.** Nutritional liquifier, electric pump, fuelwood heater, seismic vibrator, lasers, radioactive waste barrel, digital miner, dimensional stabilizer. Removes `radiation.barrel-*` and the miner's and stabilizer's energy and ticks; their radius and scan limits stay world rules.
- **Stage 3: QIO.** Drive items and kinds go in each drive's item config (D32, `mekanized:qio_drive` item behavior); importer/exporter rate on their blocks. Array kinds stay (structural).
- **Stage 4: gauge dropper capacity** in its item config (D32), with the other dropper data already in `ItemData`.
- **Stage 5: multiblock physics.** Induction cells and providers, dynamic tank and evaporation per-block amounts, boiler, fission, fusion, SPS, turbine numbers as options on their multiblock types. Removes `dynamic-tank.*`, `thermal-evaporation.*`.
- **Stage 6: clean-up.** `MekanizedConfig` keeps only radiation and teleporter; `MEKANIZED_GUIDE.md` lists where each number now lives; decision log entry.

## Rules for every stage
- Never build jars while the dev server runs. `compileJava` and `test` are fine.
- D25 still applies: only 1.20.6 Paper API outside `nms:v*`.
- Defaults stay Mekanism's; a test compares the shipped YAML with them.
