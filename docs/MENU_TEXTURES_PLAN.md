# Textured menus plan (2026-10-09)

Machine menus are zMenu chest inventories (D20). Mekanized writes them as `mekanized_<machine>.yml` from `MenuYaml` / `MenuCatalog`: gray glass panes as background, vanilla items as gauges (`REDSTONE` for energy, `BLAZE_POWDER` for progress), and text bars in the lore. The side configuration (`SideMenus`) and redstone control (`MenuYaml#redstoneControl`) are vanilla items with text too.

This plan gives the menus Mekanism's look: a textured panel drawn over the chest, Mekanism slots (colored by role), gauges and progress arrows that fill, and Mekanism's side configuration and redstone control buttons. Menus stay zMenu files that server owners edit (D20). No gameplay change.

## What we know

- **CraftEngine draws images in container titles.** With `network.intercept-packets.container: true` (the default), CE replaces `<image:ns:id>` and `<shift:n>` tags in the title of any open-screen packet, on 1.20 and 1.20.3+ (`OpenScreenListener`). Images are font glyphs declared under `images:` in CE configuration, with `height`, `ascent`, `font`, and `char` / `chars` / `grid-size` (`AbstractFontManager`). Item names and lore get the same treatment (`intercept-packets.item`).
- **So the static part goes in the title, the live part goes in slots.** A title can draw the whole panel (176 × 222 for 6 rows, including the player inventory) and any sprite at any position. It cannot change without reopening the menu, and it has no tooltip or click. Anything that moves (gauges, progress, side modes, redstone mode, slot contents) must be an item in a slot, on the 18 px grid.
- **Items can draw texture over a slot.** A CE item with its own model shows any 16 × 16 picture (larger with a scaled `gui` display transform). CE already makes models from a parent and textures (`generation`, used for cable parts, D38), and handles 1.20.6 clients (custom model data) and 1.21.4+ (`item_model`).
- **Mekanized already has some Mekanism GUI sprites** (`textures/gui/sprites/`: `base`, `element_holder`, `inner_screen`, buttons, `gauge/<color>`, `bar/base`), imported in D31. It does not have Mekanism's slot, progress, tab or button icon textures (`gui/slot/`, `gui/progress/`, `gui/button/`, `gui/tabs/`, redstone control icons).
- **Menus are written by Java builders** (`MenuYaml`), and an owner's edited file is never overwritten (`EditableFiles`). Unedited files are replaced, so the new look reaches servers that did not edit their menus.
- **Button types today**: `machineengine_slot`, `machineengine_data`, `machineengine_side`, `machineengine_menu`, `machineengine_upgrades`, `machineengine_cancel_process`, player settings. They show one fixed item (with placeholders in name and lore); none picks a different item per value.

## Design

**Panel in the title, made of layered glyphs.** A menu title is built from glyphs and shifts:

```
<shift:-8><image:mekanized:gui/panel_5><shift:-176>…<image:mekanized:gui/slot/input_r1><shift:…>…<lang:block.mekanized.crusher>
```

- `gui/panel_<rows>`: the Mekanism background for 3, 4, 5 or 6 rows, player inventory included (from `base` nine-slice, composed once into a PNG per size).
- `gui/slot/<kind>_r<row>`: one glyph per slot kind (normal, input, output, extra, energy, …) and per row, since a glyph's vertical position is fixed by its `ascent`. Columns come from shifts. Mekanized declares them with a CE template (kinds × 6 rows).
- Static decorations (gauge frames, arrow outlines, labels) the same way.

Server owners can move a slot's picture by editing the title, without drawing PNGs. `MenuYaml` writes the title from the buttons it places, so the Java builders stay the single source.

**Live widgets as items.** New options on the button types, in `hooks:zmenu`:

