# CraftEngine behaviors plan (draft, 2026-10-07)

Today almost every custom item works through a hand-written Bukkit listener. The listener reads the CraftEngine id from the item, compares it with a string, reads and writes PDC values, and rewrites the lore.

This plan moves that logic into CraftEngine's own extension points:
- item behaviors;
- item data;
- block behaviors;
- loot functions.

The per-item numbers (capacities, costs, ranges) move into the CraftEngine item YAML, as behavior options. CraftEngine then handles dispatch, ordering, cancellation and `/ce reload`. Listeners stay only where CraftEngine has no hook (see "What stays a listener").

## Progress
- **2026-10-07, Stage 0 (partial).** Dev server checks on Paper 26.3 and 1.20.6, headless:
  - `machineengine:*` and `mekanized:*` item types registered in `onLoad` resolve on the first load;
  - no unknown types and no unbound resources.

  From CraftEngine's source: writes on a server stack (from an inventory) change it in place, and a plain Bukkit stack is copied. `PaperItemResources` copies the result back, so both work.

  Still to check in game: interactions (hand-off, `useOnBlock` order, `SUCCESS_AND_CANCEL`) and client-bound lore.
- **2026-10-07, Stage 1 (done, except `KeptContents`, which moves to Stage 4 with the loot function).**
  - `api.item`: `MachineTool`, `ResourceItem`, `ItemCapacityModifier`, `ItemResources`.
  - `api.paper.PaperItemResources`: item data `machineengine.resources.<type>`, PDC migrations, change listeners.
  - The hand-off in `MachineBlockBehavior.useOnBlock`. `TransportBlockBehavior` needs none: it has no right-click action, so the item's behavior already runs.
  - `CraftEngineIntegration.unboundContent` also reports unknown resources in items.
- **2026-10-07, Stage 2 (energy and chemicals done).**
  - `machineengine:energy_item` and `machineengine:resource_item` are in place.
  - Mekanized's 16 energy and chemical items use them with the old numbers.
  - `mekanized:module_holder` gives the energy unit's capacity (`ItemCapacityModifier`).
  - Removed: `EnergyItems`, `PaperEnergyItems`, `gear.energy-capacity` / `gear.chemical-capacity`, `GearItems.CHEMICALS`.
  - Machines charge any item through `ItemResources`.
  - Still to do: `wrench`, `storage_reader`, `fill-from-machines` (Stage 3); modules and other Mekanized PDC data to `ItemData`; client-bound lore.
  - `resource_item` takes one resource key, not a tag, for now.

- **2026-10-07, Stage 3 (done; still to try in game).**
  - Every right-click of a Mekanized item is a CraftEngine item behavior now, written on the new `api.paper.PaperItemBehavior`: a Bukkit `Use` (player, live item, block, face, sneaking) and `useOnEntity`.
  - Mekanized types:
    - `configurator`, `tier_installer` (`tier`), `turbine_blade`;
    - `configuration_card`, `dictionary`, `gauge_dropper`, `crafting_formula`;
    - `module` (`module`) and `module_holder` (taking modules out at a station);
    - `flamethrower`, `canteen`, `meka_tool` (teleport, farming, shearing);
    - `radiation_reader` (`mode`);
    - `portable_teleporter`, `portable_qio`, `cardboard_box`, `cardboard_box_storage`, `seismic_reader`.
  - Engine types: `machineengine:storage_reader` (the network reader), and `fill-from-machines` on `resource_item` (gear filled from machines). Their messages are translated (`machineengine.item.filled`, `machineengine.reader.*`).
  - **Hand-off changed:** a machine offers the click to a `MachineTool` first and opens its menu when the tool passes. A plain "pass everything" would have stopped the menu from opening for a Meka-Tool that has nothing to do there.
  - Gone: `PaperTools`, `GearTools.onUse` / `onShear`, `GearTools.fill`, `PaperRadiation.onUse`, `PaperAutomation.onPortable`.
  - Block-side branches stay listeners until Stage 4: multiblock menus and taking a blade back in `PaperConfigurator`, and the dashboard block in `PaperAutomation.onUse`.
  - **Small changes:**
    - On a vanilla block that opens (chest, furnace), CraftEngine lets vanilla win unless the player sneaks. Using the configurator on a container to set the transports beside it, or the dictionary on such a block, now needs sneaking.
    - The fill message is translated and no longer shows "mB".

