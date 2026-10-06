# Config loader and built-in resources plan (draft, 2026-10-06)

Goal: one configuration loader with typed getters, which writes every missing key into
`config.yml`. The first users are the engine's new built-in resources: **energy, water and lava**.
They move out of Mekanized, and their unit and scale are read through the loader when the
resources are built.

**Rules for every stage** (as in the other plans):
- Each stage is one or more commits that build and pass `./gradlew test`.
- Never build jars while the dev server runs; `compileJava` and `test` are fine.
- A world saved before a stage must load after it (checked on the dev servers, stage 6).
- Mekanized's main code keeps compiling against `api` only (D27).
- Paper API used by the loader must exist in 1.20.6 (D25): `YamlConfiguration` with comment
  parsing and `ConfigurationSection.setComments` (Bukkit 1.18.1+) both do.

## Decisions (owner, 2026-10-06)

| # | Decision |
|---|---|
| Q1 | Keys: `machineengine:energy` (category and type); category `machineengine:fluid`; types `machineengine:fluid/water` and `machineengine:fluid/lava`. |
| Q2 | The unit (and scale) belong to the energy and the fluid category. They are read from config through getters and set on the category when it is built. |
| Q3 | `machineengine:fluid` is one global fluid category for everyone. The engine registers only water and lava. Mekanized keeps registering its own fluids (brine, …) as `mekanized:fluid/<name>`, in the global category. A pipe that accepts the fluid category moves every fluid registered in it. |
| Q4 | The built-ins are always registered (no switch). |
| Q5 | `/machineengine reload config` re-reads reloadable keys live. Keys used to build registries (units, scales) are restart-only, and a reload says so when they changed. |

These become decision **D29** in `ARCHITECTURE.md` (stage 6).

## Where things stand

- **config.yml:** 5 keys read with raw `getConfig()` calls (`MachineEnginePlugin.java:105-123`,
  `ZMenuMachineMenus.java:116-118`). There is no holder, keys missing from an old file are never
  added, and the file is never reloaded.
- **Mekanized** has its own loaders (`MekanizedSettings`, `GearSettingsLoader`): defaults in static
  fields, range checks, a warning per bad value. The new engine loader generalises this pattern.
- **Built-in resources:** only items (`CoreMachineEngine.java:130-132`).
- **Energy and fluids live in Mekanized** (`MekanizedContent`): `mekanized:energy` (unit `J`, ~114
  Java uses) and `mekanized:fluid/<name>` from the generated `fluids.txt` (water and lava
  included). The generated definitions name `mekanized:energy` 1 375 times and
  `fluid/water`/`fluid/lava` 28 times (`buildSrc` `Recipes.kt`, `Assets.kt`, `MekanismSource.kt`).
- **Water/lava ↔ world fluid** is mapped twice: `CraftEngineMachineHost.java:527-538` and
  Mekanized `UtilityBehaviors.java:95-100`.
- **Saved data names resource types by key** (`ResourceCodecs.decodeVariant`), with no rename
  support. Changing `mekanized:energy` to `machineengine:energy` with no alias would turn every
  machine holding energy or water into a placeholder.

---

## Stage 1: the config loader (done 2026-10-06)
**Result:**
- `api/config`: `ConfigKey` (string, bool, integer, whole, decimal, choice, strings, map; `comment`,
  `restartOnly`), `ConfigValues`, `ConfigSpec` (header, section comments, key order, `defaults()`),
  `ConfigFile` (`load`, `reload`, `get`) and `ConfigLoadResult`.
- **The implementation lives in `api`, not `common`**, so Mekanized (api only, D27) can use it in
  stage 7. It needs only Bukkit's `YamlConfiguration`.
- An up-to-date file is never saved, so it keeps the owner's exact formatting. A file is saved only
  when it is created or a key was added.
- `ConfigFileTest` (12 tests) covers creating, completing, invalid values, a value where a section
  belongs, broken YAML, unknown keys, every key type, reload and restart-only keys.
  `verifyPaperBaseline` passes.
- Deleting the bundled `plugin/.../config.yml` and the golden-file test move to stage 2, where the
  engine's keys are declared.

**What was planned:**