- `machineengine_slot`: `empty-item` — the item shown when the slot is empty (an invisible model with the slot's name as tooltip), so the slot texture under it shows.
- `machineengine_bar` (new): a source (`storage: <id>` with optional `storage-slot`, or `process`), a `shape` (item prefix, e.g. `mekanized:gui/bar/tall`), and `cells` (slots, from empty end to full end). Each cell shows `<shape>/<level>`, dyed with the resource's color unless the button sets `color:`; name and lore keep the placeholders. Progress is a bar with `process` as source. See Fill bars below.
- `machineengine_data` and `machineengine_side`: `icons:` — one item per value (`ignored: mekanized:gui/redstone/ignored`, `input: mekanized:gui/side/input`, …), falling back to `item:`.

Items are CE item ids, made with `CraftEngineItemBridge`; an unknown id falls back to the button's `item:` and is logged once.

**Bar items are generated.** 17 models per shape (fill 0..16 px, from one parent with a cropped UV, like the cable shapes of D38) give `mekanized:gui/bar/<shape>/<level>` with a CE template; colors come from tint, not from more textures.

**Fill bars for every storage.** Every amount a menu shows gets a bar that fills, like Mekanism: energy, the metallurgic infuser's infuse tank (redstone, carbon, …), the osmium compressor's osmium, oxygen, every chemical and fluid tank, heat. Mekanism's three shapes:

| Shape | Size | Cells | Used for |
|---|---|---|---|
| tall gauge | 16 × 52 inside an 18 × 54 frame | 1 column × 3 rows | tanks: chemicals, fluids, infuse |
| power bar | 6 px wide, 3 rows tall | same cells, model on the left 6 px of the slot | energy |
| horizontal bar | 3 cells wide | 1 row × 3 columns | process progress, heat, small buffers |

- The frame is a title glyph (static); the fill is one item per cell (live). A cell shows level 0..16 of its own share: with `total = cells × 16`, `level = ceil(amount / capacity × total)` when not empty (1 mB shows 1 px), 0 when empty, and cell `i` shows `clamp(level - 16 i, 0, 16)`.
- **One model set per shape, colored by tint.** The fill texture is gray-scale; the cell item is dyed with the resource's color (`minecraft:dyed_color` with `tintindex 0`; CE maps it for 1.20.6 and 1.21.4+). Redstone is red, carbon black, oxygen light blue, energy Mekanism's green, without a texture per resource. Fluids use their own still texture where tint is not enough (water is tinted, lava has its own).
- **Colors come from the resource.** Resources have no color today. Add an optional `color` to resource definitions (chemicals, fluids, energy, heat) in Mekanized's `chemicals.txt` / `fluids.txt` (Mekanism's tint values), with a gray default; the bar button reads it from the storage's current resource, so a tank shows the color of what it holds.
- **Hover keeps the numbers.** Each cell has the bar's name and lore (resource name, amount / capacity), so hovering any cell reads like Mekanism's tooltip.

**Side configuration and redstone control (Mekanism style).**

- Side config: one menu per port group as today, tabs across the top as group icons, the unfolded cube of 6 side buttons with Mekanism's mode colors (none gray, input red, output blue, both purple), the auto-eject toggle with its icon, and the back button. The machine's slots in the main menu get the colors of their role, so a player sees which slot is fed from which side.
- Redstone control: a single icon button cycling ignored / high / low with Mekanism's three icons (gunpowder, redstone, redstone torch off).
- Upgrades: a Mekanism-style upgrade tab icon; the menu itself unchanged.

## Stages

### Stage 0: spike (prove the technique on one menu). Done (2026-10-09), checked in game by the owner on Folia 26.2.

**Result:** panel, colored slot frames and gauge frame line up with the chest grid at the computed positions (glyph top = `13 - ascent`, advance = width + 1, `<white><shift:-8>` before the panel); zMenu passes `<image>` and `<shift>` through and CE replaces them; the empty-slot item shows the slot texture and inserting still works; the CE items in buttons do not flicker and keep their tooltips. The energy and progress test items are fixed pictures (half full), so they do not follow the machine: that is stage 3's `machineengine_bar`. Still to check: a 1.20.6 client, and 3- and 6-row panels.

On the crusher, by hand: a panel glyph and two slot glyphs in the title, one gauge cell and an invisible empty-slot item. No Java: zMenu 1.1.1.9 loads CraftEngine items itself (`material: craftengine:<id>`, its `CraftEngineLoader`), so the spike menu only uses YAML.

Written: `pack/configuration/gui.yml` (glyphs `gui/panel_5`, `gui/slot_input_r1`, `gui/slot_output_r1`, `gui/gauge_r1`, `gui/slot_r3`, font `mekanized:gui`; items `gui/empty`, `gui/test_energy`, `gui/test_progress`), textures under `textures/font/gui/` and `textures/item/gui/` (composed from `sprites/base`, `element_holder` and `gauge/*`), and a hand-written `mekanized_crusher.yml` in the Folia 26.2 dev server's menu folder (delete it afterwards to get the generated menu back). Positions assume a glyph's top is at `13 - ascent` and its advance is width + 1; CE's own GUIs use `<white><shift:-11>` and ascent 18 for a 182-wide image, which the check confirms or corrects.

Check, on 1.20.6 and 26.3 clients, on Paper and Folia:
- zMenu passes `<image:…>` and `<shift:…>` through MiniMessage untouched (or find the escaping that does), and CE replaces them;
- the title still updates the machine name per language (`<lang:…>`);
- the panel lines up with the chest grid at 3, 5 and 6 rows (ascent and shift values recorded here);
- a CE item with a model in a zMenu button, updated every 500 ms, does not flicker and keeps its tooltip;
- an empty machine slot shows the slot texture through `gui/empty`, and clicking it still inserts the cursor item.

Stop and report if any of these fails.

### Stage 1: textures. Done (2026-10-09).

