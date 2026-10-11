# Docs inventory (DOCS_PLAN stage 1)

Every user-facing fact in README.md (R:line) and docs/MEKANIZED_GUIDE.md (G:line), checked against the
code on 2026-10-11 (commit 13776d6). Used to write the website pages; facts not listed under "Stale"
were found correct. Abbreviations: `cmn` = common/src/main/java/fr/robie/machineengine,
`zm` = hooks/zmenu/src/main/java/fr/robie/machineengine/zmenu, `mek` = Mekanized's Java root,
`mekres` = mekanized/src/main/resources/mekanized/pack/configuration.

## Stale

| Doc | Fact | Correction | Code |
|---|---|---|---|
| R:113 | A wrong config value falls back to its default | Clamped keys (`players.save-interval`, `players.load-timeout`) take the nearest allowed value | api/.../config/ConfigKey.java:185,217 |
| R:290 | `machine` (alias `inspect`) | The command is `inspect`; `machine` is the alias | cmn/paper/MachineEngineCommands.java:70 |
| R:306 | Debug dots: green, blue, orange, red | Also yellow scheduled, aqua waiting, gray disabled, magenta placeholder | cmn/paper/MachineDebugView.java:44-46,170-180 |
| R:76 | "see the comments in machine.yml for the full list" | They omit `placeholder`, `list_*`, `data_*`, `side_*`, `player_*` and several button types | hooks/zmenu/src/main/resources/menus/machine.yml:4-28 |
| R:191 | `resource_item` keys `resource`, `capacity`, `fill-from-machines` | Also `max-input`, `max-output`; `fill-from-machines` defaults to false | cmn/craftengine/item/ResourceItemBehavior.java:57-59 |
| R:247 | display_rendered templates take `display_state`, `display_model` | Also optional `state_type` (default `leaves`) | plugin/.../templates.yml:120-135 |
| R:148 | `definitions/*.yml` | Sub-folders are read too, sorted by path | cmn/paper/YamlDefinitions.java:49-51 |
| R:263 | Bukkit events fire on the machine's thread | Not `MachineEngineReloadEvent` (registry reload) | cmn/paper/event/BukkitEventBridge.java:35 |
| G:205 | `config_factory#..._transports` at the end of four files | Not at the end; name them: `#energy_transports` (machines.yml:72), `#chemistry_transports` (chemistry.yml:117), `#item_transports` (logistics.yml:54), `#heat_transports` (reactors.yml:318) | mekres |
| G:335 | Command table | Omits `frequency`, `place-set`, `fill` and every `check-*` | mek/paper/command/MekanizedCommands.java:31-41 |

Details worth adding where the docs are right but short: `placeholders purge` must be repeated within
10 s; `registry <name>` defaults to the `machineengine:` namespace; `reload config` also reloads menu
files; `player <player>` with no action lists every value; `PlayerMachineInteractEvent` is not
affected by `events.bukkit-bridge`; `place-set` without a position uses the block above the one looked
at; `players.load-timeout` defaults to 5 (1-60); `machine.yml` is written only if missing.

## In the code, in neither doc

**Configuration (config.yml):** `limits.max-machines-per-chunk` 256 (0 = none; admins exempt),
`menus.default` "machine", `placeholders.allow-breaking` false, `resources.energy.scale` 0 (0-18),
`resources.fluid.scale` 0, `resources.fluid.water-color` "#3F76E4", `resources.fluid.lava-color`
"#E0641A".

**Permissions:** `machineengine.use` (TRUE, open menus); `machineengine.admin.breakplaceholders` and
`mekanized.personal.bypass` are checked in code but not registered in plugin.yml.

**Commands:** `/machinesettings <setting>` shows its value; a setting may be named by id alone when
unambiguous. `/mekanized check-block-options`, `check-upgrades`, `check-station`, `check-modules`,
`check-item-data`.

**Placeholders:** `type_namespace`, `type_value`, `placeholder`, `last_wake`, `error`, `x`, `y`, `z`,
`world`, `escrow`, `upgrade_<namespace>_<value>`; `storage_<id>_percent|bar|free|resource|name`;
`storage_<id>_slot_<n>_amount|capacity|resource|name|color`;
`process_active|recipe|status|progress|duration|remaining|remaining_seconds|percent|bar`;
`side_<group>_<side>` (label form).