### API (`fr.robie.machineengine.api.config`)
```java
// A typed key: path, default, comment, range, and whether a reload may change it.
public final class ConfigKey<T> {
    static ConfigKey<String>  string(String path, String def);
    static ConfigKey<Boolean> bool(String path, boolean def);
    static ConfigKey<Integer> integer(String path, int def, int min, int max);
    static ConfigKey<Long>    whole(String path, long def, long min, long max);
    static ConfigKey<Double>  decimal(String path, double def, double min, double max);
    static <E extends Enum<E>> ConfigKey<E> choice(String path, E def);
    static ConfigKey<List<String>> strings(String path, List<String> def);
    static ConfigKey<Map<String, String>> map(String path, Map<String, String> def);

    ConfigKey<T> comment(String... lines);   // written above the key
    ConfigKey<T> restartOnly();              // reload keeps the old value and says so
    String path(); T defaultValue();
}

// Read-only, thread-safe getter. Values are an immutable snapshot.
public interface ConfigValues {
    <T> T get(ConfigKey<T> key);
}

// One YAML file: a plugin's config.yml (the engine's, Mekanized's, any addon's).
public interface ConfigFile extends ConfigValues {
    static ConfigFile of(Plugin plugin, String fileName, List<ConfigKey<?>> keys);
    static ConfigValues defaults(List<ConfigKey<?>> keys);   // headless tests
    ReloadResult reload();   // which keys changed, which need a restart, which were invalid
}
```

### Loading (`common`, `YamlConfigFile`)
1. Read the file with comments kept (`YamlConfiguration`, `options().parseComments(true)`). If it
   does not exist, create it.
2. For each registered key, in registration order:
   - **missing** → write the default with its comment (`set` + `setComments`) and use the default;
   - **wrong type or out of range** → log `config.yml: <path> must be …; using <default>`, use
     the default, and **leave the owner's text untouched**;
   - **valid** → use it.
3. If anything was added, save the file once and log `config.yml: added N new keys (…)`. Keys
   the file has but nobody registered are kept and never deleted.
4. Section headers: a `ConfigSection.comment("resources", "…")` helper for comments above a
   section that holds no value itself.
5. The snapshot is an immutable map in a `volatile` field. Readers on any region thread see the
   old or the new snapshot, never a mix (Folia).
6. `reload()` re-reads the file, adds missing keys again, swaps the snapshot, keeps old values for
   `restartOnly` keys and lists them in the result.

### One source of defaults
The bundled `plugin/src/main/resources/config.yml` is deleted: the file is generated from the
keys (defaults and comments live in Java, once). A test writes a fresh file from the engine keys
and checks it against a golden copy in `test` resources, so a change to the generated file shows
up in review.

### Tests (`test` module, on a temp folder, no server)
- A missing file is created with every key and comment.
- An old file missing two keys gains them; the owner's other values and comments are unchanged.
- A bad value logs a warning, gives the default, and is not overwritten.
- A key nobody registered stays in the file.
- `reload()` swaps reloadable values, keeps `restartOnly` ones and reports them.

## Stage 2: the engine's existing keys use the loader (done 2026-10-06)
**Result:**
- `api/config/EngineConfig` holds the 5 keys and `SPEC`. The keys are in `api`, not `common`, so
  addons can read them through `MachineEngine.config()`. Without a server, `MachineEngine.config()`
  gives the defaults.
- `MachineEnginePlugin` loads the file in `onLoad` and reads the values when it needs them, so a
  reload applies at once. `events.bukkit-bridge` is restart-only.
- `ZMenuMachineMenus` reads `menus.*` through the getter. An invalid machine type in `by-type` now
  logs a warning instead of failing the menu reload.
- `/machineengine reload config` reloads the file and the zMenu menus, and prints what changed,
  what was added, what needs a restart and what was invalid.
- `plugin/src/main/resources/config.yml` is deleted.
- `EngineConfigTest` compares a new server's file with `api/src/test/resources/config/engine-config.yml`.
  It also checks that the old bundled file loads without being rewritten.
- Paper 1.20.6 dev server:
  - an old file missing 4 keys gained them, with their comments;
  - `reload config` applied `max-machines-per-chunk: 128`;
  - it reported `bukkit-bridge` as needing a restart and an invalid `menus.default`;
  - zMenu reloaded its menus.
- `./gradlew check` passes.

**What was planned:**
1. `common`: `EngineConfig` holds the engine's keys as constants:
   `limits.max-machines-per-chunk`, `placeholders.allow-breaking`, `events.bukkit-bridge`
   (restart-only), `menus.default`, `menus.by-type`. The same paths, defaults and comments as
   today's file, so existing servers see no change.
