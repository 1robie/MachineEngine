# MachineEngine — Master Architecture & Implementation Specification

> Revision 2 — 2026-10-01.
> Supersedes `MachineEngine_Master_Architecture_Prompt.md` (revision 1).
> Revision 2 records binding design decisions, integrates findings from the CraftEngine `dev` branch (26.10-SNAPSHOT), and resolves the open questions of revision 1.

## 0. How to Use This Document

This document is the single source of truth for MachineEngine's architecture.

- **MUST / MUST NOT** are binding. **SHOULD** is the default unless a documented reason exists. **MAY** is optional.
- §1 (Decision Log) overrides any other section if they ever disagree.
- Items marked **[VERIFY]** depend on CraftEngine behavior that was not fully confirmed in source; the implementer MUST check them against the pinned CraftEngine snapshot before relying on them, and update this document.
- Changing a decision requires a new entry in §1 with a date and rationale. Do not silently diverge.

---

# 1. Decision Log

| ID | Decision | Rationale |
|----|----------|-----------|
| D1 | **Cross-region transfers use asynchronous handoff with escrow.** Same-thread transfers are synchronous transactions. | Folia region threads cannot share a synchronous transaction. See §16. |
| D2 | **Machine state is persisted in the CraftEngine block-entity data** (`BlockEntityController.saveCustomData/loadCustomData`). Chunk/world `PersistentDataContainer` is used only for data not owned by a single machine block (e.g. network metadata, world-level ID allocators). | CraftEngine stores custom block states and their block entities together in its own region storage. Co-locating machine state with the block avoids block/data desync on crash. (Revision 1 tentatively chose chunk PDC; changed after reviewing CraftEngine's storage model.) |
| D3 | **Missing content is kept as opaque placeholders**, never deleted: missing machine types, resource types, components and codecs. | Uninstalling an addon must not destroy world data. See §21. |
| D4 | **Unloaded machines are frozen in time by default** (no catch-up). Machine types MAY opt in to a bounded, closed-form catch-up. Networks never catch up. Chunks are never loaded by MachineEngine to process or transfer. | Matches vanilla expectations, keeps simulation deterministic, prevents unloaded-chunk exploits and load-time lag spikes. See §18. |
| D5 | **Two registry kinds:** *static* registries (types, codecs, categories) freeze after registration; *reloadable* registries (recipes, data-driven definitions) are swapped atomically as validated immutable snapshots. | Types must be stable for the server lifetime; content like recipes must be reloadable. |
| D6 | **Identity uses Adventure `net.kyori.adventure.key.Key`** in the public API. CraftEngine's own `net.momirealms.craftengine.core.util.Key` appears only inside the CraftEngine adapter. | Paper ships Adventure; `adventure-key` is a tiny, server-free artifact usable in pure unit tests. |
| D7 | **CraftEngine is a hard runtime requirement** of the MachineEngine plugin. MachineEngine builds against the **latest CraftEngine dev snapshot** (currently `26.10-SNAPSHOT`). The `api` and `core` modules still MUST NOT depend on CraftEngine. | Product decision. The adapter boundary is preserved for testability and to contain CraftEngine API churn. |
| D8 | **Paper only** (no Spigot). Folia compatibility is designed in from day one and validated in milestone M4. | CraftEngine is Folia-capable; the threading model must not need a rewrite later. |
| D9 | **Resource hierarchy is not generic.** `ResourceCategory`, `ResourceType`, `ResourceStack` carry runtime identity (`Key`), not Java type parameters. | Generics are erased; they would give false safety. Runtime identity is the contract (§6). |
| D10 | **Amounts are `long`** in category-defined base units. Fractional quantities use fixed-point scaling declared by the category. No floating point in transfers. | Determinism, no rounding drift, no overflow surprises (saturating math). |
| D11 | **Transactions follow a nested snapshot/rollback model** (inspired by the Fabric Transfer API). `simulate` = open transaction, then abort. | Proven model; simpler and safer than reserve/commit bookkeeping. |
| D12 | **MachineEngine has its own event bus** in the API module; it does not use Bukkit events internally. No per-transfer events in hot paths. | Core must be server-free and fast. |
| D13 | **Addons register through a callback submitted in their `onLoad`** (or Paper bootstrap). MachineEngine runs callbacks in dependency order during its own enable at `STARTUP` and then freezes static registries — before worlds load. | All plugins' `onLoad` run before any `STARTUP` enable; worlds load after `STARTUP` enables. |
| D14 | **CraftEngine block definitions reference machine types lazily by key.** The `machineengine:machine` block behavior stores the machine-type `Key` at config-parse time and binds it after MachineEngine's registry freeze (and again after every CraftEngine reload). | CraftEngine parses block configs during its own enable, before MachineEngine's addons have registered; it also re-parses on `/ce reload`. |
| D15 | **Inputs of a process are consumed atomically when the process starts** and held in a persisted in-flight buffer; cancelling a process returns them. | No resource is ever silently lost (§10). |
| D16 | **Modifier stacking follows Minecraft attribute semantics:** `base → +ADD → ×(1+ΣADD_MULTIPLIED_BASE) → ×Π(1+MULTIPLY_TOTAL) → clamp`. | Familiar to the ecosystem and order-independent within each stage. |
| D17 | **License: GPL-3.0.** | Project owner decision (2026-10-01); compatible with CraftEngine (GPL-3.0). |
| D18 | **Java package root `fr.robie.machineengine`**, Maven group `fr.robie`. | Project owner decision (2026-10-01). |
| D19 | **Supported Minecraft versions: 1.20.6 to 26.3.** Compile against the 1.20.6 Paper API; test on both ends of the range. | Project owner decision (2026-10-01). 1.20.6 is the first version with item data components and Paper's lifecycle command API, both of which MachineEngine relies on. |

---

# 2. Technical Baseline

| Item | Value |
|------|-------|
| Server platform | Paper (and Folia from M4). Spigot unsupported. |
| Minecraft version | **1.20.6 – 26.3** (D19). 26.3 matches CraftEngine `dev`'s `latest_supported_version`. |
| Paper API (compile) | Compile against the **1.20.6** Paper API so no newer-only API is used by accident. Features needing newer APIs go behind a version check in `machineengine-paper`; none are allowed in `api`/`core`. |
| Runtime test matrix | Smoke tests on Paper 1.20.6 and on the latest supported 26.3 build (and Folia equivalents from M4). |
| Java | Bytecode `--release 21` (required by 1.20.6 and the same floor as CraftEngine); build toolchain JDK 25. |
| Build | Gradle (Kotlin DSL), multi-module. `xyz.jpenilla.run-paper` for dev servers, `net.minecrell.plugin-yml.paper` for `paper-plugin.yml`, Shadow for the plugin jar. |
| CraftEngine repository | `https://repo.momirealms.net/snapshots/` (dev builds) — releases at `https://repo.momirealms.net/releases/`. |
| CraftEngine artifacts | `net.momirealms:craft-engine-core:26.10-SNAPSHOT` and `net.momirealms:craft-engine-bukkit:26.10-SNAPSHOT`, both `compileOnly`, used only in `machineengine-craftengine`. |
| CraftEngine version policy | Track the newest dev snapshot. The version is a single property in `gradle.properties` (`craftengine_version`). CI runs one job against the pinned snapshot and one against the newest snapshot so breakage is detected early. |
| Annotations | `org.jetbrains:annotations` (`@ApiStatus.*`, `@Nullable`, `@NotNull`). |
| API dependency footprint | `machineengine-api`: `net.kyori:adventure-key` + JetBrains annotations only. |
| License | GPL-3.0 (D17), same as CraftEngine. |

---

# 3. Project Goal

Design and implement **MachineEngine**, a server-side Minecraft machine and infrastructure framework, inspired by the extensibility philosophy of NeoForge, implemented as a Paper plugin, and integrated with CraftEngine for custom blocks.

MachineEngine provides infrastructure for: machines, resources, resource storage, resource transfer, ports, capabilities, recipes, processes, networks, transports, upgrades and modifiers, persistence, scheduling, events, developer APIs, validation, and debugging/observability.

Third-party plugins (addons) MUST be able to add completely new resource systems and machine systems without modifying MachineEngine source code. No client-side mod is required.

---

# 4. Core Architectural Principle

> **MachineEngine provides mechanisms, not meanings.**

MachineEngine owns the infrastructure to register, store, transfer, process, network, persist, schedule and execute resources and machines. Addons define what those resources and machines mean.

The core MUST NOT assume a resource is FE, EU, mana, fluid, item, gas, heat, steam, water, oil, electricity or magic. These are examples of resource systems that addons may define. Even first-party resource systems (e.g. the Paper item category, §23.4) are registered through the same public SPI as any addon.

---

# 5. Modules and API Boundaries

```text
machineengine-api          Public API + SPI. Pure Java. Depends on adventure-key only.
machineengine-core         Implementation of the API. Pure Java. No Paper, no CraftEngine.
machineengine-test         Headless test harness (fake worlds, fake regions, fake clock,
                           in-memory DataView). Published so addon authors can test too.
machineengine-paper        Paper integration: bootstrap, lifecycle, commands, scheduler bridge,
                           PDC, item resource category, protection, vanilla interop.
machineengine-craftengine  CraftEngine adapter: block behavior, block-entity controller,
                           ticker, persistence bridge, Key conversion. The ONLY module
                           that imports CraftEngine classes.
machineengine-plugin       Shaded plugin jar = paper + craftengine + core + api.
```

Future modules: `machineengine-network`, `machineengine-data` (YAML), `machineengine-gui`, and official content plugins (Energy, Logistics, Chemistry, Industry, Utilities) as separate addons.

Dependency direction (MUST be enforced by Gradle, no cycles):

```text
api ← core ← paper ← craftengine ← plugin
 ↑      ↑
 └──────┴── test
```

### API stability tiers

| Tier | Marking | Guarantee |
|------|---------|-----------|
| Public API | default in `machineengine-api` | Binary compatible within a major version. |
| SPI (implement-me interfaces) | `@ApiStatus.OverrideOnly` | Same as public API; new methods only with defaults. |
| Experimental | `@ApiStatus.Experimental` | May change in any minor release. |
| Internal | `@ApiStatus.Internal` or outside `api` module | No guarantee. Addons MUST NOT use it. |

Non-extendable API types are marked `@ApiStatus.NonExtendable`. Implementation classes live in `core` and are never returned with their concrete type.

### API versioning

- One semantic version for the API (`MachineEngineApi.VERSION`). Pre-1.0 (`0.x`): breaking changes allowed in minor versions, documented in `CHANGELOG.md`.
- Addons declare the API range they require when registering (§8.3). Mismatch → clear startup error naming the addon.
- CI runs `japicmp` (or equivalent) against the last released API jar; a binary break without a major bump fails the build.
- Persistence formats are versioned separately (§20.3).

---

# 6. Identity and Keys

- All IDs are Adventure `Key`s (`namespace:value`). Namespace rules follow Adventure (`[a-z0-9_.-]`). MachineEngine's own namespace is `machineengine`.
- Examples: `machineengine:machine` (CE block behavior type), `energyplugin:fe`, `mymod:steam`, `mymod:refinery`.
- **Naming:** `RegistryKey<T>` = a typed key of an entry in a registry (`registry key + entry key`). This avoids confusion with the "resource" concept (revision 1 used `ResourceKey` for both).
- Runtime identity checks use `Key` equality plus reference identity of the registered object. Hot paths compare registered objects by reference (they are canonical singletons after freeze), never by string.
- Each registered object exposes its `Key` and its owning addon (`AddonId`) for diagnostics.

---

# 7. Registries

### 7.1 Kinds

```java
interface Registry<T> {                 // read view, available after freeze
    RegistryKey<Registry<T>> key();
    Optional<T> get(Key id);
    T getOrThrow(Key id);
    boolean contains(Key id);
    Optional<Key> keyOf(T value);
    Stream<T> stream(); Set<Key> keys();
    Optional<AddonId> owner(Key id);
}

interface StaticRegistry<T> extends Registry<T> {}          // frozen after registration
interface ReloadableRegistry<T> extends Registry<T> {       // snapshot-based
    long generation();                                       // increments on every swap
}
```

- Registration happens only through a `RegistrationContext` handed to the addon's callback (§8). There is no global mutable `register(...)` after freeze; calling registration methods after freeze throws `RegistryFrozenException` with the addon name.
- Duplicate key → validation error naming both owners.
- Values MUST be immutable definitions.
- New registries can be created by addons (`ctx.createRegistry(...)`), with the same lifecycle rules.

### 7.2 Built-in registries

Static: `ResourceCategory`, `ResourceType`, `ResourceComponentType`, `CapabilityType`, `MachineType`, `ProcessType`, `RecipeType`, `NetworkType`, `TransportType`, `UpgradeType`, `ModifierType`, `StorageCodec` (as `VersionedCodec`), `ResourceConverter`, `EventType`.

Reloadable: `Recipe`, `ResourceTagContribution` (tag contents, §9.3), data-driven definitions (future YAML, §29).

Implemented in M1: resource category/type/component type, converter, storage codec, event type (static) and resource tag (reloadable). The machine-related registries arrive with M2.

### 7.3 Reloadable registry semantics

1. A reload builds a complete new snapshot off to the side.
2. The snapshot is fully validated (§24). If validation fails, the reload is rejected and the old snapshot stays live.
3. The swap is atomic (single volatile reference). Readers see either the old or the new snapshot, never a mix.
4. **Initial load vs. reload.** At startup there is no previous snapshot, so a provider whose addon causes errors is left out and the others load. On a later reload, any error rejects the whole reload.
5. Runtime objects hold `Key` references and re-resolve when the `generation` changes. If a referenced entry disappeared, the dependent object degrades safely and an error is recorded on the machine. Exception, decided in M2: a **running process captures its outputs, per-tick costs and duration when it starts**, so a recipe reload never changes or cancels a process already running; only processes started afterwards see the new recipes.

---

# 8. Lifecycle, Load Order and Addon Registration

### 8.1 Plugin descriptor

MachineEngine is a Paper plugin (`paper-plugin.yml`):

- `load: STARTUP` (MUST: static registries must be frozen before worlds load, and CraftEngine itself is `STARTUP`).
- `dependencies.server.CraftEngine: { required: true, load: BEFORE, join-classpath: true }`.
- `folia-supported: true` once M4 passes; until then `false`.

Addons declare `MachineEngine` as a required server dependency with `load: BEFORE` and `join-classpath: true`. Paper's dependency system handles presence and load order; MachineEngine does not re-implement it.

### 8.2 Phases

```text
[Paper bootstrap]   MachineEngine bootstrapper: create service locator, logging.
[All onLoad]        CraftEngine.onLoad, MachineEngine.onLoad, Addon.onLoad …
                    MachineEngine.onLoad registers the CE block behavior type
                    `machineengine:machine` (CE registry is still open here).
                    Addons call MachineEngine.registration(...) to SUBMIT callbacks.
[STARTUP enable]    CraftEngine.onEnable → parses block configs (machine-type keys stored
                    unresolved, D14).
                    MachineEngine.onEnable:
                      1. Registry creation (built-ins)
                      2. Addon dependency resolution (API range, inter-addon requires)
                      3. Run registration callbacks in topological order
                      4. Validation (whole registry graph)
                      5. Freeze static registries
                      6. Build first reloadable snapshots (recipes …)
                      7. Bind CE block definitions to machine types (D14)
[World load]        Worlds load; CE loads chunks and block entities → MachineEngine
                    controllers decode machine state (Machine loading).
[POSTWORLD enable]  Other plugins enable (addon onEnable may use the frozen API).
[Runtime]           Ticking, transfers, reloads of reloadable registries, CE reloads
                    trigger re-binding (D14).
[Shutdown]          Stop scheduling, flush dirty machine state, mark CE chunks unsaved,
                    let CE save.
```

If validation fails at step 4, MachineEngine logs a full diagnostic report (§24), and **disables the offending addons' content**, not the whole framework. Blocks of disabled content load as placeholders (D3).

### 8.3 Addon registration API (sketch)

```java
public final class RefineryAddon extends JavaPlugin {
    @Override public void onLoad() {
        MachineEngine.registration(this, Registration.builder()
            .requiresApi("[0.1,1.0)")
            .requires("energyplugin", "[2.0,)")       // registration ordering + version check
            .register(ctx -> {
                ResourceCategory fluid = ctx.resourceCategories().register(key("mymod:fluid"),
                        ResourceCategory.builder().baseUnit("mB").build());
                ctx.resourceTypes().register(key("mymod:oil"), ResourceType.of(fluid));
                ctx.machineTypes().register(key("mymod:refinery"), RefineryMachine.TYPE);
            })
            .build());
    }
}
```

- `MachineEngine.registration(...)` and `MachineEngine.api()` are the only static entry points; they delegate to a single instance set once at bootstrap (the only permitted static mutable state, documented as such). They belong to the Paper integration (M3). In M1 the engine is started with `MachineEngineBuilder` (core) or `TestEngine` (test harness), both taking `AddonRegistration`s.
- Implemented API (M1): `AddonRegistration.builder(name, version).requiresApi(range).requires(addon, range).after(addon).register(ctx -> …)`. Definitions carry their own key (`ResourceType.of(key, category)`) and are registered with `ctx.resourceTypes().register(type)`.
- Ties in the dependency order are broken by addon name, so the order does not depend on plugin load order.
- Optional ordering (`after`) does not disable an addon when the other one is missing.
- Dependency errors (missing, incompatible version, cycle) are reported per addon with the full chain.

---

# 9. Resource System

### 9.1 Hierarchy

```text
ResourceCategory      e.g. energyplugin:energy, mymod:fluid, machineengine:item
  └── ResourceType    e.g. energyplugin:fe, mymod:oil
        └── ResourceVariant = (ResourceType, ResourceComponents)   what it is
              └── ResourceStack = (ResourceVariant, long amount)     how much
```

- `ResourceVariant` is the unit storages and transfers work with: two equal variants always stack.

- `ResourceCategory` defines: base unit name, fixed-point display scale (D10), and its `MergePolicy`. (Allowed component types and a display formatter hook are deferred until a use case needs them.)
- `ResourceType` belongs to exactly one category and carries default components. Tag membership lives in the reloadable tag registry, not on the type.
- `ResourceStack` is an immutable value object. Storages SHOULD NOT hold `ResourceStack` objects internally in hot paths; they store `(type, components, amount)` in primitive-friendly structures and materialize stacks on demand.
- Amount math is saturating (`Math.addExact` guarded); overflow is a validation/transfer rejection, never a wrap-around.

### 9.2 Components (metadata)

Revision 1's "metadata" is modeled as typed components, similar to Minecraft data components:

```java
ResourceComponentType<T>   registered, has Key + StorageCodec<T> + equality semantics
ResourceComponents         immutable map ResourceComponentType<?> → value
```

Examples: `mymod:temperature` (long, milli-Kelvin), `mymod:pressure`, `magicplugin:purity`.

- Two stacks are **stackable** iff same type and their components are equal under the category's `MergePolicy`.
- `MergePolicy` is category-defined. Default: `STRICT` (all components equal). Categories MAY provide a custom merger, e.g. steam averaging temperature weighted by amount. Merging MUST be deterministic and integer-based.

### 9.3 Matching, predicates, tags

```java
ResourcePredicate         (ResourceVariant, ResourceTags) → boolean, composable (and/or/negate)
ResourceMatcher           ResourcePredicate + optional amount requirement; used by ports, recipes
ResourceTagContribution   adds ResourceTypes to a tag Key; reloadable; several addons may
                          contribute to the same tag, each with its own contribution key
ResourceTags              snapshot of tag → members, rebuilt on every tag reload
```

Predicates take the tag snapshot as an argument, so they stay valid across reloads. `predicate.bind(tags)` turns one into a plain `Predicate<ResourceVariant>` for storage filters.

Built-in predicate builders: by type, by category, by tag, by component (equals / range). Example:

```java
ResourceMatcher coolant = ResourceMatcher.of(
    ResourcePredicate.category(FLUID)
        .and(ResourcePredicate.tag(key("mymod:coolant")))
        .and(ResourcePredicate.component(TEMPERATURE, t -> t < 500_000)));
```

**Matching never implies conversion.**

### 9.4 No implicit conversion

MachineEngine MUST NEVER convert one resource into another automatically (FE→EU, water→steam, mana→FE, heat→energy …). Conversion happens only through:

- an explicitly registered `ResourceConverter` (pure, deterministic `(input stack) → (output stack, consumed amount)`), invoked by a machine, a port with an explicit converter binding, or a transport that declares it; or
- an explicit machine/process.

`ResourceConverterRegistry` lookups are by `(from type, to type)` and return all registered converters; the caller chooses. Nothing in core calls a converter on its own.

---

# 10. Storage and Transactions

### 10.1 Interfaces

```java
interface ResourceHandler {
    long insert(ResourceVariant variant, long maxAmount, TransactionContext tx);
    long extract(ResourceVariant variant, long maxAmount, TransactionContext tx);
    List<StorageView> views();                      // snapshot: (variant, amount, capacity) per slot
}
abstract class SnapshotParticipant<S>               // base for state that takes part in transactions
```

Extraction is by exact variant; filtered extraction goes through `views()` (see `Transfers.move`). The API ships `SingleVariantStorage` (one slot, merge-policy aware) and `CombinedResourceHandler` (slots, fills existing stacks first) as building blocks; they live in the API, not core, because addons construct them directly.

Storages are composable (slotted, single-type tank, filtered, capped-rate wrappers). Third parties can implement their own; the transaction contract is mandatory.

### 10.2 Transactions (D11)

```java
try (Transaction tx = engine.transactions().openOuter()) {
    long moved = Transfers.move(source, target, filter, 1000, tx).moved();
    if (moved == 1000) tx.commit();          // otherwise auto-abort on close
}
```

- Nested: `tx.openNested()`; aborting a nested transaction rolls back only its changes.
- Participants snapshot state lazily on first modification in a transaction and restore on abort.
- `simulate` = open a transaction, perform, read the result, abort. There is no separate code path, so simulation and execution cannot diverge.
- A transaction is **confined to one owning thread** (Paper main thread or one Folia region). Touching a participant owned by another thread inside a transaction throws `WrongThreadException` (debug) / is rejected (production). Cross-region movement uses handoff (§16).
- Commit callbacks (`tx.onCommit(...)`) are used for side effects such as wake-ups, events and dirty marking; they never run on abort.
- Multi-resource operations (consume 100 FE + 10 water + 2 iron, produce 1 steel) are one transaction: if any step fails, everything aborts.

### 10.3 Results

```java
record TransferResult(Status status, long requested, long moved, @Nullable MachineError reason) {
    enum Status { COMPLETE, PARTIAL, REJECTED }
}
```

Simulation is a mode, not an outcome (revision 1's `SIMULATED`/`ROLLED_BACK` statuses are removed).

**Invariant (tested by property tests):** for any sequence of inserts/extracts/aborts/commits, the total amount of each resource type across all participants changes only by explicit creation/destruction calls (process outputs, voiding ports), never by transfers.

---

# 11. Capabilities and Ports

### 11.1 Capabilities

```java
CapabilityType<T>      registered, Key-identified, carries the Java interface T
CapabilityProvider     machine-side: <T> Optional<T> getCapability(CapabilityType<T>, @Nullable Side)
```

- Lookups are by `CapabilityType` reference (O(1) array index assigned at freeze), never by class scanning.
- Addons define new capability types freely (e.g. `magicplugin:mana_link`).
- Capabilities have a lifecycle: valid while the machine is loaded; holders receive an invalidation callback on unload/remove; caching a capability past invalidation is a bug detected in debug mode.
- Built-in capability types are infrastructure-only: `machineengine:resource_handler`, `machineengine:redstone_control`, `machineengine:comparator_output`, `machineengine:inspectable`.

### 11.2 Ports

A **port is a configured exposure of a capability on a set of block faces**:

```text
Port
 ├── id (Key, unique within the machine type)
 ├── capability (e.g. resource_handler backed by one of the machine's storages)
 ├── direction: INPUT | OUTPUT | INPUT_OUTPUT | DISABLED
 ├── sides: set of relative faces (FRONT/BACK/LEFT/RIGHT/UP/DOWN), resolved via block facing
 ├── filter: ResourceMatcher
 ├── rate limit (per tick) and capacity view
 └── connection rules (which transports/networks may attach)
```

- Sides are relative to the machine's facing; the CraftEngine adapter maps the block-state facing property to absolute directions (§22.4).
- Port configuration defaults come from the `MachineType`; per-instance overrides (player side configuration) are part of `MachineState`.
- Ports are resource-agnostic.

---

# 12. Machines: Definitions vs Instances

```text
MachineType        immutable definition: key, ports, storages layout, capabilities,
                   behavior factory, scheduling hints, catch-up policy, break policy, codecs
MachineInstance    one machine in the world (runtime object, owned by one thread)
MachineState       persistent mutable state (storages, process, config, upgrades, custom data)
MachineContext     what the behavior may touch: own state, own storages, clock, scheduler
                   handle, neighbor capability lookup, event bus, diagnostics
MachineBehavior    addon-provided logic (SPI): onLoad, onTick, onWake(reason), onUnload,
                   onRemove, catchUp (optional)
MachineController  framework-owned glue between a world host (CE block entity) and the
                   instance; addons never implement it
```

- Definitions are immutable after freeze; runtime state is mutable and persistent.
- The same principle applies throughout: `RecipeType/Recipe`, `ProcessType/ProcessInstance`, `NetworkType/Network`, `TransportType/Transport`, `UpgradeType/UpgradeInstance`.
- `MachineBehavior` MUST NOT access the Bukkit/CraftEngine world directly in core logic. World effects (particles, block state changes, sounds) go through `MachineContext.world()` effects, which are queued and applied by the host on the owning thread (§17).
- Machines are not required to use recipes, processes or networks.

---

# 13. Recipes and Processes

- `Recipe` describes a transformation (inputs as `ResourceMatcher`s, outputs as stack templates, optional conditions). It does not define execution.
- `RecipeType` defines recipe shape and matching; `Recipe`s live in a **reloadable** registry (D5).
- `RecipeMatcher` finds candidate recipes for a machine's inputs; machines SHOULD cache the last matched recipe and re-match only on input change.
- `ProcessType` defines execution (duration, per-tick or up-front resource costs, parallelism). `ProcessInstance` is persisted in `MachineState` with progress, recipe key, and the **in-flight input buffer** (D15).
- **M2 implementation:** one built-in timed process behind `MachineContext.process()` (`ProcessControl`: `start`, `advance`, `cancel`) for `ProcessingRecipe`s (inputs consumed at start, per-tick costs, outputs at the end), and a ready-made `ProcessingBehavior` for recipe machines. A `ProcessType` registry is deferred until a second kind of process is needed. A process that cannot pay a per-tick cost pauses (`WAITING_FOR_COST`); cancelling returns the in-flight inputs, keeping in the buffer whatever does not fit.
- Process start = one transaction that consumes inputs into the in-flight buffer. Process end = one transaction that inserts outputs; if outputs do not fit, the machine goes `BLOCKED` and the process holds its outputs until space is available. Nothing is voided implicitly.

---

# 14. Upgrades and Modifiers

```text
UpgradeType / UpgradeInstance   installed into a machine's upgrade slots
ModifierType                    Key-identified numeric target (e.g. machineengine:process_duration)
Modifier                        (ModifierType, operation, value, source)
```

- Core defines modifier types only for mechanisms core owns: `process_duration`, `process_cost`, `storage_capacity`, `transfer_rate`, `parallel_operations`. Addons add any other (e.g. range, efficiency).
- Stacking per D16; operations: `ADD`, `ADD_MULTIPLIED_BASE`, `MULTIPLY_TOTAL`. Integer/fixed-point math.
- Upgrade changes wake the machine (`WakeReason.UPGRADE_CHANGED`) and invalidate cached modifier totals.

---

# 15. Networks and Transports (post-MVP, constraints fixed now)

- Networks, transports and resources are three separate concepts. A resource does not imply a network; a transport defines how something moves, not what it means.
- `NetworkType / Network / NetworkNode / NetworkConnection`; `TransportType / Transport / TransportConnection`.
- Network state is owned by a per-world `NetworkManager`, not by machines. Machines participate through ports.
- Graph updates are incremental (split/merge on add/remove), never full rebuilds per change.
- **Partially loaded networks:** only loaded segments participate. Unloaded segments are inert; transfers to them fail with `NetworkUnavailable` and are retried on chunk load (CE `subscribeChunkLoad`). No chunk loading.
- **Folia:** a network segment is confined to one region; links between segments in different regions use the handoff protocol (§16).
- Network metadata not tied to a single block (network IDs, segment index) is stored in chunk/world PDC (D2).

---

# 16. Threading Model and Cross-Region Handoff

### 16.1 Thread categories

Every public API method is documented with one of:

| Tag | Meaning |
|-----|---------|
| `@ThreadSafe` | Callable from any thread (registries after freeze, immutable definitions, metrics reads). |
| `@OwnerThread` | Must run on the thread owning the machine/chunk (Paper main thread, or the Folia region thread). |
| `@AnyThreadAsync` | Returns a future; work is scheduled onto the correct owner. |

Debug mode asserts owner-thread rules (`Bukkit.isOwnedByCurrentRegion` on Folia, `isPrimaryThread` on Paper).

### 16.2 Simulation vs application

```text
simulation (pure, owner thread or test harness)
    ↓ result + queued WorldEffects
application (owner thread): block state updates, particles, sounds, drops
```

CraftEngine also offers async block-entity tickers (`createAsyncBlockEntityTicker`). MachineEngine MUST NOT use them in the MVP; they are reserved for a possible future pure-simulation offload.

### 16.3 Handoff protocol (D1)

Used when source and target are owned by different threads (Folia). On Paper, every machine is on the main thread, so the synchronous fast path always applies; the handoff code path is still exercised in tests with the fake multi-region scheduler.

```text
Source (owner A)                                Target (owner B)
1. tx: extract ≤ N into Escrow(transferId)
   (Escrow is a participant persisted in the
   SOURCE machine's state) → commit
2. post Deliver(transferId, stacks) ─────────▶  3. if transferId in dedupe set → ack previous result
                                                 4. tx: insert what fits → commit;
                                                    record transferId in dedupe set (persisted
                                                    with TARGET, bounded ring + TTL)
                    ◀───────────────────────────  5. post Ack(transferId, accepted, remainder)
6. tx: clear accepted from escrow; return
   remainder to source storage (or keep in
   escrow and retry/return later) → commit
```

Rules:

- **At most one in-flight handoff per route** (backpressure). A route with non-empty escrow does not start a new transfer.
- **Backoff:** a handoff answered with nothing accepted rests its route (20 ticks if the target is missing, 5 if it is full), so a source never ping-pongs messages every tick. Not persisted.
- **Removed source:** if a machine is removed while a handoff is in flight, the remainder of the answer is dropped into the world (`MachineHost.dropResources`). This record is in memory only, so a crash in that window loses the remainder.
- If the target is unloaded, removed, or rejects: ack with full remainder; source returns it.
- Escrow contents are visible in inspection and count toward the source's contents (nothing is "in the air" unaccounted).
- Messages are scheduled with Paper's `RegionScheduler` for the target location, which also works on non-Folia Paper and follows region migration.
- **Guarantees:** no loss and no duplication on every non-crash path (unload, region merge/split, removal, shutdown — shutdown drains or returns escrows before the final save). On a hard crash, duplicates are suppressed by the dedupe set; a loss window exists only if the target's chunk was not saved after the source's chunk recorded the ack. This is the same class of risk as vanilla's non-atomic multi-chunk saves and is documented, not hidden.

---

# 17. Scheduler, Sleeping and Waking

MachineEngine does not tick every machine every tick. It builds on CraftEngine's block-entity ticker infrastructure, which already keeps sleeping tickers out of the active list (`SleepingBlockEntityTicker`, `ActiveBlockEntityTickersList`).

### 17.1 States

| State | Meaning | CE ticker |
|-------|---------|-----------|
| `ACTIVE` | Has work this tick | awake |
| `SCHEDULED` | Will wake at a known game tick | sleeping + timed wake-up |
| `WAITING` | Needs input (wakes on insert / input change) | sleeping |
| `BLOCKED` | Output full or dependency missing (wakes on extract / neighbor change) | sleeping |
| `SLEEPING` | Nothing to do until an external signal | sleeping |
| `DISABLED` | Redstone-disabled, placeholder, or errored | sleeping |

### 17.2 Wake reasons

`RESOURCE_INSERTED`, `RESOURCE_EXTRACTED`, `INPUT_CHANGED`, `OUTPUT_AVAILABLE`, `NEIGHBOR_CHANGED`, `NETWORK_CHANGED`, `UPGRADE_CHANGED`, `RECIPES_RELOADED`, `CONFIG_CHANGED`, `TIMER`, `CHUNK_LOADED` (via CE `subscribeChunkLoad`), `EXTERNAL` (addon-defined `Key`).

- Wake signals are emitted from transaction commit callbacks, so aborted simulations never wake anything.
- `wake()` is `@OwnerThread`; cross-thread wakes arrive through handoff messages or the region scheduler.
- **Elapsed time:** a machine woken from `SCHEDULED` sees the ticks it slept as elapsed (planned work, e.g. a running process). One woken from `WAITING`/`BLOCKED`/`SLEEPING` sees 1 tick, because waiting is not working. Scheduling state and timers are persisted relative to the save.
- Timed wake-ups use `RegionScheduler.runDelayed`, coalesced per chunk per target tick to bound the task count. (Implementation detail to benchmark in M3; a per-world timer wheel is the fallback on non-Folia Paper.)

### 17.3 Budgets and fairness

- Configurable per-tick time budget per world/region; machines exceeding it are deferred round-robin to the next tick (no starvation).
- A slow-machine watchdog logs machine types/positions exceeding a configurable microsecond threshold.
- CraftEngine's latest dev also added stricter tick conditions (ticking only when the chunk is block-ticking); MachineEngine follows CE's chunk-ticking state and does not tick machines in non-ticking chunks.

---

# 18. Unloaded Chunks and Catch-Up (D4)

- Default `CatchUpPolicy.NONE`: a machine in an unloaded or non-ticking chunk is frozen; on load it resumes exactly where it stopped.
- Each machine persists `lastTickedGameTime` (world game time, never wall-clock).
- Opt-in `CatchUpPolicy.bounded(maxTicks)`: on load, `elapsed = min(gameTime - lastTickedGameTime, maxTicks)` and `MachineBehavior.catchUp(ctx, elapsed)` is called **once**. Requirements:
  - closed-form (no tick replay loop);
  - uses only the machine's own storages and state (no neighbors, no networks, no world access);
  - deterministic.
  A default implementation for process-based machines advances progress and completes at most as many cycles as inputs and output space allow.
- Networks never catch up. Transfers never target unloaded chunks. MachineEngine never loads a chunk.

---

# 19. Deterministic Simulation

Given machine state, inputs, configuration and game tick, the same operation produces the same result unless explicit randomness is involved.

- Randomness only through `MachineContext.random()`, a seeded per-machine source (seed derived from position + world seed + tick), so tests can reproduce runs.
- No iteration over unordered hash collections in simulation logic where order affects results; registries expose stable (registration) order.
- No floating point in resource math (D10).

---

# 20. Persistence (D2)

### 20.1 Where

- **Machine state** → CraftEngine block-entity data, written by the MachineEngine controller in `saveCustomData(CompoundTag)` and read in `loadCustomData(CompoundTag)`, under the root key `machineengine:machine`.
- **Chunk/world PDC** → only data not owned by one machine block (network metadata, world ID allocators).
- If MachineEngine itself is missing, CraftEngine keeps the block entity's raw tag through `InactiveBlockEntityController`, which re-emits it unchanged on save (verified in CE source). MachineEngine data therefore survives MachineEngine being temporarily removed.

### 20.2 Dirty tracking (critical CE finding)

CraftEngine only writes a chunk when `CEChunk.isUnsaved()` is true, and modifying block-entity data does **not** set that flag by itself. Therefore:

- Every committed change to machine state MUST mark the machine dirty, and the adapter MUST call `setUnsaved(true)` on the owning `CEChunk` (once per tick at most, coalesced).
- On `saveCustomData`, if the machine is clean, the adapter re-emits the cached encoded tag instead of re-encoding (dirty-state persistence).
- **[VERIFY]** the thread `saveCustomData` runs on (the CE world save path builds tags then encodes/writes on an async executor). If it can run off the owner thread, the controller MUST hand over an immutable pre-encoded snapshot produced on the owner thread.

### 20.3 Format and codecs

```text
machineengine:machine
 ├── format: int                       MachineEngine envelope version
 ├── type: "mymod:refinery"            machine type key
 ├── typeVersion: int                  MachineType data version (owned by the addon)
 ├── lastTick: long                    lastTickedGameTime
 ├── state: { … }                      MachineState encoded by codecs
 ├── escrow: [ … ]                     pending handoffs (§16.3)
 ├── dedupe: [ … ]                     recent incoming transfer IDs
 └── unknown: { … }                    opaque data preserved for missing content (§21)
```

- `StorageCodec<T>` encodes to a format-neutral immutable `DataValue` tree (`DataMap`, list, string, long, double, boolean, bytes). The CE adapter maps it to sparrow-NBT `CompoundTag`; the test harness uses an in-memory implementation. MachineEngine does not adopt DataFixerUpper.
- Every codec has a version. Addons register migrations `(fromVersion → toVersion)` that run on load. A missing migration is a validation error at startup, not a runtime crash.
- Persisted: machine state, storages, process state + in-flight buffer, upgrades, side configuration, custom addon state, escrow, dedupe set.

### 20.4 Machine as item

- Break policy per `MachineType`: `DROP_CONTENTS` (default) or `KEEP_CONTENTS_IN_ITEM`.
- **[VERIFY]** whether CraftEngine calls `BlockEntityController.loadCustomDataFromItem` for blocks (the hook exists; a call site was only found for furniture). If not, the Paper module stores the encoded state in the item's PDC on break and restores it on `CustomBlockPlaceEvent`.

---

# 21. Missing Content and Placeholders (D3)

| Missing | Behavior |
|---------|----------|
| MachineEngine plugin | CE `InactiveBlockEntityController` preserves raw data (§20.1). |
| Machine type (addon removed or disabled) | Controller creates a `PlaceholderMachine`: keeps the full raw tag verbatim, never ticks, exposes no capabilities, shows in inspection and `/machineengine placeholders`. Breaking it by non-admins is cancelled by default (configurable) so data is not destroyed. Admins can purge. |
| Resource type inside a known machine | Stored as `UnknownResourceStack` (raw encoded form). Its slot is locked (unusable by other resources) until the type returns or an admin purges it. |
| Component type | Raw encoded component is kept opaque and re-emitted on save; the stack is treated as non-stackable with anything else. |
| Codec / migration | The machine loads as a placeholder with an `SerializationError` diagnostic; raw data preserved. |

When the missing content returns, placeholders rehydrate on next load with no data loss.

---

# 22. CraftEngine Integration (D7, D14)

### 22.1 Layering

```text
MachineEngine API  →  MachineEngine Core  →  machineengine-craftengine (adapter)
                                               ├── MachineBlockBehavior   (CE BlockBehavior + EntityBlock + WorldlyContainerHolder)
                                               ├── MachineBlockEntityController (CE BlockEntityController)
                                               ├── MachineTicker          (wraps CE SleepingBlockEntityTicker)
                                               └── KeyAdapter, DataView↔CompoundTag, facing mapping
                                                        ↓
                                               CraftEngine block / block entity
                                                        ↓
                                               MachineInstance (core)
```

The adapter is the only place CraftEngine classes appear. CraftEngine's extension points used by MachineEngine live in `craft-engine-core` and are not a formally versioned API; churn is expected, hence the dual CI job (§2).

### 22.2 Block behavior registration

- In `MachineEngine.onLoad`: `BlockBehaviors.register(Key.of("machineengine", "machine"), MachineBlockBehavior.FACTORY)` (CraftEngine's `BlockBehaviors.register` writes into its built-in behavior-type registry; extension plugins such as *craft-engine-blocks* do this in `onLoad`).
- Server owners (or addons shipping CE resource packs) attach the behavior in CraftEngine block config, referencing a machine type by key. Verified schema (V1), as used by the example addon's pack:

```yaml
items:
  example:generator:
    model: minecraft:block/iron_block
    behavior: { type: block_item, block: example:generator }
blocks:
  example:generator:
    behavior:
      type: machineengine:machine
      machine: example:generator       # MachineType key, resolved lazily (D14)
      facing_property: facing          # optional; defaults to "facing" if the block has it
    settings:
      template: default:settings/solid_1x1x1
      overrides: { push_reaction: immoveable }
    state: { auto_state: solid, texture: minecraft:block/iron_block }
```

- The factory stores the unresolved key and every controller resolves it when its block entity loads, so CraftEngine reloads need no re-binding (V6). After CraftEngine loads or reloads block configs (`CraftEngineReloadEvent`), MachineEngine logs a warning for every block whose machine key is not registered. Such blocks stay inert, and blocks that already hold machine data load as placeholders.
- CraftEngine parses its block configs in its delayed init, after MachineEngine has enabled; lazy resolution makes this order irrelevant.
- The published CraftEngine jars relocate sparrow NBT to `net.momirealms.craftengine.libraries.nbt` (inside `craft-engine-core`); the adapter compiles against that package.

### 22.3 Block entity lifecycle mapping

| CraftEngine hook | MachineEngine action |
|------------------|----------------------|
| `EntityBlock.createBlockEntityController` | create `MachineBlockEntityController` (holds the controller id from `initControllerId`, needed for composite controllers via `controller.let(...)`) |
| `loadCustomData` | decode `MachineState` (or build placeholder) |
| `onLoad` | create `MachineInstance`, register capabilities, subscribe to neighbor chunk loads (`subscribeChunkLoad`), apply catch-up policy |
| `createBlockEntityTicker` | return the machine's `SleepingBlockEntityTicker` (one per controller) |
| `preBlockStateChange` | update facing → side mapping, wake `CONFIG_CHANGED` |
| `saveCustomData` | write cached or freshly encoded state (§20.2) |
| `onUnload` | invalidate capabilities, cancel handoffs-in-start (escrows remain persisted), stop timers |
| `onRemove` | break policy (drops / item), return escrow and in-flight buffers to drops, fire `MachineRemoveEvent` |
| `BlockBehavior.neighborChanged` | wake `NEIGHBOR_CHANGED`, invalidate neighbor capability cache |
| `BlockBehavior.useWithoutItem` / `useOnBlock` | `MachineInteraction` SPI (open GUI, configure sides) after protection check |
| `BlockBehavior.getSignal` / `getAnalogOutputSignal` | `redstone_control` / `comparator_output` capabilities |
| `WorldlyContainerHolder.getContainer` | expose item ports to hoppers/droppers as a vanilla `WorldlyContainer` view (§23.2) |

### 22.4 Facing and sides

The behavior config names the block-state property that stores facing. The adapter converts the CE property value to an absolute direction and resolves relative port sides. Machines without a facing property use absolute sides.

### 22.5 Events used from CraftEngine

`CustomBlockPlaceEvent`, `CustomBlockBreakEvent`, `CustomBlockInteractEvent` (protection and item-data restore), `CraftEngineReloadEvent` (re-binding, D14).

---

# 23. Paper Integration

### 23.1 Responsibilities of `machineengine-paper`

Bootstrap and service locator, lifecycle (§8), Brigadier commands (Paper lifecycle command registration), region scheduler bridge, PDC for non-block data, protection service, vanilla interop, item resource category, metrics export, configuration file.

### 23.2 Vanilla interop

- **Hoppers/droppers:** item ports are exposed through CE's `WorldlyContainerHolder` as a `WorldlyContainer` whose operations run as MachineEngine transactions, respecting port sides and filters.
- **Redstone:** optional `redstone_control` capability (ignore / active-high / active-low / pulse); comparator output through `comparator_output`.
- **Pistons:** machine blocks MUST be immovable: CE block settings have a `push-reaction`; the machine behavior requires `BLOCK`, and validation warns otherwise. **[VERIFY]** config key name.
- **Explosions:** treated as removal; break policy applies; addons can veto via `MachineRemoveEvent` (PRE).
- **WorldEdit / chunk regen:** CraftEngine handles WorldEdit compatibility for its blocks. Machines created without data start empty; machines removed this way follow `onRemove` without drops (no duplication).

### 23.3 Players, GUIs, protection, limits

- **GUI:** MVP ships no GUI toolkit. It provides the `MachineInteraction` SPI so addons can open their own inventory menus, plus a generic read-only inspection menu. A first-party GUI module is M5. Visual feedback MAY use CE block-entity render elements (display entities) later.
- **Protection:** opening/configuring a machine checks a `ProtectionService`. The default implementation delegates to CraftEngine's anti-grief provider (an internal CE API, accessed only in the adapter); placement and breaking are already protection-checked by CE.
- **Ownership:** `MachineState` records the placing player's UUID (optional). Permissions: `machineengine.admin.*`, `machineengine.inspect`, `machineengine.use`.
- **Limits:** configurable caps on machines per chunk and per player, and per-tick transfer budgets, to prevent lag machines.

### 23.4 Item resource category

`machineengine:item` is registered by the Paper module through the public SPI (not core). `ResourceType` per item id (vanilla or CE item); remaining item data stored as an opaque item-data component; stackability = `ItemStack.isSimilar` semantics. Amount = item count.

---

# 24. Validation

```java
record Diagnostic(Severity severity, Key code, String message, AddonId source,
                  @Nullable Key subject, @Nullable String location, Map<String, Object> context) {}
final class ValidationReport { List<Diagnostic> errors(); List<Diagnostic> warnings(); … }
```

Validated at startup and on every reload: IDs and namespaces, duplicates, missing dependencies and API ranges, ports (unknown capability, impossible direction/sides), storages (zero/negative capacity), recipes (unknown types, unsatisfiable matchers), processes, codecs and migration chains, network definitions, capability compatibility, CE block bindings (unknown machine type, missing facing property, wrong push reaction), modifier targets.

Fail early, report everything in one pass (not first error only), and disable only the offending addon's content.

---

# 25. Error Model

Operations that can fail in expected ways return results, not exceptions:

```java
sealed interface Result<T> permits Ok, Err {}
sealed interface MachineError {
    Key code(); String message(); Map<String, Object> context();
}
```

Error families (each a record implementing `MachineError`): `InvalidDefinition`, `MissingResource`, `InsufficientResource`, `TransferRejected`, `MachineBlocked`, `NetworkUnavailable`, `SerializationError`, `DependencyMissing`, `InvalidConfiguration`, `CapabilityUnavailable`, `WrongThread`, `RegistryFrozen`. Addons may define more (the code is a `Key`).

Exceptions are reserved for programmer errors (contract violations). Each machine keeps its last error (`machine.lastError()`) for inspection.

---

# 26. Events (D12)

- `EventType<E>` is registered (addons can add types). `EventBus` with priorities and two phases: `PRE` (may cancel or modify where the event allows) and `POST` (observe only).
- Listeners are owned by an addon and are unregistered automatically when it disables.
- Events fire on the owner thread of the operation; listeners MUST NOT block.
- Simulated transactions fire no events. Commit callbacks fire POST events.
- No per-transfer events in hot paths: `ResourceTransferEvent` is aggregated per machine per tick, and only emitted if a listener is subscribed for that machine type.
- Initial set: `MachineCreate/Load/Unload/Remove`, `ResourceInsert/Extract/Transfer` (aggregated), `MachineProcessStart/Complete`, `MachineUpgrade`, `RegistryFrozen`, `RecipesReloaded`, later `NetworkCreate/Change`.
- An optional bridge MAY mirror selected events as Bukkit events in the Paper module.

---

# 27. Observability, Debugging and Inspection

- Metrics: per-region counters aggregated on read (`scheduler.active_machines`, `scheduler.sleeping_machines`, `machine.processes`, `machine.resource_transfers`, `handoff.in_flight`, `tick.time_us` …). Hot counters behind a config flag.
- Inspection API: `machine.state()`, `ports()`, `storages()`, `process()`, `schedulerState()`, `lastWakeReason()`, `lastError()`, `escrow()`.
- Commands (Brigadier):

```text
/machineengine inspect                 machine you are looking at
/machineengine machine <x y z>         same by position
/machineengine registry <name> [filter]
/machineengine placeholders list|purge
/machineengine profile start|stop      slow machines, budgets, handoffs
/machineengine debug <flag> on|off     thread assertions, capability-cache checks
/machineengine reload recipes
```

Debug output must be useful without attaching a debugger.

---

# 28. Testability (`machineengine-test`)

Core logic MUST be testable without a Minecraft server. The harness provides:

- `TestRegistrationEnvironment` — simulates multiple addons registering, dependency ordering, freeze, validation.
- `FakeWorld` with chunks, load/unload, and a deterministic game clock.
- `FakeRegionScheduler` — multiple simulated regions with deterministic interleaving, used to test handoff, including injected crashes at every protocol step.
- In-memory `DataView` for persistence round-trips and migrations.
- Assertions for resource conservation (§10.3 invariant).

Real-server tests: `run-paper` with the pinned CraftEngine snapshot jar for smoke tests (M3), run on Paper 1.20.6 and 26.3. MockBukkit is not used (it cannot host CraftEngine).

---

# 29. Data-Driven Definitions (future YAML)

Not implemented in the MVP; the extension point is preserved:

```text
YAML → Parser → Reference resolution → Templates/Inheritance → Validation → Definition compiler
     → MachineType / Recipe / ProcessType … registered through the same public API
```

- YAML is a declarative layer over the public API, not a separate runtime.
- YAML references registered IDs only: no class loading, reflection, method invocation, scripts or filesystem access.
- Behavior comes from registered `MachineBehavior` factories referenced by key; configuration parameters are passed to them.
- Data-driven definitions go into reloadable registries (D5); static types they need must already be registered in code.
- CraftEngine block YAML already references machine types by key (§22.2) and is the first, minimal data-driven surface.

---

# 30. Ownership Rules

| Thing | Owner | Others may… |
|-------|-------|-------------|
| Machine state & storages | the machine instance (owner thread) | access only via capabilities/transactions on the owner thread |
| Escrow of a handoff | source machine until ack | read via inspection |
| Network state | `NetworkManager` (per world, per region on Folia) | attach/detach through ports |
| Scheduled tasks / timers | the scheduler | request via `MachineContext.scheduler()` |
| Persistence | the controller/adapter | provide codecs; never write CE tags directly |
| Registrations | the registering addon | read after freeze |
| World mutation | the host (owner thread) | queue `WorldEffect`s |

A machine owns its internal state but not external network state. Addons cannot mutate infrastructure they do not own except through the API.

---

# 31. Plugin Isolation and Cross-Plugin Interaction

An addon can register resource categories, resources, components, machines, ports, capabilities, recipes, processes, networks, transports, upgrades, modifiers, codecs, converters, event types, and its own registries, without modifying MachineEngine.

```text
Plugin A: energyplugin:energy, energyplugin:fe, energyplugin:generator
Plugin B: mymod:fluid, mymod:oil, mymod:refinery (consumes energyplugin:fe)
Plugin C: converter energyplugin:fe → magicplugin:mana
```

Cross-plugin interaction works because compatibility is decided at runtime from registered identity: a port of Plugin B's machine whose matcher accepts `energyplugin:fe` interacts with Plugin A's generator; no shared code beyond the MachineEngine API is required. When B requires A's types at registration time, B declares `requires("energyplugin", …)` so its callback runs after A's (§8.3).

First-party content plugins have no privileged access; they use the same public API.

---

# 32. Performance

Avoid: ticking every machine every tick, per-tick allocation in hot paths, unnecessary serialization (dirty caching §20.2), network rebuilds, unnecessary client sync, registry lookups by string in hot loops, reflection, global locks.

Prefer: sleeping tickers, coalesced wake-ups, canonical immutable definitions compared by reference, index-based capability lookup, primitive storage layouts, batched transfers, per-region data structures, profiling hooks.

Performance acceptance targets are set in M3 with a benchmark world (e.g. 10,000 idle machines must cost ~0 tick time; 1,000 active simple machines within a stated budget).

---

# 33. Non-Goals

MachineEngine MUST NOT: assume FE is default, assume fluids are water-based, assume one universal resource system, convert resources automatically, hardcode resource categories or machine types in core, require a particular energy or fluid system, require CraftEngine in the `api`/`core` modules, use CraftEngine outside the adapter module, modify third-party plugins, require a client mod, require every machine to use recipes/networks, tick every machine every tick, load chunks, or expose internal classes as public API.

---

# 34. Naming and Namespaces

- Namespaced IDs everywhere. MachineEngine's namespace: `machineengine`. Examples in this document use `energyplugin:`, `mymod:`, `magicplugin:` for addons.
- Avoid Forge-specific branding or terminology that could be confused with Minecraft Forge/NeoForge.
- Java package root: `fr.robie.machineengine` (D18), with one sub-package per module: `fr.robie.machineengine.api`, `.core`, `.test`, `.paper`, `.craftengine`. Maven group: `fr.robie`.

---

# 35. Milestones and Acceptance Criteria

Build stable foundations first; do not implement every advanced system at once.

### M0 — Scaffold
Gradle multi-module layout (§5), version catalog, `craftengine_version=26.10-SNAPSHOT`, CI (build, tests, dual-CE job, API compatibility placeholder), `paper-plugin.yml` with required CraftEngine dependency, `run-paper` tasks for Paper 1.20.6 and 26.3 that install the CE snapshot.
**Accept:** plugin loads on Paper with CE present; refuses to load with a clear message when CE is absent; `api`/`core` have no Paper/CE on the classpath.
**Status: done (2026-10-01).** Verified on Paper 1.20.6 (build 151) and 26.3 (build 141) with CraftEngine `26.10-SNAPSHOT` built from `dev`; without CraftEngine, Paper rejects MachineEngine with `UnknownDependencyException`; `verifyPureJava` fails the build if Paper is added to `core`.

### M1 — Foundations (headless, `api` + `core` + `test`)
Keys/`RegistryKey`, static + reloadable registries, lifecycle phases, addon registration & dependency ordering, validation/diagnostics, error model, resource category/type/stack/components/merge policies, predicates/matchers/tags, storages, transactions, converter registry, codecs + `DataView` + migrations, event bus.
**Accept:** unit tests for all; property tests proving the resource-conservation invariant under random operation/abort sequences; duplicate/missing/cycle diagnostics tested.
**Status: done (2026-10-01).** 63 tests pass (API module and `machineengine-test`). The conservation property test runs 8 seeds × 200 random rounds with nested commits/aborts across 6 storages, and fails as expected when rollback is deliberately broken. The format-neutral data tree is `DataValue`/`DataMap` (named `DataView` in earlier drafts).

### M2 — Machines (headless)
Capabilities, ports & sides, `MachineType/Instance/State/Context/Behavior`, scheduler states & wake reasons, recipes (reloadable) & processes with in-flight buffer, upgrades/modifiers (D16), placeholders (D3), persistence round-trip, handoff protocol over `FakeRegionScheduler`, inspection API.
**Accept:** the §36 integration test passes fully headless, including persistence, scheduler, a cross-region variant, injected crash points, and the missing-addon variant.
**Status: done (2026-10-01).** 90 tests pass. `MachineIntegrationTest` covers the two-addon scenario (same region, cross-region over the handoff protocol, save/reload with identical continued simulation, missing addon → placeholder → rehydration, runtime identity at ports). `HandoffCrashTest` restores mixed saves at each protocol step; resources are conserved in every case except the documented window, which loses exactly the one in-flight transfer. Deliberately disabling dedupe or the in-flight buffer makes these tests fail. The host SPI is `core.machine.MachineHost`; the test harness provides `FakeWorld` (regions, clock, timers, message queues, crash simulation).
Deferred from M2: per-instance port side configuration, `WorldEffect`s (no world mutation needed before M3), automatic wake of loaded machines on recipe reload (the host does it in M3), and the placeholder break policy (the host decides in M3).

### M3 — Paper + CraftEngine
`machineengine:machine` behavior, controller, sleeping ticker, lazy binding & CE reload re-binding, persistence in CE block-entity data with unsaved-flag handling, chunk-load subscriptions, break/item policy, hopper interop, item category, protection, commands, metrics, config, limits/budgets.
**Accept:** §36 test runs as two real addon jars on a `run-paper` server; state survives restart; removing an addon yields placeholders that rehydrate when it returns; removing MachineEngine and re-adding preserves data; 10k idle machines show negligible tick cost.
**Status: core done (2026-10-01), rest split into M3b.** Verified on Paper 1.20.6 and 26.3 with CraftEngine `26.10-SNAPSHOT`, using the `machineengine-example` addon (a generator and a consumer, placed by `/mexample place`): machines are created in CraftEngine block entities, sleep and wake through CraftEngine's sleeping ticker and Paper's region scheduler, transfer through ports, survive restarts (state stored in the block-entity data, chunks flagged through `CEWorld.blockEntityChanged`), turn into placeholders when the addon is removed, and rehydrate unchanged when it returns. The server run found one bug: region-scheduler timers fire one tick before the world clock reaches the wake time. Early timers now reschedule themselves (regression test added). Commands: `/machineengine status|report|inspect|machine <x y z> [world]|registry <name>|reload recipes|tags`.
**M3b (remaining):** the item resource category and dropping item resources on break, hopper interop (`WorldlyContainerHolder` needs an NMS container), keep-contents-in-item, protection checks for interactions, the placeholder break policy, `neighborChanged` wake-ups, metrics, configuration and limits, the 10k-idle-machines benchmark, and the "remove MachineEngine and re-add" run. Until the item category exists, resources of a broken machine have no world form and are voided (logged at FINE).

### M4 — Networks, transports, Folia
Network/transport API and implementation (incremental graphs, segment-per-region), bounded catch-up policy, Folia validation (`folia-supported: true`).

### M5 — Ecosystem
GUI module, data-driven YAML definitions (§29), official content plugins, Bukkit event bridge.

---

# 36. Initial Integration Test (proves third-party extensibility)

```text
Addon A registers: resource category A-cat, resource types A and B, machine A (outputs B)
Addon B registers: machine B (consumes B), requires Addon A
```

The test MUST prove:

- no MachineEngine source modification;
- resource and machine registration from two independent addons, with dependency ordering;
- ports, storages and transfers work across addons, using transactions;
- runtime identity validation (a port rejects a look-alike type from another namespace);
- scheduler: B sleeps when empty, wakes on insert; A goes `BLOCKED` when B is full;
- persistence: save → reload → identical state (including in-flight process buffer);
- cross-region variant: A and B in different fake regions transfer via handoff without loss, including injected crashes;
- missing-addon variant: unload Addon B's registrations → machine B becomes a placeholder with data intact → restore → rehydrates;
- resource conservation invariant holds throughout.

---

# 37. Development Philosophy

Prefer: interfaces, immutable definitions, composition, registries, constructor injection, explicit ownership, explicit transactions, capabilities, events, validation, testability.

Avoid: global hardcoded systems, static mutable state (except the single documented entry point), resource-specific assumptions, hidden conversions, CraftEngine coupling outside the adapter, reflection-heavy hot paths, uncontrolled plugin access.

When uncertain between a hardcoded feature and an extension point, prefer the extension point if it does not make the core unnecessarily complex.

---

# 38. Final Design Requirement

```text
MachineEngine
 ├── Addon A defines an entirely new resource category
 ├── Addon B defines machines using that resource
 ├── Addon C defines a network for that resource
 ├── Addon D defines a transport for that resource
 ├── Addon E defines a converter/process involving that resource
 └── Addon F defines upgrades for those machines
```

…without changing MachineEngine's source code. MachineEngine remains a generic machine and resource infrastructure layer, not a collection of predefined industrial mechanics. Its quality is judged by how easily an independent developer can extend it without modifying the core.

---

# 39. Open Items to Verify Against CraftEngine Source

| # | Item | Section |
|---|------|---------|
| V1 | ~~Exact CE block YAML schema~~ **Resolved:** `blocks.<id>.behavior.type: machineengine:machine` (§22.2) | §22.2 |
| V2 | ~~Thread of `saveCustomData`~~ **Resolved:** CE serializes on the calling (owner) thread before encoding async; chunks are written before their block entities unload. `loadCustomData` runs while a chunk is read, so the controller only stores the raw tag and decodes in `onLoad`. | §20.2 |
| V3 | ~~`loadCustomDataFromItem` for blocks~~ **Resolved:** never called for blocks; keep-contents-in-item will use MachineEngine's own item data (M3b). | §20.4 |
| V4 | ~~Push reaction key~~ **Resolved:** `settings.push_reaction: immoveable` (`BLOCK` is deprecated). | §23.2 |
| V5 | Anti-grief access: `BukkitCraftEngine.instance().antiGriefProvider().test(player, Flag, location)`; `Flag` comes from AntiGriefLib, not on our compile classpath yet (M3b). | §23.3 |
| V6 | ~~Re-binding on `/ce reload`~~ **Resolved:** not needed; keys resolve lazily per block entity. | §22.2 |

### CraftEngine facts confirmed (dev branch, commit `d50b4c9`, 2026-09-30)

- Version `26.10-SNAPSHOT`, published to `repo.momirealms.net/snapshots`; Paper plugin, `load: STARTUP`, Folia-supported, bytecode `--release 21`.
- Block behaviors: `BlockBehaviors.register(Key, BlockBehaviorFactory)` into `BuiltInRegistries.BLOCK_BEHAVIOR_TYPE`; factories receive the `BlockDefinition` and `ConfigSection`.
- Block entities: behaviors implementing `EntityBlock` create a `BlockEntityController` with hooks `saveCustomData`, `loadCustomData`, `onLoad`, `onUnload`, `onRemove`, `preBlockStateChange`, `createBlockEntityTicker`, `createAsyncBlockEntityTicker`, `subscribeChunkLoad`; multiple controllers per block are composed (`let(Class, index, …)`).
- Ticking: `SleepingBlockEntityTicker` (owner-thread sleep/wake), active/sleeping partitioned ticker list; ticking skipped when the chunk is not block-ticking.
- Storage: CE region files (MCA or linear) with a per-chunk unsaved flag; block-entity data changes do not set it automatically.
- Missing behavior: `InactiveBlockEntityController` preserves and re-emits raw data.
- Hopper interop hook: `WorldlyContainerHolder.getContainer`.
- Public Bukkit events: `CustomBlockPlace/Break/Interact`, `CraftEngineReloadEvent`, `ServerPreShutdownEvent`.