**Button types:** `machineengine_slot` keys (`storage` "input", `storage-slot` 1, `allow-insert`,
`allow-take`); `machineengine_cancel_process` `always-show`; `machineengine_data` (`data`, `values`);
`machineengine_side` (`side`, `group`); `machineengine_menu` (`menu`, `group`, `groups`);
`machineengine_upgrades` with no upgrades menu returns the last installed upgrade.

**Block behavior `machineengine:machine`:** `machine` (required), `facing_property` facing,
`active_property` active, `display_item`, `display_rotation` 0, `display_rotations`, `level_display`,
`level_storage` energy, `level_steps` 4, `side_display`; per-block `storages:`, `ports:`, `options:`
(D34). Loot function `machineengine:keep_contents`; `block/machine` already includes keep-contents.

**Transports:** block behavior `machineengine:transport` with `transport` (required),
`connection_items` (`<prefix>/center|arm|arm_pull|arm_push`, or per direction), `rate`. Side modes
default/pull/push/none. Transport types are Java-only (no YAML section).

**YAML definitions:** top-level `enabled:` (default true); `resource-categories {unit, scale}`;
`resource-types {category, color}`; `recipe-types`; `item-tags`, `resource-tags`; machine keys
`storages {slots, capacity, filter}`, `ports {storage, direction, sides, filter, rate}`,
`upgrade-slots` 0, `catch-up` 0; recipe keys `type`, `duration`, `inputs`, `outputs`,
`cost-per-tick`, `chance-outputs`; filters `any`, `type`, `types`, `category`, `tag`, `item`,
`item-tag`, `all`, `any-of`, `not`; `machineengine:processing` keys `recipes`, `inputs`, `output`,
`costs`, `push`, `retry-ticks` 20. Multiblocks: `pattern.rotate` (true); `box.casing`, `faces`,
`interior`, `min-size`, `max-size`, `width`, `open-top`, `controller`;
`capacities: {x: {per-block: N}}`; member `role: frame|valve|port`, `storages`, `exclusive`,
`output-rate`.

**Player data:** with `machineengine:files`, a damaged file is moved aside as `.damaged-<time>`.
Mekanized keys `mekanized:suit_flight`, `mekanized:raised_step` (saved), `mekanized:last_teleport`
(session); setting `mekanized:radiation_hud` (true).

**Events:** bus-only `MachineEvents.ACTION`, `PlayerEvents.SETTING_CHANGED`,
`EngineEvents.REGISTRIES_FROZEN`; base classes `MachineEvent`, `MachineProcessEvent`.

**Dev/build:** `-PwithMachineEngine=false`, `-Pcraftengine_build_heap` (4G),
`craftengine_modrinth_version` 26.9.2, `-Pcraftengine_version` (CI), `:plugin:buildCraftEngineDev`,
`:plugin:ceSnapshot` (`-PacceptCeSnapshot`), `verifyRelocation`, `verifyBundledPaperBaseline`; the
stock `runServer` task is disabled.

**Mekanized:** config keys and defaults: `teleporter.base-cost` 1000, `cost-per-block` 10,
`other-world-cost` 100000, `cooldown-seconds` 3.0; `chunk-loading.max-radius` 2 (0-8);
`radiation.background` 1e-7, `source-decay-per-second` 0.995, `min-source` 1e-6,
`dose-decay-per-second` 0.999, `sickness-dose` 1, `damage-dose` 10, `lethal-dose` 100;
`digital-miner.scan-per-tick` 2048, `max-radius` 32; `qio.array-kinds` 1024
(mek/MekanizedConfig.java:33-69). Item behaviors not in the guide: `module_holder`,
`configuration_card`, `dictionary`, `gauge_dropper`, `crafting_formula`, `configurator`,
`tier_installer`, `turbine_blade`, `canteen`, `radiation_reader` (`mode`), `portable_teleporter`,
`portable_qio`, `cardboard_box`, `cardboard_box_storage`, `seismic_reader`; processor
`mekanized:gear_lore`. Templates `block/transport`, `energy_cube`, `chemical_tank`, `fluid_tank`,
`display_machine`, `machine_facing`, `machine_active`, `storage_block`. Config factories
`#basic_machines`, `#factories`, `#induction_parts`, `#storage_blocks`, `#menu_rows`,
`#menu_bar_cells`, `#menu_bar_long`. Modifier alias `mekanized:process_cost/energy`.
`plugins/Mekanized/installed.properties`. Multiblock physics keys in mek/multiblock/*.java.