2. `MachineEngine.config()` (api) returns `ConfigValues` for addons that want to read engine
   settings.
3. `MachineEnginePlugin` builds the `ConfigFile` in **`onLoad`**, because stage 3 builds resources
   before addons register. It drops its `maxMachinesPerChunk` and `allowBreakingPlaceholders`
   fields for `config().get(...)`; `ZMenuMachineMenus` reads `menus.*` through the getter.
4. `/machineengine reload config` (and in `reload all`) calls `reload()`, refreshes zMenu menus,
   and prints the result: changed keys, restart-only keys, invalid values.

## Stage 3: registry aliases (done 2026-10-06)
**Result:**
- **Aliases are general:** `WritableRegistry.alias(from, to)` works on every static registry, not
  only resource types, so a renamed machine type can use it later. `Registry.aliases()` lists them.
  `get` and `contains` follow them; `keys()` and iteration do not include them.
  `owner(from)` gives the addon that added the alias.
- **Checks:**
  - when registering: an alias over an entry, an entry over an alias, and a duplicate alias throw
    `DUPLICATE_KEY`; an alias pointing to itself is an `IllegalArgumentException`;
  - at startup: an alias to a missing entry or to another alias is `INVALID_ALIAS`, and an alias in
    the `machineengine` or `minecraft` namespace is `RESERVED_NAMESPACE`. Either one disables the
    alias's addon and removes its aliases.
- **Saved data and definitions follow it with no other change.** `ResourceCodecs`,
  `DefinitionCompiler` and tags look keys up through the registry, which follows aliases, and data
  is saved under the entry's own key. Every other key stored by the engine is a machine type,
  upgrade, recipe or world, and goes through the registry too.
- `/machineengine registry <name>` lists aliases after the entries.
- `AliasTest` (6 tests):
  - a tank saved holding `oldmod:juice` loads as `newmod:juice` and saves back as `newmod:juice`;
  - a definition filter that names the old key accepts the new type;
  - the error cases above.

  `./gradlew check` passes (300 tests).
