# Contributing to MachineEngine

How the repository is laid out, how to build and run it, and how releases are made. For using
MachineEngine, see the [documentation](https://1robie.github.io/plugins/machineengine/).

## Decisions and plans

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) is the specification. Its decision log (D1, D2…) is
  binding: a change that goes against a decision changes the decision first, with its reason.
- `docs/*_PLAN.md` are the plans of larger pieces of work, with their stages and what was found on
  the way. They stay as history once done.

## Modules

| Module | Purpose |
|---|---|
| `api` | Public API and SPI, including the Paper-facing services addons use (`api.paper`) and version access (`api.platform`). |
| `common` | Implementation: the engine (`core` packages), its Paper integration and its CraftEngine integration. |
| `hooks/zmenu` | Optional zMenu integration (machine menus). One `hooks/<plugin>` module per optional plugin. |
| `nms/common` | Reflective fallback for Minecraft versions without a module of their own. |
| `nms/v1_20_6`, `nms/v26_3` | Version-specific code, built with paperweight userdev against each version. |
| `plugin` | The plugin jar (everything above), and the dev servers. |
| `test` | Headless test harness for MachineEngine and addons, built on the assembled plugin. |

```text
api <- nms:common <- common <- hooks:*  <- plugin <- test
                       nms:v1_20_6, nms:v26_3 <- plugin
```

Every module compiles against the newest Paper API (26.3) and CraftEngine, and emits Java 21
bytecode, except `nms/v26_3` (Java 25, loaded only on 26.x). `check` fails if the bytecode uses
anything missing from Paper 1.20.6, or is newer than Java 21 (`verifyPaperBaseline`), and if a
bundled library is not relocated (`verifyRelocation`). Why: ARCHITECTURE.md §5, D24–D28.

[Mekanized](https://github.com/1robie/Mekanized), the content addon, is its own repository (D42).
With both checked out side by side, Mekanized builds against this checkout.

## Building

JDK 25 builds everything (the Gradle toolchain downloads it if needed).

```bash
./gradlew build
```

The plugin jar is `plugin/build/libs/MachineEngine-<version>.jar`.

## Dev servers

```bash
./gradlew :plugin:runPaper-26.3
```

Also `runPaper-1.20.6`, `runPaper-1.21.4`, `runPaper-1.21.11`, `runFolia-1.20.6` and
`runFolia-26.2`. Servers run in
`run/<paper|folia>-<version>/`; on the first start, accept the Minecraft EULA in its `eula.txt`.
Do not rebuild while a dev server runs: it loads the jars from `build/libs`.

| Property | Effect |
|---|---|
| `-Pcraftengine_runtime=dev` (default) | CraftEngine built from its `craftengine_dev_ref` branch into `.craftengine/`, the snapshot MachineEngine compiles against |
| `-PrefreshCraftEngine` | Rebuild that CraftEngine jar |
| `-Pcraftengine_runtime=modrinth` | The published release (`craftengine_modrinth_version`) instead |
| `-Pcraftengine_runtime=none` | No CraftEngine: MachineEngine must refuse to start |
| `-PwithMachineEngine=false` | CraftEngine alone (checks that it keeps machine data) |
| `-PwithZMenu=false` | No zMenu |
| `-Pcraftengine_build_heap=4G` | Heap of the CraftEngine build |
| `-Pcraftengine_version=<v>` | Compile against another CraftEngine snapshot (CI's daily run uses the newest) |

### Server checks

The dev servers get two test blocks (`plugin/src/dev`, never shipped). From the console, after
`forceload add 0 0 63 63`:

| Command | Expected |
|---|---|
| `machineengine check drop <x y z> world` | a bin with 100 iron ingots broken without a player drops all 100 |
| `machineengine check hopper <x y z> world` | hopper → bin → hopper → chest moves all 10 ingots into the chest |
| `machineengine check comparator <x y z> world` | a comparator reads 8 from a half-full bin, 0 from an empty one |
| `machineengine check fill <size> <x y z> world` | a grid of idle crushers, to measure the idle cost |

Each logs its result with the expected value. `/machineengine debug` shows machine states, ports and
networks in the world.

## Tests

`./gradlew test` runs the headless tests: a real engine with fake worlds, regions and clock
(`test` module). New engine behavior comes with a test there; what needs vanilla blocks or a
client is checked on a dev server.

## A new Minecraft version

1. **Compile and baseline.** Raise the Paper API in the version catalog if the new version needs
   it; `./gradlew check` still enforces the 1.20.6 baseline (`verifyPaperBaseline`).
2. **Version access.** A version without a module of its own uses `nms/common` (reflection). For
   world fluids, check what `WorldFluidNms` does there: the key biomes injected into the registry
   data, the biome section format (`BiomeSections`: the array length prefix went away in 1.21.5),
   the biome update packet and the attribute names. A version that changed them gets a branch in
   `ReflectiveWorldFluids` or its own `nms/v<version>` module.
3. **Biome lookup.** `BiomeZoom` copies the client's `BiomeManager.getBiome`; compare it with the new
   client's (unchanged from 1.20.6 to 26.3).
4. **Shaders.** The pack builder patches the new client's water shader at runtime, and a shader that
   fails to compile disables the whole resource pack. Add the version to
   `tools/world-fluids/fetch_vanilla.py` (and `fetch_sodium.py` once a Sodium for it reads pack
   shaders), then follow [tools/world-fluids/README.md](tools/world-fluids/README.md): patch every
   version, compile every define set with glslang, 0 failures. A `ShaderPatch.MismatchException`
   means the shader lost a line the patch relies on: extend `ShaderPatch` for it.
5. **Dev server.** Add `registerDevServer("<version>", …)` in `plugin/build.gradle.kts`, run it,
   accept the EULA, and check: the server checks above, a fluid poured from a bucket, its look in
   game (with Sodium too on 26.2+), `/machineengine fluid info`.
6. **Docs.** The supported versions in the README and on the documentation site.

## Releases

Before the tag: in [CHANGELOG.md](CHANGELOG.md), turn "Unreleased" into the version and date (with
every API break listed, in a minor version only). After it: raise `version` in `gradle.properties`
to the next `-SNAPSHOT` and `apiBaseline` in `api/build.gradle.kts` to the version just released.
`:api:japicmp` fails a patch version that breaks the API of `apiBaseline`; its report is in
`api/build/reports/japicmp`.

Push a tag named like the version (`0.2.0`, `0.2.0-beta.1`):

- `release.yml` builds and tests with that version, checks the API with japicmp, makes a GitHub release with the jar, uploads it to
  [Hangar](https://hangar.papermc.io/1robie/MachineEngine) (a hyphenated tag goes to the Snapshot
  channel);
- `maven.yml` builds and tests, then publishes every module to the Maven repository `https://1robie.github.io/maven/`
  (repository `1robie/maven`), with the API Javadoc. It can also be run by hand for an existing tag.

`hangar-page.yml` keeps the Hangar page equal to this README. Secrets: `HANGAR_API_TOKEN`,
`MAVEN_REPO_TOKEN`. Release MachineEngine before a Mekanized release that needs it.