- Imported by hand (D31) from Mekanism commit `4718860` (branch 26.1), MIT (`LICENSE-MEKANISM.txt`): Mekanism keeps its menu textures in `assets/mekanism/gui/`, not `textures/gui/`. Copied to `textures/gui/` under the same relative paths, 169 PNGs:
  - `slot/` (26: normal, input, input_2, output, output_2, extra, power, ore, digital, inner_holder_slot, the large/wide outputs and warnings, the `overlay_*` icons), `progress/` (12: right, bar, flame, down, …; each file stacks empty, full and warning strips), `bar/` (7: vertical and horizontal power, rate, digital), `gauge/` (5: the tick overlays standard, small, small_med, medium, wide), `switch/` (5), `tabs/` (12), `button/` (53);
  - top-level icons: side config groups (`items`, `energy`, `chemicals`, `fluids`, `heat`), `configuration`, `transporter_config`, `redstone_control_{disabled,high,low}`, `upgrade`, `frequency`, security, teleporter states, `container_edit_mode_*`, `gas_mode_*`, arrows, …;
  - `generators/` from `mekanismgenerators/gui/` (7: fuel, sees_sun, no_sun, wind, state_holder, `button/reactor_logic`).
- Left out (not menus): `hud/`, `radial/`, `mode/`, `icons/` (cable icons), the 256 × 256 `compass` and `warning*`, `slot/slots*.png` sheets, sliders, scroll bars, color picker, module and upgrade selection.
- Gauge frames and colored slot frames reuse the nine-slice sprites already in `sprites/gauge/` (D31 import).
- `panel/panel_{3,4,5,6}.png`: Mekanism's `sprites/base` at the chest screen size (176 × (114 + 18 rows)) with the player inventory slots (`slot/normal`), written by `mekanized/tools/compose_menu_panels.py` (run by hand, not a build step).
- The spike's own composed slot textures (`textures/font/gui/`) are removed; `gui.yml` points at `panel/panel_5` and `slot/{input,output,normal}`.

### Stage 2: CE configuration for the GUI.

- `pack/configuration/gui.yml` (Mekanized): `images:` (panels, slot kinds × rows, static decorations) and `items:` (invisible item, gauge and progress levels, side mode, redstone and tab icons), with templates in `templates.yml`.
- Run `:plugin:ceSnapshot` and accept the new entries.

### Stage 3: engine button types and resource colors.

- `hooks:zmenu`: `empty-item`, `machineengine_bar`, `icons:`; the level math (amount → per-cell level, rounding so 1 mB shows 1 px and full shows full) in a plain class with unit tests.
- Optional `color` on resources (API and definitions), Mekanism's tints in `chemicals.txt` / `fluids.txt`; a test that every Mekanized chemical has one.
- Document the new keys in `menus/machine.yml`'s header comment.

### Stage 4: Mekanized layouts.

- `MenuYaml`: a `Layout` that records each placed slot kind and decoration and writes the title; `slot`, `tank`, `chemical`, `heat`, `energyDisplay` and the progress button switch to bars (tall gauge for tanks, power bar for energy, horizontal bar for progress and heat). Layouts move to Mekanism's: energy bar on the right, tanks on the left, progress between input and output.
- Convert family by family, checking each in game: processing and generators first, then chemistry, logistics, loaders and buffers, reaction and pigment, utility, automation, multiblocks, reactors. `MenuConsistencyTest` checks every title glyph exists and no two buttons share a slot.

### Stage 5: side configuration, redstone control, upgrades.

- `SideMenus` rewritten as in Design; slot colors in the machine menus from the side config roles.
- `redstoneControl` with icons; the upgrade button with the tab icon.

### Stage 6: docs.

- `docs/MEKANIZED_GUIDE.md` (how to edit a textured menu: titles, glyph ids, widgets), the button keys in `ARCHITECTURE.md` §GUI, and the decision (D39).

## Owner answers (2026-10-09)

1. Players without the resource pack: **require the pack**; no plain fallback.
2. Textures: **Mekanism's** (MIT).
3. Panel: **layered glyphs** in the title.
4. Also asked: fill bars for every storage (redstone, energy, …) — added as Fill bars above.

## Open questions

4. **Engine menus:** should `machine.yml` and `player_settings` (MachineEngine, not Mekanized) also get a textured look, with a small `machineengine` GUI kit, or stay plain?
5. **Machine name:** Mekanism centers it; centered text needs its width, which a translated name does not give. Left-aligned is proposed.
6. **Edited menus:** owners who edited a menu keep the old look. Leave them, or overwrite once (no other servers run Mekanized yet, as for D38)?

## Not in this plan

- Clickable elements outside the chest grid (Mekanism's side tabs): a chest only takes clicks on slots, so tabs go in the grid.
- Animated textures in titles.