- **2026-10-07, Stage 4a (done; still to try in game).**
  - `api.paper.MachineInteractions`: what a machine type's block does on Paper, registered per type by its addon. It has `use` (right-click, after any `MachineTool` and before the menu), `attack` (left-click, through CraftEngine's `CustomBlockInteractEvent`, which can cancel the break) and `steppedOn` (the block behavior's `stepOn`). Machine behaviors stay free of Bukkit.
  - Mekanized uses it for:
    - bins (insert and take);
    - personal storage (owner);
    - multiblock members (structure menu);
    - the turbine rotor (taking blades back);
    - the QIO dashboard block;
    - teleporters (`steppedOn`, which replaces a `PlayerMoveEvent` listener on every move).
  - Personal storage no longer needs explosion listeners: its block resistance is 3,600,000.
  - Keep-contents is now the loot function `machineengine:keep_contents`, used by the template `machineengine:loot_table/keep_contents` (engine pack `templates.yml`, rewritten at every start); all 145 Mekanized blocks with `keep_contents: true` use it. The state is CraftEngine item data `machineengine.contents`, and the old PDC key is still read. `BlockDropItemEvent` is no longer used.
  - Headless checks pass: `check-drop` (100/100 through the fallback) and `check-hopper` (10/10).
  - **Stays:**
    - the chargepad's per-player loop, because a machine cannot see the players on it;
    - a non-owner breaking personal storage (`CustomBlockBreakEvent`), because block behaviors cannot cancel a break.
  - **Small change:** a teleporter now only fires when the player stands directly on it, no longer from two blocks above.

**Rules for every stage:**
- Each stage is one or more commits that build and pass `./gradlew test`.
- No gameplay change unless the stage says so.
- Every tool keeps working between stages: a listener is deleted only in the commit that moves its last item.
- Never build jars while the dev server runs. `compileJava` and `test` are fine.
- D25 still applies: only 1.20.6 Paper API outside `nms:v*`. CraftEngine hooks that hand over raw NMS objects (`Object[] args`) are wrapped once in `common` and never used from Mekanized.
- **Behaviors hold configuration only, never state.** `/ce reload` rebuilds them. State lives in the item data (items) or in the block entity (blocks, D2).

## Decisions (owner, 2026-10-07)
1. **Generic behaviors live in the main plugin; content behaviors live in the addon.**
   - **MachineEngine** registers the behaviors that only need engine concepts: energy, resources, machines, storages, facing. Any addon can use them from YAML:
     - `machineengine:energy_item`;
     - `machineengine:resource_item`;
     - `machineengine:wrench`;
     - `machineengine:storage_reader`.

     See "Which behavior goes where".
   - **Mekanized** registers its own types for Mekanism gameplay in its `onLoad` (`ItemBehaviors.register`), such as `mekanized:configurator`, `mekanized:module_holder` and `mekanized:jetpack`. It uses the engine behaviors for energy and chemicals instead of writing its own.
   - The engine also:
     - defines small `api` interfaces that behaviors implement (`MachineTool`, `EnergyItem`, `ResourceItem`, `ItemCapacityModifier`), so engine code and addon code find each other through `getFirst` without knowing the class;
     - provides the machine tool hand-off.
2. **Per-item numbers live in the CraftEngine item config**, as options of the item's behavior.
   - The id lists in `MekanizedConfig.java:106-120` (`ENERGY_CAPACITY`, `CHEMICAL_CAPACITY`) and the `CHEMICALS` map in `GearItems.java:41-43` are removed.
   - `config.yml` keeps only global settings (D30), such as radiation and teleporter costs that do not belong to one item.
3. **Item state moves to CraftEngine item data** (`Item.setTag` / `getTagAsJava` / `removeTag`, rooted in `minecraft:custom_data`), away from Bukkit PDC.
   - Items that already exist in worlds are converted when they are first read (Stage 2).
4. **Simple costs: delegated, chosen: Java behaviors with the numbers as YAML options.**
   - Examples: `energy-per-hit`, `energy-per-shot`, `energy-per-block`.
   - This gives the same tuning in YAML as decision 2, with one place for each item.
   - Custom CraftEngine `events:` functions (`use_energy`, `has_energy`) are not built now. They can come later, without changing the item data.

## Which behavior goes where
Rule: **a behavior goes in MachineEngine when it needs only engine concepts and another addon could use it unchanged.** It goes in Mekanized when it encodes Mekanism gameplay.

| Behavior | Module | Options | Replaces |
|---|---|---|---|
| `machineengine:energy_item` | engine (`common`), implements `EnergyItem` | `max-energy`, `max-input`, `max-output`, `charge-from-machines` (bool) | `PaperEnergyItems`, `EnergyItems.Access`, `ENERGY_CAPACITY`, energy part of `GearItems` |
| `machineengine:resource_item` | engine, implements `ResourceItem`, MachineTool | `resource` (type key or `#tag`), `capacity`, `fill-from-machines` (bool), `transfer-per-click` | `CHEMICAL_CAPACITY`, the `CHEMICALS` map, `GearTools.fill` |
| `machineengine:wrench` | engine, MachineTool | `rotate` (bool), `dismantle` (sneak: break a machine into its item with keep-contents) | the "sneak rotates" branch of the configurator |
| `machineengine:storage_reader` | engine, MachineTool | `energy-per-use` | `network_reader` (prints the machine's storages) |
| `machineengine:keep_contents` | engine, **loot function** | — | `KeptContents` `BlockDropItemEvent` |
| `mekanized:configurator` | Mekanized, MachineTool | `energy-per-use` | side config and transport side cycling (uses `machineengine:wrench` for rotation) |
| `mekanized:module_holder`, `mekanized:module` | Mekanized, the holder implements `ItemCapacityModifier` | `accepts`, `module`, `max-installed`, `enchantment` | `GearModules`, `GearRules` |
| `mekanized:mining_tool`, `mekanized:jetpack`, `mekanized:mekasuit_piece`, `mekanized:flamethrower`, `mekanized:canteen`, … | Mekanized | see Stage 3 | the `GearTools` / `SuitTicker` branches |

The engine behaviors are found by interface, never by class or id:
- the chargepad and machine charge slots charge every `EnergyItem`;
- a Mekanized module that adds capacity is an `ItemCapacityModifier` that `machineengine:energy_item` asks through `getFirst`;
- `resource_item` resolves `resource` lazily (D14): CE parses the YAML before the resource registries freeze, and again on `/ce reload`, so the key is stored and bound after the freeze. Unknown keys are reported like `unboundBlocks`.

## Where things stand

| Area | Today | Main problems |
|---|---|---|
| CE item behaviors | none registered; YAML uses only `block_item` (200×) | 59 gear and tool items have no `behavior:` |
| Per-item numbers | id lists in `MekanizedConfig`, the `CHEMICALS` map in `GearItems` | an item's numbers are far from its definition; other addons cannot add items |
| CE block behaviors | `machineengine:machine` (145), `machineengine:transport` (20) | 35 blocks have no behavior; bins, pads, multiblock parts and personal storage are handled by listeners |
| Item listeners | `PaperConfigurator`, `PaperTools`, `GearTools`, `SuitTicker`, `PaperRadiation`, `PaperUtility`, `PaperAutomation` | ~20 `PlayerInteractEvent` branches on the item id; `LOWEST` priorities used to get ahead of the machine menu |
| Item state | PDC keys `energy`, `gear_chemical`, `modules`, `configuration`, `dropper_*`, `formula`, `frequency`, `box_*`, and the engine's `machineengine:contents` | read in 4+ files; lore is rewritten on the server, in English only, against D23 |
| Machine as item | `KeptContents` on `BlockDropItemEvent` (LOWEST) | runs outside CE's loot pipeline |

## CraftEngine facts this plan relies on
Checked in `.craftengine/source` (HEAD `5e6c323`). The entries marked **verify** are checked on the dev server in Stage 0.

1. **Registration.**
   - The registration calls are:
     - `ItemBehaviors.register(Key, ItemBehaviorFactory)`, where the factory is `create(Pack, Path, Key itemId, ConfigSection)`;
     - `BlockBehaviors.register`;
     - `LootFunctions.register`.
   - Registries never freeze.
   - CE creates its own types in its `onLoad` and parses the YAML in its `onEnable`, so Mekanized registers in its `onLoad`. **Verify** that Paper calls CE's `onLoad` first: it should, because Mekanized depends on CE.
   - A `type:` without a namespace resolves to `craftengine:`, so ours are written `mekanized:...`.
2. **Item behavior hooks.**
   - `useOnBlock(UseOnContext)`, `use(World, Player, Hand)`, `useOnEntity(InteractEntityContext)`, `onBreakBlock(World, Player, BlockPos)`.
   - There is **no inventory or held-item tick**.
   - `onBreakBlock` cannot cancel the break or change the drops.
3. **Several behaviors on one item.**
   - `behaviors:` takes a list, combined in `CompositeItemBehavior`.
   - `behavior.getFirst(Class)` finds the first behavior implementing an interface. This is how engine code finds `EnergyItem` or `MachineTool` without knowing Mekanized.
4. **Dispatch order on a custom block** (`ItemEventListener.java:174-405`):
   - `CustomBlockInteractEvent`, then the block `events:`;
   - then the **block** behavior `useOnBlock`, whose default is `TRY_EMPTY_HAND`, which leads to `useWithoutItem` (main hand);
   - and only when that returns `PASS`, the **item** behavior `useOnBlock`.

   `MachineBlockBehavior` opens the menu in `useWithoutItem` whatever the player holds, so a tool's behavior is never reached on a machine. That is why `PaperConfigurator` and `PaperTools` listen at `LOWEST` today.
5. **Item data.**
   - `BukkitAdaptor.adapt(ItemStack)` → `Item`, which offers:
     - `getTagAsJava(path…)`;
     - `setTag(value, path…)`;
     - `removeTag(path…)`;
     - `setCustomData` / `getCustomData` with registered serializers.
   - Paths are rooted in `minecraft:custom_data`. The old PDC values sit at `PublicBukkitValues."mekanized:energy"`, so the migration can read them with the same API.
   - **Verify** that changes on a wrapped `CraftItemStack` from a player inventory write through, or whether the stack must be set back.
6. **Lore.**
   - `client-bound-data` lore is rendered per packet; `set-arguments` stores `craftengine:arguments`.
   - **Verify** that client-bound lore can show values we write at runtime, with `<lang:...>`.
   - If it can, items store numbers only and each player reads the lore in their own language (D23). If not, one shared server-side `refresh` stays.
7. **Equipment.** `settings.equipment-potion-effects` and `settings.attribute-modifiers` are evaluated per slot, with `condition(s)`, every `update-interval`. This is the only periodic item hook CE has.
8. **Blocks.**
   - Block behaviors get `stepOn`, `entityInside`, `attack` (left click), `playerWillDestroy`, `spawnAfterBreak` and `preExplosionHit`, most with NMS arguments.
   - There is no `getDrops`; drops come from `loot:`.

## Examples

### Item YAML: numbers next to the item
```yaml
# gear.yml
mekanized:jetpack:
  material: iron_chestplate
  data: {item_name: "<lang:item.mekanized.jetpack>", components: {minecraft:max_stack_size: 1}}
  settings:
    tags: [mekanized:chemical_tanks]
  behaviors:
    - type: machineengine:resource_item   # engine: holds hydrogen, fills from a machine (MachineTool)
      resource: mekanized:hydrogen
      capacity: 24000
      fill-from-machines: true
    - type: mekanized:jetpack             # Mekanized: read by SuitTicker through getFirst(JetpackBehavior.class)
      hydrogen-per-tick: 2
      max-speed: 0.6

mekanized:meka_tool:
  material: netherite_pickaxe
  behaviors:
    - type: machineengine:energy_item     # engine
      max-energy: 16000000
      max-input: 100000
    - type: mekanized:mining_tool
      energy-per-block: 1000
      energy-per-hit: 2000
      charged-speed: 16
    - type: mekanized:module_holder       # the modules this item accepts; energy_unit raises max-energy (ItemCapacityModifier)
      accepts: [excavation_escalation_unit, vein_mining_unit, blasting_unit, teleportation_unit, farming_unit, shearing_unit, attack_amplification_unit, energy_unit]

mekanized:configurator:
  material: stick
  settings:
    tags: [c:tools/wrench, mekanized:configurators]
  behaviors:
    - type: machineengine:energy_item
      max-energy: 60000
    - type: machineengine:wrench          # engine: sneak-click rotates the machine
      rotate: true
    - type: mekanized:configurator        # Mekanized, MachineTool: side config instead of the machine menu
      energy-per-use: 10

mekanized:network_reader:
  material: paper
  behaviors:
    - type: machineengine:energy_item
      max-energy: 60000
    - type: machineengine:storage_reader  # engine: prints the clicked machine's storages
      energy-per-use: 400

# Another addon gets a chargeable item without any Java:
myaddon:battery:
  material: paper
  behavior: {type: machineengine:energy_item, max-energy: 500000, max-output: 1000}

mekanized:module_vein_mining_unit:
  material: paper
  behavior:
    type: mekanized:module
    module: vein_mining_unit
    max-installed: 4
```

Today the same numbers are spread over `MekanizedConfig` (capacities), `GearItems` (which chemical) and `GearTools` (costs).

### Registering: engine and addon
```java
// common: CraftEngineIntegration.register(), called from MachineEnginePlugin.onLoad (next to the block types)
ItemBehaviors.register(Key.of("machineengine", "energy_item"), EnergyItemBehavior.FACTORY);
ItemBehaviors.register(Key.of("machineengine", "resource_item"), ResourceItemBehavior.FACTORY);
ItemBehaviors.register(Key.of("machineengine", "wrench"), WrenchBehavior.FACTORY);
ItemBehaviors.register(Key.of("machineengine", "storage_reader"), StorageReaderBehavior.FACTORY);
LootFunctions.register(Key.of("machineengine", "keep_contents"), KeepContentsFunction.FACTORY);

// mekanized: MekanizedPlugin.onLoad, before MachineEngine.register(...)
ItemBehaviors.register(Key.of("mekanized", "configurator"), ConfiguratorBehavior.FACTORY);
ItemBehaviors.register(Key.of("mekanized", "module_holder"), ModuleHolderBehavior.FACTORY);
ItemBehaviors.register(Key.of("mekanized", "jetpack"), JetpackBehavior.FACTORY);
// … one line per Mekanized type
```

### An engine behavior: options from YAML, state in item data
```java
// common/craftengine/item/EnergyItemBehavior.java
public final class EnergyItemBehavior extends ItemBehavior implements EnergyItem {
    public static final ItemBehaviorFactory<EnergyItemBehavior> FACTORY = (pack, path, id, section) ->
            new EnergyItemBehavior(section.getLong("max-energy", 0),
                    section.getLong("max-input", Long.MAX_VALUE), section.getLong("max-output", Long.MAX_VALUE));

    private final long maxEnergy, maxInput, maxOutput;

    private EnergyItemBehavior(long maxEnergy, long maxInput, long maxOutput) {
        // CE reports its own config errors with KnownResourceException; use the same one so the item and file are named
        if (maxEnergy <= 0) throw new IllegalArgumentException("max-energy must be > 0");
        this.maxEnergy = maxEnergy;
        this.maxInput = maxInput;
        this.maxOutput = maxOutput;
    }

    @Override public long capacity(Item item) {
        // addon hook: Mekanized's module_holder raises it when an energy unit is installed
        ItemCapacityModifier modifier = item.getBehavior().map(b -> b.getFirst(ItemCapacityModifier.class)).orElse(null);
        return modifier == null ? this.maxEnergy : modifier.modify(item, Energy.KEY, this.maxEnergy);
    }
    @Override public long stored(Item item) { return ItemResources.get(item, Energy.KEY); }
    @Override public void setStored(Item item, long joules) {
        ItemResources.set(item, Energy.KEY, Math.clamp(joules, 0, capacity(item)));
    }
}
```

`ItemResources` (engine) is the one place that knows where amounts are stored; addon state such as modules uses Mekanized's own `ItemData`:
```java
public final class ItemResources {
    // custom_data: {machineengine:{resources:{"machineengine:energy":5000L,"mekanized:hydrogen":1200L}}}
    public static long get(Item item, Key type) {
        Migrations.run(item);   // old PDC keys registered by addons, see below
        return item.getTagAsJava("machineengine", "resources", type.asString()) instanceof Number n ? n.longValue() : 0L;
    }
    public static void set(Item item, Key type, long amount) {
        if (amount == 0) item.removeTag("machineengine", "resources", type.asString());
        else item.setTag(amount, "machineengine", "resources", type.asString());
    }
}

// Mekanized declares how its old PDC keys map to the new data, once, in its addon callback:
ItemMigrations.pdcLong("mekanized:energy", Energy.KEY);
ItemMigrations.pdcLong("mekanized:gear_chemical", item -> /* the item's resource_item type */);
```

Item data before and after:
```
before: minecraft:custom_data={PublicBukkitValues:{"mekanized:energy":5000L,"mekanized:gear_chemical":1200L,"mekanized:modules":"vein_mining_unit,blasting_unit"}}
after:  minecraft:custom_data={machineengine:{resources:{"machineengine:energy":5000L,"mekanized:hydrogen":1200L}},
                               mekanized:{modules:["vein_mining_unit","blasting_unit"]}}
```

### A tool on a machine: the machine tool hand-off
```java
// api: a marker the engine understands
public interface MachineTool {}

// Mekanized
public final class ConfiguratorBehavior extends ItemBehavior implements MachineTool {
    @Override
    public InteractionResult useOnBlock(UseOnContext context) {
        Player player = (Player) context.getPlayer().platformPlayer();
        Block block = PaperWorld.block(context);
        LoadedMachine machine = PaperWorld.machine(block);
        if (machine == null) return InteractionResult.PASS;
        if (context.isSecondaryUseActive()) Configurator.rotate(machine);
        else Configurator.openSideMenu(player, machine, context.getClickedFace());
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
}

// common: MachineBlockBehavior (and TransportBlockBehavior)
@Override
public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
    boolean tool = context.getItem().getBehavior()
            .map(b -> b.getFirst(MachineTool.class) != null).orElse(false);
    return tool ? InteractionResult.PASS : InteractionResult.TRY_EMPTY_HAND;  // PASS → CE calls the item's useOnBlock
}
```
This replaces the `LOWEST` listeners of `PaperConfigurator` and `PaperTools`.

### Engine code that finds item energy (chargepad, charge slots)
```java
Item item = BukkitAdaptor.adapt(stack);
EnergyItem energy = item.getBehavior().map(b -> b.getFirst(EnergyItem.class)).orElse(null);
if (energy != null) energy.setStored(item, energy.stored(item) + offered);
```
Any addon's item with an `EnergyItem` behavior can now be charged, not only Mekanized's.

## Stage 0: spike on the dev server (no commit to main)
- **One `EnergyItemBehavior` and one `MachineTool` behavior on the dev server:**
  - right-click air, a vanilla block, a machine (with the hand-off), and an entity; break a block;
  - record what `SUCCESS_AND_CANCEL` cancels and whether `onBreakBlock` runs before or after the drops.
- **Registration from Mekanized's `onLoad`:** the YAML resolves `mekanized:*` types on the first load and after `/ce reload`.
- **Item data:** writes on a wrapped inventory stack are kept (fact 5); the PDC → item data migration works on an old item.
- **Client-bound lore** with runtime values and `<lang:>` (fact 6).
- **Folia:** which thread `use` and `useOnBlock` run on. It should be the player's region thread.

Write the results into `ARCHITECTURE.md` §39 ("CraftEngine facts confirmed").

## Stage 1: engine interfaces, item data and hand-off (no gameplay change)
1. **`api` interfaces** that CE behaviors implement:
   - `MachineTool` (marker, with an optional `boolean actsOn(LoadedMachine)`);
   - `EnergyItem` (`capacity`, `stored`, `setStored`);
   - `ResourceItem` (the same, for one resource type);
   - `ItemCapacityModifier` (`modify(item, type, base)`).

   They take CE `Item`, which is allowed by D26.
2. **`api` `ItemResources` and `ItemMigrations`:**
   - amounts live at `custom_data.machineengine.resources.<type>`;
   - addons declare how their old PDC keys map to the new data;
   - the migration runs on the first read.
3. **Machine tool hand-off** in `MachineBlockBehavior.useOnBlock` and `TransportBlockBehavior.useOnBlock` (example above).
4. **The engine's own item data:**
   - `KeptContents` writes `machineengine:contents` through CE item data instead of PDC, with the same migration on read;
   - `MachineBlockEntityController.loadCustomDataFromItem` reads the new path.
5. **Tests:**
   - the hand-off decision as a pure function, without a server;
   - the `ItemResources` paths and the migration;
   - the `KeptContents` round trip.
6. **Doc:** **D32** in `ARCHITECTURE.md` (decisions 1–4 and "Which behavior goes where"), §22 updated (the `MachineInteraction` / `useOnBlock` sketch becomes the real hand-off), and §20.4 updated (item data path).

## Stage 2: engine behaviors, then Mekanized adopts them (no gameplay change, config change by decision 2)
1. **Engine behaviors** in `common/craftengine/item/`, registered in `CraftEngineIntegration` next to the block types:
   - `machineengine:energy_item`;
   - `machineengine:resource_item`, including filling from a machine, as a MachineTool;
   - `machineengine:wrench`;
   - `machineengine:storage_reader`.

   `resource_item` binds its resource key lazily, and `unboundBlocks` grows into `unboundContent`, which also reports unknown resources in items.
2. **Charge slots and the chargepad** find `EnergyItem` through `getFirst`. `EnergyItems.Access` and `PaperEnergyItems` are removed.
3. **Mekanized YAML:**
   - every item in `ENERGY_CAPACITY` / `CHEMICAL_CAPACITY` today gets `machineengine:energy_item` or `machineengine:resource_item` with the same numbers, so there is no gameplay change;
   - `network_reader` gets `machineengine:storage_reader`;
   - the configurator gets `machineengine:wrench` for rotation.

   Then the id lists, the `CHEMICALS` map, `ItemLongs` and their `config.yml` keys are removed. A removed key left in an existing `config.yml` is kept and ignored, as D30 does.
4. **Mekanized migrations:** `ItemMigrations` entries for `mekanized:energy` and `mekanized:gear_chemical`.
5. **Mekanized `ItemData`** for Mekanism-only state: modules, configuration card, dropper, formula, frequency, cardboard box. Each moves from PDC to `custom_data.mekanized.*`, with its own migration.
6. **`mekanized:module_holder` and `mekanized:module`:**
   - modules are stored as a list in item data;
   - accepted modules and the per-module maximum come from YAML instead of `GearRules`;
   - `module_holder` implements `ItemCapacityModifier` for the energy unit.
7. **Lore:**
   - The engine owns the energy and resource lines (`<lang:machineengine.lore.energy>`), so every addon's items show them the same way. Mekanized adds only the module lines.
   - If Stage 0 shows client-bound lore works, both go there. Otherwise there is one server-side `refresh` in the engine, which addons extend.
8. **Tests:**
   - engine behavior factories: options, defaults, errors on a missing `max-energy`, unknown resources reported;
   - module capacity scaling through `ItemCapacityModifier`.

## Stage 3: Mekanized tools to item behaviors (no gameplay change)
Each line below is one behavior type plus its `behaviors:` entry in YAML. The listener branch is deleted in the same commit.

| Item(s) | From | Behavior type, hook | Options (examples) |
|---|---|---|---|
| `configurator` | `PaperConfigurator` | `mekanized:configurator`, `useOnBlock`, MachineTool (rotation: engine `machineengine:wrench`) | `energy-per-use` |
| `*_tier_installer` | `PaperConfigurator` | `mekanized:tier_installer`, `useOnBlock`, MachineTool | `from-tier`, `to-tier` |
| `turbine_blade` | `PaperConfigurator` | `mekanized:turbine_blade`, `useOnBlock` | — |
| `configuration_card` | `PaperTools` | `mekanized:configuration_card`, `useOnBlock`, MachineTool | — |
| `gauge_dropper` | `PaperTools` | `mekanized:gauge_dropper`, `useOnBlock` + `use`, MachineTool | `capacity` |
| `crafting_formula` | `PaperTools` | `mekanized:crafting_formula`, `useOnBlock`, MachineTool | — |
| `dictionary` | `PaperTools` | `mekanized:dictionary`, `useOnBlock` | — |
| `network_reader` | `GearTools` | **engine** `machineengine:storage_reader`, done in Stage 2 | `energy-per-use` |
| `geiger_counter`, `dosimeter` | `PaperRadiation` | `mekanized:radiation_reader`, `use` | `mode: environment\|dose` |
| `seismic_reader` | `PaperAutomation` | `mekanized:seismic_reader`, `useOnBlock`, MachineTool | `energy-per-use` |
| `cardboard_box`, `cardboard_box_storage` | `PaperAutomation` | `mekanized:cardboard_box`, `useOnBlock` | `allow-spawners` |
| `portable_teleporter`, `portable_qio_dashboard` | `PaperAutomation` | `mekanized:portable_teleporter`, `mekanized:portable_qio`, `use` | `energy-per-block` |
| `canteen` | `GearTools` | `mekanized:canteen`, `use` | `paste-per-hunger` |
| `flamethrower` | `GearTools` | `mekanized:flamethrower`, `use` | `hydrogen-per-use`, `range` |
| chemical gear fill | `GearTools.fill` | **engine** `machineengine:resource_item` (`fill-from-machines`), done in Stage 2 | `transfer-per-click` |
| `module_*` install | `GearTools` → `GearModules.install` | `mekanized:module`, `use` (sneaking) | `module`, `max-installed` |
| gear on `modification_station` | `GearTools` → `GearModules.uninstall` | part of `mekanized:module_holder`, `useOnBlock`, MachineTool | — |
| `meka_tool` teleport / farming / shearing | `GearTools` | `mekanized:mining_tool`, `use` / `useOnBlock` / `useOnEntity` | `teleport-range`, `energy-per-teleport-block` |

After this stage, these are gone:
- `PaperConfigurator` and `PaperTools`;
- the `PlayerInteractEvent` and `PlayerInteractEntityEvent` handlers of `GearTools`, `PaperRadiation` and `PaperAutomation`.

## Stage 4: block behaviors for blocks handled by listeners (no gameplay change)
| Block(s) | From | Target |
|---|---|---|
| bins | `PaperUtility` interact (right and left click) | `MachineBlockBehavior` gets an optional `MachineInteraction` SPI for `useOnBlock` and `attack` (left click), implemented by the bin machine type. `attack` needs the NMS hook, wrapped in `common`. |
| personal chest and barrel | `PaperUtility` interact, `CustomBlockBreakEvent`, explode events | Ownership check on interact through the same SPI. Explosions: `resistance: 3600000` in block settings, which drops both explode listeners. A non-owner breaking the block stays a `CustomBlockBreakEvent` listener, because block behaviors cannot cancel a break. |
| teleporter frame / pad | `PaperAutomation` `PlayerMoveEvent` | `mekanized:teleporter_pad` block behavior on `stepOn` (or `entityInside`). This removes a listener that runs on every player move. |
| chargepad | `PaperUtility` join loop | The machine's own tick charges `EnergyItem`s of players standing on it. The per-player loop goes away. |
| multiblock members (8 blocks without behavior), turbine rotor | `PaperConfigurator` | `mekanized:multiblock_part` block behavior: `useWithoutItem` opens the structure menu; port mode cycling goes through the configurator (MachineTool); the rotor takes blades back. |
| ores (10) | — | Check `drop_experience_block` against vanilla ore XP. No listener is involved. |
| machines that keep contents | `KeptContents` `BlockDropItemEvent` | CE loot function `machineengine:keep_contents` in the self loot table, writing the CE item data from Stage 1. |

## Stage 5: gear in combat and mining (small gameplay changes possible, listed)
| Feature | From | Target |
|---|---|---|
| electric bow: no arrow, costs energy | `EntityShootBowEvent` | `mekanized:electric_bow` (`energy-per-shot`). CE has no shoot hook on behaviors, so the listener stays but reads the behavior instead of the id: `getFirst(ElectricBowBehavior.class)`. |
| attack bonus | `EntityDamageByEntityEvent` | The same pattern: the listener asks `getFirst(MiningToolBehavior.class)` for `energy-per-hit` and `bonus-damage`. |
| energy per mined block | `BlockBreakEvent` | `MiningToolBehavior.onBreakBlock`, which charges `energy-per-block`. It cannot cancel the break (fact 2), so check that an empty tool is already useless through its tool component; otherwise keep a small guard. |
| tool speed by charge | `GearItems.refresh` | Java, called when `EnergyItem.setStored` changes the charge; `charged-speed` from YAML. |
| module enchantments, glider | `GearModules` | Java, through `ItemData`. Which enchantment a module gives moves to the module's YAML (`enchantment: silk_touch`). |
| vein mining, blasting 3x3 | `BlockBreakEvent` | **Stays a listener**, because each extra block must fire its own break event for protection plugins. Limits come from the behavior (`vein-limit`, `vein-limit-with-unit`). |

For the listeners that stay, the rule is: **they find the item's behavior and read its options.** They never compare item ids.

## Stage 6: worn gear (partial; CE has no item tick)
| Feature | Target |
|---|---|
| vision enhancement, inhalation purification | `settings.equipment-potion-effects`, with a custom CE condition `mekanized:has_module` (registered with `CommonConditions.register`, small) |
| motorized servo (step height), hydrostatic repulsor | `settings.attribute-modifiers` where the attribute exists; 1.20.6 lacks `step_height`, so there it stays Java behind `Nms` |
| hazmat and MekaSuit radiation shielding | `mekanized:radiation_shielding` behavior (`protection: 0.25`) read by `PaperRadiation` through `getFirst` instead of id lists |
| jetpack, magnet, scuba, gravitational flight, solar and geothermal charging, module energy drain | **stay in `SuitTicker`**, but every number comes from the worn item's behavior (`mekanized:jetpack`, `mekanized:mekasuit_piece`) instead of id branches |
| fall absorption | **stays** `EntityDamageEvent`; reads `mekanized:fall_absorber` (`energy-per-damage`) |

## What stays a listener (by design)
- vein mining and blasting;
- bow and attack energy;
- the `SuitTicker` core;
- fall absorption;
- radiation join and death;
- the QIO dashboard chest GUI (`InventoryClickEvent`, `InventoryDragEvent`);
- a non-owner breaking personal storage;
- the engine's placeholder protection;
- the per-chunk machine limit (`CustomBlockAttemptPlaceEvent`);
- the `CraftEngineReloadEvent` handlers.

Each of these gets a one-line comment naming the missing CE hook. All of them read behaviors, never item ids.

## Stage 7: docs
- **`ARCHITECTURE.md`:**
  - D32 (the four decisions above);
  - §22 lists the CE types registered by the engine and by Mekanized;
  - §39 gets the facts from Stage 0.
- **`MEKANIZED_GUIDE.md`:** a table of every `mekanized:*` item behavior and its options, telling owners where to change a capacity or cost.
- **`README.md`:** the YAML reference of the engine behaviors (`machineengine:energy_item`, `resource_item`, `wrench`, `storage_reader`, the `keep_contents` loot function) and how an addon writes its own `EnergyItem` or `MachineTool` behavior.

## Order and size
| Stage | Depends on | Size |
|---|---|---|
| 0 | — | spike |
| 1 | 0 | small to medium (engine, ~6 files) |
| 2 | 1 | medium: 4 engine behaviors, then every energy and chemical item in YAML |
| 3 | 2 | large but mechanical; one commit per listener file |
| 4 | 1 | medium |
| 5, 6 | 2 | small to medium |
| 7 | with each stage | — |