- **Found in the audit, for stage 5:** two Mekanized lookups read a stored key as text instead of
  going through the registry, so they would miss aliases: `PaperEntangloporters.type(Key)` (its
  save file) and `PaperTools.dropperType` (the gauge dropper's PDC).

**What was planned:**
1. `api`: `RegistrationContext.resourceTypeAliases().register(Key oldKey, Key newKey)`. Aliases
   freeze with the static registries. Validation rejects an alias whose old key is also a real
   type, chains of aliases, and aliases to missing types.
2. `Registry.get(Key)` for resource types follows aliases. That covers saved machine data,
   in-flight buffers, kept-contents items (`ResourceCodecs`), YAML definitions and recipes
   (`DefinitionCompiler`, so owners' files naming `mekanized:energy` keep working), and tags.
3. Saving always writes the new key, so data moves over the next time a machine saves.
4. Audit every other stored resource key (grep `key().asString()` in `common` and `mekanized`:
   network buffers, handoff escrow, item PDC, Mekanized save files). Each one must decode through
   the registry, never by comparing strings.
5. Tests: data saved as `a:old` loads as `b:new` through an alias and saves back as `b:new`; a
   definition naming `a:old` compiles; a bad alias is a startup error.

## Stage 4: built-in energy, water and lava (done 2026-10-06)
**Result:**
- **New API** (`api/resource`): `Energy` and `Fluids` declare their config keys
  (`resources.energy.unit|scale`, `resources.fluid.unit|scale|bucket`, all restart-only) and their
  getters. `BuiltInResources` (internal) builds them.
- **Build order:**
  - `MachineEnginePlugin.onLoad` calls `BuiltInResources.install(config)` right after loading the
    config;
  - without a server, the first getter builds them from `MachineEngine.config()` (the defaults);
  - installing again with other settings throws, since registries cannot change while the server
    runs.
- `CoreMachineEngine` registers `machineengine:energy` and `machineengine:fluid` (categories) and
  `machineengine:energy`, `machineengine:fluid/water` and `machineengine:fluid/lava` (types),
  owned by MachineEngine.
- **No world-fluid change in `CraftEngineMachineHost`:** its water/lava table maps world fluid keys
  to block materials, not resource types, so it is not a copy of Mekanized's and stays as it is.
  `Fluids.worldFluid` / `fromWorld` replace Mekanized's table in stage 5.
- New warning `FLUID_OUTSIDE_CATEGORY`: a `*:fluid/*` type outside the global category.
- `example.yml` uses `machineengine:energy` and declares `custom:fluid/oil` in the global
  category. A test checks that it compiles with no warnings.
- **Moved forward from stage 5:** Mekanized's `FLUID` is now `Fluids.category()`, and Mekanized no
  longer registers its own fluid category. Without this, the new warning fails Mekanized's
  "definitions load cleanly" test. Its water and lava are still `mekanized:fluid/*` until stage 5.
- **Tests:**
  - `BuiltInResourcesTest` (6): registration, units from config, install conflict, ports by type
    and by category including an addon's fluid, the warning, world fluids, `example.yml`;
  - the reference `config.yml` is updated. The old-bundled-file test now checks that the file gains
    the `resources` keys and keeps every line the owner wrote. Bukkit may re-indent a trailing
    comment when it saves; that is accepted.

  `./gradlew check` passes (306 tests).
- **Paper 1.20.6 and 26.3 dev servers:** `config.yml` gained the 5 `resources` keys, the registries
  list the built-ins, and there are no new warnings or errors.

**What was planned:**
### Config keys (restart-only), declared in the API next to the resources they configure
```yaml
# Resources MachineEngine provides to every addon and definition. Restart to apply.
resources:
  energy:
    unit: J        # shown after energy amounts
    scale: 0       # decimal digits: with 3, 1500 is shown as 1.500
  fluid:
    unit: mB
    scale: 0
    bucket: 1000   # amount of one bucket (water/lava buckets, pumps)
```

### API (`fr.robie.machineengine.api.resource`, beside `Items`)
```java
public final class Energy {
    public static final Key KEY = MachineEngineKeys.key("energy");
    public static final ConfigKey<String>  UNIT  = ConfigKey.string("resources.energy.unit", "J").restartOnly()...;
    public static final ConfigKey<Integer> SCALE = ConfigKey.integer("resources.energy.scale", 0, 0, 18).restartOnly()...;

    public static ResourceCategory category();   // getters: valid once the engine has built them
    public static ResourceType type();
    public static ResourcePredicate is();         // ResourcePredicate.type(type())
}

public final class Fluids {
    public static final Key CATEGORY_KEY = MachineEngineKeys.key("fluid");
    public static final ConfigKey<String>  UNIT, SCALE; public static final ConfigKey<Long> BUCKET;

    public static ResourceCategory category();
    public static ResourceType water();
    public static ResourceType lava();
    public static long bucket();
    public static ResourcePredicate any();        // ResourcePredicate.category(category())

    /** A fluid type in the global category, for addons: Fluids.create(Key.key("mekanized", "fluid/brine")). */
    public static ResourceType create(Key key);

    /** The one water/lava ↔ minecraft:water/lava table. */
    public static @Nullable Key worldFluid(ResourceType type);
    public static @Nullable ResourceType fromWorld(Key worldFluid);
}
```
**How "set when we build it" works:**
1. `MachineEnginePlugin.onLoad` loads the config (stage 2) and calls
   `BuiltInResources.build(config)` in `common`. That reads `Energy.UNIT`, `Energy.SCALE`,
   `Fluids.UNIT`, … through the getter and builds each category with
   `ResourceCategory.builder(key).unit(config.get(UNIT)).scale(config.get(SCALE))`.
2. The built objects are stored once, so `Energy.type()`, `Fluids.water()` and the others return
   them. A second `build` with the same values is a no-op; with different values it is an error,
   since registries cannot change while the server runs.
3. Calling a getter before `build` throws `IllegalStateException("MachineEngine has not built its
   resources yet")`, which catches class-initialisation order mistakes early. Paper runs
   MachineEngine's `onLoad` before its dependents', so addons' `onLoad` and static fields are
   safe.
4. `CoreMachineEngine` registers them beside `Items` (`:130-132`). Headless tests build them from
   `ConfigFile.defaults(...)`.
5. `CraftEngineMachineHost` replaces its water/lava table (`:527-538`) with `Fluids.worldFluid` /
   `fromWorld`.
6. Category display: `unit()` and `scale()` stay plain getters on `ResourceCategory`. Formatting
   amounts with them is a later plan (see "Not in this plan").

### YAML definitions
- `example.yml` uses `machineengine:energy` instead of declaring `custom:energy` and `custom:fe`.
  Keep a short commented example of declaring a custom category.
- A definition can declare a fluid in the global category:
  `resource-types: { custom:fluid/oil: { category: machineengine:fluid } }`. It then flows
  through any pipe or port that accepts `{ category: machineengine:fluid }`.

### Validation ("well registered" fluids)
At startup, warn about a resource type whose key path starts with `fluid/` but whose category is
not `machineengine:fluid`. Pipes would ignore it, which is almost always a mistake.

### Tests
- The built-ins are registered with the configured unit and scale (a config with
  `unit: FE, scale: 3` gives a category with `FE` and 3).
- A YAML machine with an energy tank (`filter: { type: machineengine:energy }`) runs a recipe.
- A port filtered on the fluid category accepts water, lava and an addon fluid created with
  `Fluids.create`.
- `worldFluid` / `fromWorld` round-trip.

## Stage 5: Mekanized uses the built-ins (done 2026-10-06)
**Result:**
- `MekanizedContent`:
  - `ENERGY`/`ENERGY_CATEGORY` are `Energy.type()`/`category()`, so the call sites are unchanged;
  - `fluid("water"|"lava")` gives the engine's;
  - its own fluids are made with `Fluids.create(mekanized:fluid/<name>)`;
  - it no longer registers an energy category or type.
- **Aliases:** `mekanized:energy`, `mekanized:fluid/water` and `mekanized:fluid/lava` (resource
  types), and `mekanized:process_cost/energy` (modifier type).
- **Not in the plan:** the energy upgrade's cost modifier is keyed by the energy category, so it is
  now `machineengine:process_cost/energy`. That key is in a reserved namespace, so
  `CoreMachineEngine` registers `processCost(Energy.category())` and Mekanized no longer does.
- **Pump and plenisher** use `Fluids.fromWorld` / `worldFluid` and `Fluids.bucket()`.
  `PaperResourceNames` gives Mekanism lang keys only to Mekanized's own fluids; water and lava use
  Minecraft's.
- `PaperEntangloporters` and the gauge dropper (`PaperTools`) look stored keys up in the registry,
  so old saves follow the aliases.
- **Generator (`buildSrc`):** writes `machineengine:energy` and `machineengine:fluid/water|lava`, and
  `fluids.txt` no longer lists water and lava. The generated output differs only in those keys:
  1 339 energy, 26 water and 2 lava lines, plus the two `fluids.txt` lines. Bucket recipes stay at
  1000 mB: they are generated YAML, not tied to `resources.fluid.bucket`.
- **Tests:**
  - `ChemistryChainTest` and `GaugeDropperTest` use `Fluids.water()` instead of the string
    `"fluid/water"`;
  - new `EngineResourcesTest` (3): Mekanized uses the engine's resources, the old keys are aliases,
    and data saved as `mekanized:energy` loads and saves back as `machineengine:energy`.

  `./gradlew check` passes (309 tests).
- **Paper 1.20.6 dev server**, on the existing world (saved with the old keys):
  - all 12 Mekanized machines loaded, none as a placeholder;
  - the 5 placeholders are the removed `example` addon's, as before;
  - 0 startup errors and 0 warnings;
  - the unedited definition files were replaced with the new keys;
  - `mekanized test`: "2 osmium dust (expected 2), 10000 J left (expected 10000)".

  The jansi crash at shutdown happens after "All chunks are saved" and is an old, known console
  crash (`hs_err` files from Oct 1–2).

**What was planned:**
1. `MekanizedContent`:
   - `ENERGY` and `ENERGY_CATEGORY` become `Energy.type()` and `Energy.category()`. Keeping the
     field names avoids touching the ~114 call sites; replacing them is a separate mechanical
     commit if wanted;
   - `FLUID` becomes `Fluids.category()`;
   - the `fluids.txt` loop creates each fluid with `Fluids.create(key("fluid/" + name))` and
     **skips `water` and `lava`**; `fluid("water")` and `fluid("lava")` return `Fluids.water()`
     and `Fluids.lava()`;
   - `resourceType(String)` and `displayName(...)` handle both namespaces.
2. Aliases (stage 3): `mekanized:energy → machineengine:energy`, `mekanized:fluid/water →
   machineengine:fluid/water`, `mekanized:fluid/lava → machineengine:fluid/lava`.
3. `UtilityBehaviors.java:95-100` (pump, plenisher) uses `Fluids.worldFluid` / `fromWorld`.
4. Bucket amounts in Mekanized use `Fluids.bucket()` instead of a literal `1000`.
5. `buildSrc` (`Recipes.kt`, `Assets.kt`, `MekanismSource.kt`) emits `machineengine:energy` and
   `machineengine:fluid/water|lava`, and drops water and lava from the generated `fluids.txt`.
   Diff `build/generated/mekanized` before and after: only those keys may change.
6. Mekanized's own `ENERGY_CATEGORY` unit (`J`) disappears; its config.yml comments that say
   "Joules" and "mB" stay true while the engine defaults are `J` and `mB`.
7. Heat and chemicals stay Mekanized's (not fluids, no change).
8. `PaperEntangloporters.type(Key)` and `PaperTools.dropperType` look the stored key up in the
   resource type registry (`MachineEngine.get().registries()`), so saved `mekanized:fluid/water`
   and `mekanized:energy` follow the aliases (found in stage 3).
9. Tests that name `"fluid/water"` as a string (`ChemistryChainTest`, `GaugeDropperTest`, …) use
   `Fluids.water()` instead.

## Stage 6: docs and verification (done 2026-10-06)
**Result:**
- `ARCHITECTURE.md`:
  - **D29**, the built-in energy and fluids and registry aliases. It narrows §4 and §33: the engine
    ships these meanings but requires none of them;
  - **D30**, settings are `ConfigKey`s and `config.yml` is generated;
  - §4 notes the exception, §7.1 describes aliases, §33 notes that D29 narrows it;
  - new §9.5 (built-in energy and fluids) and new §23.5 (the configuration file and its keys).
- README: a "Configuration" section (generated file, missing keys added, reload, the shared
  resources) and `reload config` in the command list.
- `MEKANIZED_GUIDE.md`: energy and fluids are MachineEngine's; the old keys still work.
  `MEKANIZED_PLAN.md`: a note that its phases use the old names.
- **Dev servers, each on its existing world:**

  | Server | `mekanized test` | Startup | Placeholders | `config.yml` |
  |---|---|---|---|---|
  | Paper 26.3 | 2 osmium dust, 10000 J (as expected) | 0 errors, 0 warnings | 2, all from the removed `example` addon | — |
  | Folia 1.20.6 | 2 osmium dust, 10000 J (as expected) | 0 errors, 0 warnings | 0 | gained its 8 missing settings |
  | Folia 26.2 | 2 osmium dust, 10000 J (as expected) | 0 errors, 0 warnings | 8, all `example:*`; the other 1 010 machines loaded | gained its 8 missing settings |

  Paper 1.20.6 was checked in stage 5.

**What was planned:**
- `ARCHITECTURE.md`: D29 (the decisions above); §9 lists the built-in resources; a new section
  on the config loader with the rule "a new setting is a `ConfigKey`, never a raw `getConfig()`
  or a constant". README: the config file and the built-ins.
- `MEKANIZED_GUIDE.md` / `MEKANIZED_PLAN.md`: the engine owns energy, water and lava.
- Dev servers (Paper 1.20.6, 26.3, Folia): start a world saved **before** stage 5 (a full energy
  cube, a tank of water, a pump, a kept-contents item), upgrade, check every amount, restart once
  more and confirm the saved data now names `machineengine:*`. Delete a key from config.yml, start
  again, and check it comes back with its comment.

## Optional stage 7: Mekanized config on the same loader
`MekanizedSettings` and `GearSettingsLoader` become a list of `ConfigKey`s on
`ConfigFile.of(plugin, "config.yml", keys)`. Its config file then also gains missing keys, and can
reload later. Separate commits, no behaviour change.

## Not in this plan (later, using the loader)
- Amount formatting that uses `unit()`/`scale()` (placeholders, lore, debug view): today every
  amount is a raw number.
- Bars, percent and time formats; labels in `lang.yml` (D23).
- Timings and limits that are constants today: handoff backoff, menu refresh, catch-up cap,
  kept-contents lore lines, debug view, `debug.logging`.

## Order
1 → 2 → 3 → 4 → 5 → 6, then optional 7. Stage 3 does not depend on 1–2 and can run in parallel.
