# Mekanized split plan

Owner's decision (2026-10-11, while planning DOCS_PLAN.md): MachineEngine and Mekanized go in two
GitHub repositories, both public. Mekanized then builds against MachineEngine's published `api`,
like any outside addon.

Status: stages 0-4 done (2026-10-11). Next: stage 5, going public (owner).
Mekanized's repo needs MachineEngine's artifacts on JitPack.

## How tied together they are today

- **Mekanized compiles against `project(":api")`** and tests with `project(":test")` (the headless
  harness). D27 already keeps it on `api` only, so no engine internals to untangle in main code.
- **`plugin` installs Mekanized on the dev servers:** `devAddons(project(":mekanized", …))`,
  `-PwithAddons`.
- **Engine checks live in Mekanized:** `/mekanized check-drop`, `check-hopper`,
  `check-comparator`, `fill` test engine features (keep-contents, hoppers, comparators, idle cost)
  with a Mekanized bin and crusher.
- **Mekanized-only build code in the shared `buildSrc`:** `ContentManifest.kt`, `CeSnapshot.kt` (and
  the `ceSnapshot` tasks in `plugin/build.gradle.kts`, reading `mekanized/ce-snapshot.json`).
- **Shared build code Mekanized needs too:** `machineengine.platform` and
  `machineengine.java-conventions` plugins, `VerifyPaperBaseline.kt`, `VerifyRelocation.kt`,
  `CraftEngineDevTasks.kt` (CraftEngine dev build for dev servers), the version catalog.
- **Docs:** MEKANIZED_GUIDE.md, MEKANIZED_PLAN.md, and the Mekanized parts of CE_TEMPLATES_PLAN.md
  and MENU_TEXTURES_PLAN.md. ARCHITECTURE.md decisions about Mekanized: D27, D31, D36.
- **History:** 50 commits touch `mekanized/`; many also touch the engine in the same commit.

## Design

