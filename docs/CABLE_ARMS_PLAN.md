# Cable arms plan (2026-10-09)

Transports (cables, tubes, pipes, transporters, conductors) are drawn by `CableArmsElement`: one client-side item display for the center and one per connected side. Each side has its own model, so every transport ships 19 models and 19 `cable_part/` items: `center`, plus `north`, `south`, `east`, `west`, `up`, `down` each in plain, `_pull` and `_push`. For 20 transports that is 380 model files and 380 items, and they are copies of each other turned to another side.

This plan draws every arm from one model pointing north, which the display entity turns to the side. It also builds each transport's models from shared shapes with CraftEngine model generation, so the resource pack sources keep only the shapes.

No gameplay change. Placed cables keep working: the block, its behavior and its saved side modes do not change, only the items and models that draw it.

## What we know

- **Prototype (done, checked in game 2026-10-09).** `CableArmsElement` uses `<prefix>/arm`, `arm_pull` and `arm_push` when those items exist. It turns them with the display's left rotation: the display draws the north model pointing south (+Z), and `turn(Direction)` takes +Z to the side. Without those items it falls back to the per-side items. `basic_universal_cable` has the three items in `machines.yml`, pointing at its existing `north*` models. All six sides, pull and push looked right, including the textures on the up and down arms.
- **Textures follow the arm.** On every arm model the stripes of the `#side` texture run along the arm, so turning the north arm gives the same picture as the old per-side models.
- **Only two shapes.** The geometry and UVs of the 19 parts are the same within each group:
  - **thin** (center 5..11, arm 5..11 × 0..5): universal cable, pressurized tube, thermodynamic conductor (12 blocks);
  - **thick** (center 4..12, arm 4..12 × 0..4, full-face UVs): mechanical pipe, logistical transporter (8 blocks).

  The models differ only in their textures `#center`, `#side` (`<texture>_vertical`) and `#particle`. Transporters use the textures under `multipart/opaque/`.
- **CraftEngine can generate a model from a parent and textures** (`model: {path, generation: {parent, textures}}`), as `mekanized:item/generated` already does in Mekanized's `templates.yml`.
- **Assets are rewritten at every start; configuration files are kept if the owner edited them** (`MekanizedPlugin#installCraftEnginePack`, `EditableFiles`). A server that edited `machines.yml`, `chemistry.yml`, `logistics.yml` or `reactors.yml` keeps its old 19-part items, which point at the per-side models this plan removes (see Open questions).

## Stages

### Stage 1: shared shape models. Done (2026-10-09).

Add 8 parent models under `resourcepack/assets/mekanized/models/block/cable/shape/`:
- `thin/center`, `thin/arm`, `thin/arm_pull`, `thin/arm_push`;
- `thick/center`, `thick/arm`, `thick/arm_pull`, `thick/arm_push`.

Each is the current `basic_universal_cable` or `basic_mechanical_pipe` part (`center`, `north`, `north_pull`, `north_push`) with the textures left as variables (`#center`, `#side`, `#particle`). Check: the generated models are identical to the files they replace (a small script compares elements, faces and textures).

### Stage 2: item template and factory rows. Done (2026-10-09).

- Add the template `mekanized:item/cable_part` to Mekanized's `templates.yml`. It takes the arguments `shape` (thin or thick), `part` (center, arm, arm_pull, arm_push) and `texture` (e.g. `mekanized:block/models/multipart/basic_universal_cable`). It generates `mekanized:block/cable/${transport}/${part}` from `block/cable/shape/${shape}/${part}`, with `#side` = `${texture}_vertical`.
- In the four config factories (`machines.yml` `energy_transports`, `chemistry.yml` `chemistry_transports`, `logistics.yml` `item_transports`, `reactors.yml` `heat_transports`), each row gets `shape` and `texture`. The blueprint makes 4 parts instead of 19 (`center`, `arm`, `arm_pull`, `arm_push`), and the block item keeps using `center`.
- Remove the prototype items from the `items:` section of `machines.yml`.
- Update the comments above each factory ("19 parts" → 4) and the `mekanized:block/transport` template comment.
- Run `:plugin:ceSnapshot`: expect 15 fewer `cable_part/` items per transport (380 → 80) and changed `model` entries for the 4 kept parts. Accept it.

### Stage 3: remove the per-side models. Done (2026-10-09): 435 files deleted (380 per-side models, 26 transmitter models, 7 OBJ/MTL/blend files, 22 item models); checked in game by the owner (2026-10-09), all five families.

- Delete the 20 `block/cable/<transport>/` folders: 380 files, which the generation now writes.
- Delete the Mekanism leftovers no config points at: `block/transmitter/**`, the `transmitter_{small,large,contents}.{obj,mtl,blend}` files in `models/`, and the 22 transport `models/item/*.json` files (20 tiers plus `diversion_transporter` and `restrictive_transporter`). Grep for each path before deleting.
- Check in game: every transport family (cable, tube, pipe, transporter, conductor) in two tiers, connected on all six sides, with pull and push.

### Stage 4: Java and docs. Done (2026-10-09): `CableArmsElementTest`, D38.

- `CableArmsElement`: keep the per-side fallback for addons and old configs (see Open questions), and document `<prefix>/arm` first in the class comment and in the `TransportBlockBehavior` comment (`connection_items`).
- Turns are fixed per direction: build the 6 quaternions once, as a static array, instead of on each cache miss.
- Add a unit test for `turn`: each direction's quaternion takes (0, 0, 1) to that direction's vector.
- Update `docs/CE_TEMPLATES_PLAN.md` (transports: 4 parts) and `docs/MEKANIZED_GUIDE.md` if it lists the parts, and add D38 to `docs/ARCHITECTURE.md`.

## Decision

**D38** (recorded in ARCHITECTURE.md): a transport's `connection_items` prefix names 4 items: `center`, `arm`, `arm_pull` and `arm_push`. The arm points north and the display turns it to each side. Mekanized generates them from two shared shapes (thin, thick) and one texture per transport. The per-side items (`<prefix>/<direction>[_pull|_push]`) remain a fallback for addons that already ship them.

## Owner answers (2026-10-09)

Both settled: (1) delete the old models outright, since no other server runs Mekanized yet; (2) keep the per-side fallback.

The questions were:

1. **Servers that edited a transport file.** Their kept file still lists the 19 per-side items, whose models Stage 3 deletes, so their cables would show the missing-model texture. Options:
   - (a) delete anyway and say so in the changelog: owners merge the new factory block (recommended: the edit was the rate in a factory row, easy to copy);
   - (b) keep the 380 old model files for one more release;
   - (c) at start, warn when a kept file still defines `cable_part/<transport>/north`.
   (c) can go with (a).
2. **Keep the per-side fallback in Java?** Recommended: yes. It costs 4 lines, and addons written against the current `connection_items` docs keep working.

## Not in this plan (noticed on the way)

- **Stale arms.** Arms are redrawn on a neighbor block update, on load and when the configurator cycles a side. A machine's port side-config change (`MachineInstanceImpl` → `networks().machineChanged`) does not redraw the cables next to it, so they can show a wrong arm until the next block update.
- **Hitbox.** Transports take the spruce sapling's outline (about 2..14 × 0..12) and have no collision, so the outline does not match the thin cable and players walk through cables.
