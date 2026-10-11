# Resource color plan

Owner's request (2026-10-10): look at how fluids are used in menus, and define a resource's color directly in code, not in a hardcoded constant or a lookup table. The owner's decision: **the color lives inside `ResourceType`.** Not a separate registry, not a colorer.

Status: implemented (D41). The owner approved the three open questions: addon YAML sets `color:`, the fluid label follows the color, heat's color is on its type. An earlier attempt (a `ResourceColors.set` registry in front of the colorers) was reverted at the owner's request. Heat's color stays fixed in code (see Limits).

## Limits

- Heat's type is created in a static field that other classes read at class init, before config.yml's values are applied. Its color is fixed to Mekanism's orange. Making it configurable needs heat's creation moved into `register`, with every static reader changed.

## Where colors are today

- Menus read a color through the placeholder `storage_<id>_color` (and `_slot_<n>_color`), which calls `ResourceColors.of(variant)` (`api/paper/ResourceColors.java`). Tinted bars use it as `dyed-color` (MENU_TEXTURES_PLAN stage 3).
- `ResourceColors.of` asks the registered colorers in order, then falls back to two hardcoded checks: water (`0x3F76E4`) and lava (`0xE0641A`) by key, else white.
- Mekanized registers one colorer (`MekanizedPlugin`) that reads `MekanizedContent.COLORS`, filled from the second column of `mekanized/chemicals.txt` and `mekanized/fluids.txt`.
- Heat is `HEAT_COLOR = 0xFF7E1F` in `MekanizedContent`, a constant in Java.
- The fluid tank's label color is `<aqua>`, hardcoded in `MenuLayout.Tank.FLUID`. Not part of this plan (see Open questions).

So a color is set in four places (two constants, one text file, one Java constant), and none of them is on the type it belongs to.

## Design

- **`ResourceType` gets an optional `color`** (`0xRRGGBB`, or `null` for none). It is a field set at construction, so the type stays final and its identity by reference (D9) is unchanged. A type's color is part of its definition, not something changed later.
- **Construction only.** New factories `ResourceType.of(key, category, color)` and `ResourceType.of(key, category, components, color)`. The existing factories keep working and mean "no color". No `withColor`: it would make a second object for one type and break reference identity.
- **`ResourceColors.of` reads the type first:** `variant.type().color()`, then the colorers, then white. Colorers stay for addons that color many types by rule. The key checks for water and lava go away.
- **Fluid helpers** take the color too: `Fluids.create(key, color)`. Water and lava get their colors where `BuiltInResources` builds them.
- **Mekanized sets colors where it creates the type.** The text lists already give the color, so `MekanizedContent.register` passes it to `ResourceType.of`. The `COLORS` map and the Mekanized colorer go away.

What this is not: a YAML `color:` key on resource definitions, or a color on a `ResourceVariant` (per-stack). Both are possible later, but the owner asked for the type.

## Stages

**Stage 0: decide.** Done in this plan. Owner to confirm the design above.

**Stage 1: `ResourceType` and tests (api).**
- Add the `color` field, its accessor (`@Nullable Integer color()`), and the two new factories. Keep `equals` and `hashCode` as they are (reference identity).
- Add `Fluids.WATER_COLOR = 0x3F76E4` and `Fluids.LAVA_COLOR = 0xE0641A`, and pass them in `BuiltInResources`.
- Tests: a type made with a color returns it; a type made without returns `null`; the factories with and without components agree.

**Stage 2: `ResourceColors` reads the type (api).**
- `of` checks `type.color()` first, then the colorers, then white. Remove the water and lava key checks.
- Keep `WHITE`, `hex` and `register`/`unregister`.
- Tests in `MachinePlaceholdersTest`: a type's color is used; a colorer does not override it; a type with no color still reaches the colorers.

**Stage 3: Mekanized (mekanized).**
- Chemicals, Mekanized fluids and heat get their colors through `ResourceType.of(..., color)`. Parse the `#RRGGBB` column in `lines(...)` before the types are made, not after.
- Remove `MekanizedContent.COLORS`, `color(type, line)` and the colorer in `MekanizedPlugin`. Keep `MekanizedContent.color(type)` as a thin reader of `type.color()` only if something else still calls it; otherwise remove it.
- `EngineResourcesTest` keeps its checks, now on `type.color()`. Water is no longer "Mekanized doesn't color it", so update that assertion.

**Stage 4: docs.**
- ARCHITECTURE.md: a new decision (D41) with the owner's decision line.
- MENU_TEXTURES_PLAN.md stage 3 and MEKANIZED_GUIDE.md (around line 321): say the color is on the resource type.
- Javadoc of `ResourceType` and `ResourceColors`.

**Stage 5: verify.**
- Unit tests for `api`, `common` and `mekanized` (`./gradlew.bat :<module>:test --offline`). Check the XML reports, not the exit code of a pipe.
- In game, only if the owner asks: a dev server check per the headless memory (pipe console commands, wait for `Done (`, stop). No build while a server runs.

## Risks

- **Source compatibility.** Additive: existing `ResourceType.of` calls and addons compile unchanged.
- **Identity.** Colors are fixed at construction. Changing a color means a new type, which is also a new identity. Fine here, since the color is set once at startup.
- **Water and lava at startup.** `BuiltInResources` runs before any colorer; the colors must not depend on config, so they are constants.
- **Mekanized order.** Its `.txt` lists must parse before the type is created; a missing color must stay `null` (white), not an error.

## Open questions for the owner

1. Should addon YAML definitions (`definitions/*.yml`) be able to set `color:`? Not in this plan; the type gets its color from Java only.
2. The fluid tank's label is `<aqua>` in `MenuLayout`. Should the label follow the type's color (`<color:#hex>`), or stay a fixed color?
3. Heat's color moves from a Java constant to the type too. Confirm that is wanted.
