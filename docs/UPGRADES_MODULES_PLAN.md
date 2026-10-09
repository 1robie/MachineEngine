# Upgrades and modules plan (2026-10-09)

Two ways of adding things to other things work differently today, and neither is easy to use:

- **Machine upgrades** go in and out through one menu button.
- **Gear modules** (MekaSuit, Meka-Tool) go in with a sneak-right-click and come out at the modification station.

This plan gives upgrades their own menu, and turns the modification station into a working station with a menu, as in Mekanism. Decision: **D40** (2026-10-09, the proposals below accepted as written).

## What we have

### Upgrades (engine, `api.upgrade`, `hooks:zmenu`)

- `UpgradeType`: a key, the modifiers of one unit, and `maxStack`. `MachineType.upgradeSlots(n, predicate)`: how many different types a machine holds, and which ones it takes. `MachineInstance.installUpgrade/removeUpgrade` check the registry, the predicate, the slot count and `maxStack`, then wake the machine (`UPGRADE_CHANGED`) and post `MachineEvents.UPGRADE_CHANGE`. Upgrades are saved, and kept in the item when the machine is broken (keep-contents).
- **The only way in or out is the `machineengine_upgrades` zMenu button** (`UpgradeButton`, Mekanized's upgrade tab at slot 8):
  - click with an item on the cursor: when the **item id equals an upgrade type key**, install as many as fit;
  - click with an empty hand: take back **one unit of the last installed type**, to the inventory.
- What's wrong with it:
  - you can't choose which upgrade comes out, and you can only take them out one at a time;
  - you can't see which upgrades the machine takes or how many fit: the lore is one text line (`%zmenu_machineengine_upgrades%`);
  - a refused install says nothing (`installUpgrade` returns an error and the click does nothing);
  - shift-clicking an upgrade from your inventory does nothing;
  - the logic sits in the zMenu hook and uses `MachineInstanceImpl` (internal), so no other menu or addon can reuse it;
  - "item id = upgrade key" is a naming rule, not an item behavior. D32 says item logic is a CraftEngine item behavior.

### Modules (Mekanized, `paper/gear`)

- `mekanized:module` item behavior: module id, energy, range, `capacity-factor`, enchantment, glider, and `fits` (the items it goes into). `mekanized:module_holder`: gear that takes modules.
- Installed modules are a **set of names** in the item data (`ItemData.Field.MODULES`, comma separated, in install order). There are no levels and no on/off switch.
- **Install:** sneak-right-click the module. It goes into the first worn piece it fits that doesn't have it yet, then the off hand (`GearRules.pick`). You can't choose the piece, and you can't install into an item in your main hand or inventory.
- **Remove:** right-click a modification station holding the gear. It takes out **the last installed module only**. The station is a machine that sleeps and does nothing (`ModificationStation`). It has no menu, no slots and no energy.
- **Side effects:** removing a module with an enchantment removes that enchantment, even when the gear had it from somewhere else. Removing an energy unit throws away the energy above the new capacity, without warning.
- Mekanized compiles against `api` only (D27), so it can't add its own zMenu buttons. A station menu needs a generic engine button.

## Design

### One engine mechanism: machine actions

Add one generic way for a menu to call a machine, so that upgrades and the station use the same path, and any addon can too:

- `api`: `MachineAction`, which a `MachineType` declares (`builder.action(id, action)`). It is called with a `MachineActionClick`: the player, the click (left, right, shift, drop), an `index` argument, and the cursor item. It returns what goes back on the cursor or into the inventory. It runs on the machine's region thread (D35) and can refuse, with a message to show.
- `hooks:zmenu`: the button `machineengine_action` (`action: <id>`, `index: <n>`). It shows a refusal message on the action bar.
- Placeholders that read a list from the machine: `%zmenu_machineengine_list_<id>_<index>_<field>%`. The type declares the list (`builder.list(id, ...)`) and the button is hidden when the index is past its end. This lets one menu show "upgrade 3" or "module 5" without a button type for each.

### Upgrades

- **Upgrade items are a CE item behavior:** `machineengine:upgrade` (`upgrade: mekanized:upgrade_speed`). For now, an item whose id equals an upgrade key still works, so existing packs and menus keep working; we report it once as deprecated.
- **The API owns the logic** (`MachineInstance`):
  - `canInstall(type)`: how many more fit, and why not;
  - `installUpgrades(ItemStack)`: installs from a stack and returns what's left;
  - `takeUpgrade(type, count)`: returns the items.

  `UpgradeButton` becomes a thin wrapper, and engine-level actions `machineengine:upgrade_install` and `machineengine:upgrade_take` are built in for every machine with upgrade slots.
- **A proper upgrades menu** (Mekanism's upgrade screen) that opens from the upgrade tab (`machineengine_menu`, `menu: <machine>_upgrades`). Mekanized generates it from the types the machine accepts (`MenuYaml`, textured as in D39):
  - **one row per accepted upgrade type:** its icon, `installed / max` (placeholders `upgrade_<ns>_<id>_count`, `_max`), and its effect in the lore;
  - **on that row:**

    | Click | Effect |
    |---|---|
    | With that upgrade on the cursor | Install as many as fit |
    | Left-click, empty hand | Take one out |
    | Shift-click | Take all of them out |

  - **an input slot:** dropping or shift-clicking any upgrade from the player's inventory installs it. This needs the menu listener to forward shift-clicks from the bottom inventory to the machine;
  - **a refusal says why** on the action bar: not accepted, no free slot, or full.
- **Right-click a machine holding an upgrade** installs one (sneak installs the stack), through `MachineTool` (D32). This is optional: Mekanism has no such shortcut.
- The old `machineengine_upgrades` button keeps working (menus the owner edited are never overwritten, `EditableFiles`). Clicking it with an empty hand now opens the upgrades menu instead of taking the last upgrade out.

### Modules

- **Levels and an on/off switch per module**, as in Mekanism:
  - `ItemData.Field.MODULES` becomes `name:level[:off]` entries. An old comma list reads as level 1, on (migration on first read, like `migratePdcLong`).
  - The `mekanized:module` behavior gets `max-level` (default 1).
  - Effects scale by level where Mekanism scales them: the energy unit multiplies capacity by `capacity-factor ^ level`; range, the excavation unit, the jetpack and so on as in Mekanism, listed in stage 4.
  - A module that is off costs nothing and does nothing.
- **Pure rules in `GearRules`** (tested without a server):
  - `install(modules, module, maxLevel)`;
  - `remove(modules, module, levels)`;
  - `toggle`;
  - capacity after a change;
  - the energy lost when an energy unit comes out.
- **Enchantments follow the module** only when the module gave them: the gear keeps its own level. We record what the module added, and don't remove anything else.

### The modification station as a working station

The station becomes a powered machine with a menu (generated by `MenuYaml`, textured as in D39):

- **Gear slot** (item storage, 1 slot): the MekaSuit piece or Meka-Tool. It stays in the station when the menu closes, and drops (or is kept) when the station is broken, like any machine slot.
- **Module slot** (item storage, 1 slot) and **energy** (a bar and an energy slot).
- **Installing:** a module in the module slot that fits the gear in the gear slot installs one level, as a process that costs energy and time (Mekanism's station: energy per tick, a progress arrow). The numbers are in the station's block config (D34). A module that doesn't fit, or is at its max level, shows why in the lore of the progress arrow.
- **The installed module list:** one button per module (`list_modules_<i>_*`), showing its icon, `level / max`, and on or off. Paged when there are more than fit.

  | Click | Effect |
  |---|---|
  | Left-click | Take one level out (the item goes to the inventory) |
  | Shift-click | Take all levels out |
  | Right-click | Switch it on or off |

  Taking an energy unit out when the gear holds more energy than it will fit asks for a second click, and the lore warns first.
- **The sneak-right-click shortcut** (install into worn gear) stays as a convenience, and installs one level. The current "right-click the station with the gear to take the last module out" is removed: the menu replaces it.

## Stages

Each stage ends with `./gradlew check` green and, from stage 2, a headless dev-server check (start, open, stop), with the server stopped before any rebuild.

### Stage 0: decisions. Done (2026-10-09)

- The owner accepted every proposal of "Decisions" below; D40 is in `ARCHITECTURE.md`.

### Stage 1: machine actions and lists (engine). Done (2026-10-09)

- `api`: `MachineAction` (`Click`: player, kind `LEFT`/`RIGHT`/`SHIFT`/`DROP`/`INSERT`, index, held `ResourceStack`; `Outcome`: done, used, give, message), `MachineList` (rows of fields), `MachineType.Builder#action/#list` (ids `[a-z0-9_]+`), `MachineInstance#runAction/#list`, `MachineEvents.ACTION`.
- `common`: `MachineInstanceImpl` runs actions on the caller's (owner) thread, refuses unknown ids and actions that throw (logged), and posts `ACTION` only when the action did something. `MachinePlaceholders.list` parses `list_<id>_size` and `list_<id>_<index>_<field>` (longest id wins; empty past the end).
- `hooks:zmenu`: `machineengine_action` (`action`, `index`, `list`: hidden past the end, `input: true`: shift-clicks from the player's inventory run the action as `INSERT`; this replaced the planned menu-level `input-action:`, following `machineengine_slot`). What the action uses comes off the cursor, what it gives goes to the inventory (dropped if full), the message goes to the action bar.
- Tests: `MachineActionTest` (use, refuse, give, unknown and failing actions, lists, events, id checks), `MachinePlaceholdersTest#resolvesListRows`.
- Paging long lists is left to stage 5, where the module list needs it.

### Stage 2: upgrade API and item behavior (engine). Done (2026-10-09)

- `api`: `UpgradeItem` (item behavior interface) and `UpgradeItems` (item to upgrade and back; platform `Access`, the id rule as a reported fallback); `MachineInstance#upgradeRoom` (how many more fit, or why none); `UpgradeActions`: the action `upgrade` (held item: install as many as fit, right-click one; empty hand: take out row `index`, one or with shift all; nothing is taken out of an upgrade with no item) and the list `upgrades` (accepted upgrades in registry order, then installed ones no longer accepted; fields `id`, `namespace`, `value`, `count`, `max`, `installed`, `item`). `MachineType` adds both to every type with upgrade slots unless it declares its own. (The plan's `installUpgrades(ItemStack)` / `takeUpgrade` became this action, which keeps `api` free of `ItemStack`.)
- `common`: `machineengine:upgrade` (`upgrade:` defaults to the item's own id; right-click a machine: one, sneak: as many as fit; action bar message), `CraftEngineUpgradeItems` (behavior lookup, item per upgrade cached per CraftEngine load, the id rule warned once per item), unbound check for upgrade items naming an unknown upgrade.
- `hooks:zmenu`: `UpgradeButton` runs the `upgrade` action with the cursor; with an empty hand it opens `<menu>_upgrades` if it exists, else takes the last upgrade out (the old behavior, until stage 3 writes the menus).
- Mekanized: the six upgrade items have `machineengine:upgrade`. New console check `mekanized check-upgrades <x y z> world`.
- Tests: `UpgradeActionsTest` (install with leftovers, right-click one, shift-click insert, each refusal, rows, take one/all, no item stays in, a type's own action wins). Dev check on Paper 26.3 and 1.20.6: 8 of 10 installed, listed 8/8, 8 given back, 3 rows, filter refused with its reason; no fallback warnings, no errors.

### Stage 3: upgrades menu (Mekanized). Done (2026-10-09); waiting for the owner's look in game

- One menu for every machine, `mekanized_upgrades.yml` (`UpgradeMenu`), instead of one file per machine: its rows are the machine's `upgrades` list, so each machine shows its own (as the shared side configuration menus do). Back on the top left, the upgrade tab marked on the top right, one row per registered upgrade across row 2 (6; a machine shows 3 or 4, the rest are hidden and leave an empty frame), the input slot below.
- A row shows the upgrade's own item (`craftengine:%…_item%`, redrawn live), its translated name and `count/max`. Click: take one out; shift-click: all; click with upgrades: install. The input slot takes a click with upgrades (right-click: one) or a shift-click from the player's inventory.
- `machineengine_upgrades` got `menu: <file name>`; every Mekanized upgrade tab opens `mekanized_upgrades` (empty hand) or installs (with upgrades), with new lore.
- Not done: Mekanism's upgrade selection sprites and count glyphs (the plain slot frames are used), and each upgrade's effect in the lore (the engine list has no description field).
- Tests: `UpgradeMenuTest` (every tab is on a machine with upgrade slots and opens the menu, enough rows for every machine, rows run the action, no overlap); `MenuConsistencyTest` checks its title images. Dev check on Paper 26.3 and 1.20.6: the menu loads with no zMenu error, machine menus point their tab at it, `check-upgrades` passes. Clicking needs a player: the owner checks install, take one, take all, shift-click from the inventory and the refusal messages.

### Stage 4: module levels and rules (Mekanized)

- `GearRules`: the level and on/off model, parsing old lists, install/remove/toggle, capacity and energy loss. The `mekanized:module` behavior gets `max-level` (Mekanism's maxima: energy unit 8, …; the list goes in `gear.yml`).
- Every reader of `GearItems.modules` (`GearTools`, `SuitTicker`, `GearLoreProcessor`, `ModuleHolderBehavior#modify`) uses levels and skips modules that are off. The gear lore shows the level and on/off.
- Enchantment tracking: only remove what the module added.
- Tests: `GearRulesTest` for the old format, levels, max level, toggling, energy loss, and capacity per level.

### Stage 5: modification station (Mekanized)

- `ModificationStation`: gear, module and energy storages, and an install process (energy and time from the block config). Actions: `module_take` (index, click: one or all), `module_toggle`, and confirming a lossy removal. The list `modules` reads the gear in the gear slot.
- Menu: the gear and module slots, energy, the progress arrow, and a paged module list.
- `ModuleHolderBehavior#use` no longer removes at the station; `GearModules.install` (the shortcut) uses the new rules.
- Tests: a headless chain where the station installs a module (energy spent, takes the time) and gives it back; the gear kept in the station across a save and reload.

### Stage 6: docs and in-game check

- `MEKANIZED_GUIDE.md` (how to install and remove upgrades and modules); `ARCHITECTURE.md` §14 (actions, lists, upgrade item behavior); D40.
- The owner checks in game: upgrades menu, station menu, shortcut, old menus they edited.

## Decisions (accepted 2026-10-09)

| # | Question | Decided |
|---|---|---|
| 1 | Engine-level `machineengine_action` + list placeholders, or a Mekanized-only station without a zMenu menu? | Engine actions: Mekanized can't add zMenu buttons (D27), and other addons get the same tool |
| 2 | Upgrade items: the CE item behavior `machineengine:upgrade`, keeping "id = key" as a deprecated fallback? | Yes |
| 3 | Install upgrades by right-clicking the machine with the item (not in Mekanism)? | Yes, one per click, sneak for the stack |
| 4 | Module levels and on/off (needs a data migration)? | Yes, as in Mekanism |
| 5 | Installing at the station costs energy and time, or is it instant? | Energy and time (Mekanism), numbers in the block config |
| 6 | Keep the sneak-right-click install shortcut? | Keep it (one level, worn or off-hand gear) |
| 7 | Removing an energy unit that would lose energy: refuse, confirm, or just lose it? | Confirm with a second click |

## Not in this plan

- Mekanism's per-module settings beyond on/off (jetpack mode, vision brightness, magnet range picker, …). The on/off switch is the base those settings would use, later.
- The radial menu for Meka-Tool modes.
- New upgrade types or new modules.