**MachineEngine repo** keeps `api`, `common`, `hooks`, `nms`, `plugin`, `test`. It publishes
`machineengine-api` and `machineengine-test` (Mekanized's tests need the harness, and so will
other addons' tests) on JitPack.

**Mekanized repo** (`1robie/Mekanized`, a single-module Gradle build):
- depends on `com.github.1robie.MachineEngine:machineengine-api:<tag>` (compileOnly) and
  `machineengine-test` (testImplementation);
- its own `buildSrc` with copies of the build code it needs (platform conventions,
  `VerifyPaperBaseline`, `VerifyRelocation`, `CraftEngineDevTasks`) plus `ContentManifest` and
  `CeSnapshot`, which move out of MachineEngine;
- its own dev servers (`runPaper-*`, `runFolia-*`) that install MachineEngine's jar next to it;
- package renamed to `fr.robie.mekanized` (decision 2), relocations included
  (`fr.robie.mekanized.libs.paperdispatch`).

**Working on both at once:** Mekanized's `settings.gradle.kts` uses
`includeBuild("../MachineEngine")` when that folder exists and `-PlocalEngine` is set, so a local
engine change is picked up without publishing. CI uses the JitPack tag.

**Engine checks:** `check-drop`, `check-hopper`, `check-comparator` and `fill` test the engine, so
they move to MachineEngine, as YAML-defined test machines (a bin and an idle processing machine in
a dev-server definitions file) checked by a `/machineengine check …` command or by headless tests
in `test`. Mekanized keeps its own `test`, `test-cable`, `test-chemistry`.

**History:** Mekanized's repo starts from `git filter-repo` on a copy of MachineEngine, keeping
`mekanized/` (moved to the root) and the build files it takes, so its history and authorship
survive. Commits that touched both keep only their Mekanized half. MachineEngine then deletes
`mekanized/` in one commit; its own history is not rewritten.

**Decision record:** D42 in ARCHITECTURE.md: Mekanized is a separate repository built against the
published `api` and `test`; supersedes the "dev addon of `plugin`" part of D27. D31 and D36 move to
Mekanized's own docs, with a line in ARCHITECTURE.md pointing there.

## Stages

**Stage 0: decide.** This plan. Owner answers the open questions.

**Stage 1: engine checks into MachineEngine.** YAML test machines and `/machineengine check
drop|hopper|comparator|fill` (or headless tests), run on a dev server without Mekanized
(`-PwithAddons=false`). Mekanized's copies are removed after the split.
- **Done:** `/machineengine check drop|hopper|comparator|fill` (`common/.../paper/EngineChecks.java`)
  on `devcheck:bin` and `devcheck:crusher`, defined in `plugin/src/dev/` (a definitions file and a
  CraftEngine pack) and copied into each dev server by `installDevChecks<Software>-<version>`.
  Checked on Paper 26.3 with `-PwithAddons=false`: drop 100/100, comparator 8 then 0, hopper 10 in
  the chest, fill 9 crushers.

**Stage 2: publishing in MachineEngine** (DOCS_PLAN stage 2, done here first): `maven-publish` on
`api` and `test`, `jitpack.yml` (JDK 25, only those two modules). Checked locally with
`publishToMavenLocal` and a throwaway consumer project.
- **Done:** `machineengine.publishing` (buildSrc) on every engine module, group
  `com.github.1robie.MachineEngine`, artifact = jar name (`machineengine-api`, `machineengine-test`,
  `machineengine-common`, `machineengine-zmenu`, `machineengine-nms-*`, `machineengine-plugin`).
  `machineengine-test` brings the others; `machineengine-plugin` is the thin jar (the shaded variant
  is skipped). `jitpack.yml` runs Gradle on JDK 21 and `publishToMavenLocal`; the toolchain fetches
  JDK 25. Checked with `publishToMavenLocal`: POMs reference each other by those coordinates. Not
  checked yet: the JitPack build itself (needs the public repo and a tag; paperweight on JitPack is
  the risk).

**Stage 3: new Mekanized repo** (in `C:\Users\clare\IdeaProjects\Mekanized`): `git filter-repo`
(installed with `pip install git-filter-repo`) on a fresh clone, then the standalone build:
`settings.gradle.kts`, version catalog, `buildSrc`, `includeBuild` switch, dev servers,
`ceSnapshot`, CI workflow, README, LICENSE (GPL-3.0) and LICENSE-MEKANISM.txt. Builds and passes
its tests against `mavenLocal` and against the local engine (`-PlocalEngine`). The package rename
to `fr.robie.mekanized` is its own commit after the history import, so the imported history stays
as it was.
- **Done:** `IdeaProjects/Mekanized`, 131 commits kept from `mekanized/`,
  `machineengine-mekanized/`, the two guides and the importer files in `buildSrc`, then: standalone
  build, package `fr.robie.mekanized`, the moved checks removed, README and CI. The local checkout is
  found automatically (`../MachineEngine`; `-PlocalEngine=false` turns it off, `-Pmachineengine_dir`
  moves it). 177 tests pass against the local engine. Not run yet: its dev servers (their run folders
  are new, so the Minecraft EULA has to be accepted there first).

**Stage 4: remove Mekanized from MachineEngine.** Delete `mekanized/`, the `devAddons` entry,
`ceSnapshot` tasks, `ContentManifest.kt`, `CeSnapshot.kt`; move the Mekanized docs; add D42.
`./gradlew build` passes. Check on a dev server that MachineEngine starts alone, and that both
plugins start together (Mekanized jar from its repo).
- **Done:** `mekanized/`, `devAddons` / `-PwithAddons`, the `ceSnapshot` tasks, `ContentManifest.kt`,
  `CeSnapshot.kt`, MEKANIZED_GUIDE.md and MEKANIZED_PLAN.md removed; D42 added; README trimmed (the
  rewrite is DOCS_PLAN stage 10). The older plans still name Mekanized's files as history.

**Stage 5: go public.** The owner creates both GitHub repos, pushes, and tags MachineEngine
`0.1.0` first, then Mekanized `0.1.0` once JitPack has built the engine. Claude checks JitPack's
build log and the coordinates. Release publishing (GitHub releases, Modrinth, Hangar) is
DOCS_PLAN stage 2.

## Decisions (owner, 2026-10-11)

1. **Repo names:** `1robie/MachineEngine` and `1robie/Mekanized`.
2. **Package:** Mekanized becomes `fr.robie.mekanized`.
3. **History:** Mekanized's history is kept (`git filter-repo`).
