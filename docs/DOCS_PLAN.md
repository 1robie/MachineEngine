# Docs plan: README and website

Owner's requests (2026-10-11): rewrite the README, since the repo has changed since it was written;
then add MachineEngine to the portfolio and documentation website (`1robie.github.io`, a Docusaurus
site in `C:\Users\clare\IdeaProjects\1robie.github.io`).

Status (2026-10-11): stages 0-2 done; MachineEngine 0.1.0 and Mekanized 0.1.0 released on GitHub and
Hangar, Hangar pages synced from the READMEs. Next: stage 3 (site scaffolding), once the site repo's
uncommitted work is committed. Open: JitPack failed to build the 0.1.0 tag (its builders could not
install a JDK, any jitpack.yml), retried; Mekanized's CI and releases no longer need JitPack.

## Decisions (owner, 2026-10-11)

1. **New sidebar kind `plugin-framework`** for MachineEngine.
2. **Mekanized is its own project on the site**, listed right below MachineEngine (`kind: 'plugin'`).
3. **Both repos go public** on GitHub and the `api` is published on JitPack before the site goes live.
   The owner makes the repos public; Claude prepares the build for it (stage 2).
4. **Contributor text: `CONTRIBUTING.md` at the repo root** (Claude's choice). GitHub links it from
   the repo page and from new issues and pull requests, and it keeps the README for users.
5. **The MachineEngine block replaces the portfolio's "Project name" template block.**
6. **Screenshots: yes.** Claude cannot run a Minecraft client, so the owner takes them on a dev
   server from the shot list in stage 8; Claude places and crops them.
7. **Two repositories:** MachineEngine and Mekanized are split (MEKANIZED_SPLIT_PLAN.md). Each
   repo has its own README; Mekanized's is written in that plan, from the site pages of stage 7.
8. **Downloads: not only GitHub releases.** Proposed: GitHub releases, Modrinth and Hangar
   (PaperMC's own; both plugins are Paper and Folia only, so no SpigotMC). Confirmed by the owner.
   Update (2026-10-11): **Hangar only for now**; Modrinth later, when the owner has time to set it up.
   Hangar categories: MachineEngine *Developer Tools*, Mekanized *Gameplay*.
9. **Repo names** `1robie/MachineEngine` and `1robie/Mekanized`; Mekanized's package becomes
   `fr.robie.mekanized`, its history is kept (MEKANIZED_SPLIT_PLAN.md).

## Where things stand

### The README

It grew one section per feature (each plan's "docs" stage added one), so it is now ~300 lines that
read as a changelog rather than an introduction.

- **No order for the reader.** Server owners, addon developers and contributors all read the same
  page. Building and dev servers come before what the plugin does for a server; Bukkit events sit
  between CraftEngine templates and console commands.
- **Missing features** (decisions with no README text):
  - Transports and cables: networks, `machineengine:transport`, the 4 arm items (D38). No section.
  - Per-block numbers in the CraftEngine block config: `storages: {<id>: {capacity: N}}` on the
    `machineengine:machine` behavior, and `config_factory` rows (D34, D36). Only hinted at.
  - Textured menus (D39): players need the resource pack, or menu titles show boxes.
  - Resource colors (D41): a type's color, `color:` in YAML resource definitions, the
    `storage_<id>_color` placeholder.
- **Reference inside the front page:** the zMenu action button YAML (~25 lines), the multiblock
  example, every admin command.
- **Dev-only text mixed with user text:** Mekanized's check commands, `forceload` setup.

Still right and kept: requirements, module table and graph, build command, dev server tasks
(`runPaper-1.20.6`, `runPaper-26.3`, `runFolia-1.20.6`, `runFolia-26.2`), CraftEngine runtime
properties, license.

### The website

Read from its `AGENTS.md`, `src/data/projects.ts` and `docs/main/site-architecture.md`:

- **Adding a project is one entry in `PROJECTS`** (`src/data/projects.ts`) plus a folder
  `docs/plugins/<id>/` holding every page its sidebar lists. The navbar, footer, search, version
  dropdown and plugin listing page come from that entry. `docusaurus.config.ts` is not edited.
- **The sidebar is fixed by `kind`**, explicit, and shared by every project of that kind:
  - `plugin`: overview, Installation (compatibility, installing, updating), Configuration
    (config reference, messages), Usage (commands, permissions, placeholders), integrations,
    Help (troubleshooting, FAQ).
  - `plugin-api`: the same plus a collapsed Developer API (add the dependency, events, Javadoc).
- **Versions and coordinates are never typed in Markdown:** `<CompatibilityTable>`,
  `<DependencySnippet>` and `<JavadocLink>` read the `PROJECTS` entry. They assume releases are
  published on JitPack.
- **Before a version is cut**, docs are served at `/plugins/<id>/` (no `/next/`). A cut writes
  `<id>_versioned_docs/` at the repo root.
- **Checks:** `npm run check` (build fails on broken links and anchors). Deploys on push to `main`;
  never `npm run deploy`.
- **Portfolio:** `docs/portfolio/projects.md`, one block per project (problem, interesting decision,
  stack, source, live, status). It still holds the template block "Project name".
- **The site repo has uncommitted work** (paper-dispatch docs, `docusaurus.config.ts`,
  `sidebarsLibrary.ts`, `README.md`, social card). Not ours: the website stages start only after
  the owner commits or stashes it, on their own branch.

### What does not fit yet

1. **MachineEngine has no git remote.** `Project.repo` drives the source link, and the portfolio
   entry wants a source link. Settled by decision 3.
2. **Nothing is published, and nothing can be yet.** Version is `0.1.0-SNAPSHOT`, no tags, and no
   module applies `maven-publish`. JitPack would build the whole project (paperweight userdev
   modules, Mekanized) with its default JDK. With nothing on JitPack the footer's Javadoc link 404s
   and `<DependencySnippet>` shows coordinates that do not resolve. Stage 2 fixes this.
3. **The `plugin` sidebar has no room for a framework.** MachineEngine's biggest topics (menus,
   YAML definitions, multiblocks, CraftEngine block and item config, transports, player data)
   are none of commands / permissions / placeholders. Squeezing them into `integrations` makes one
   huge page.

## Design

**One home for user docs: the website.** Server and addon documentation lives on the site; the
repo keeps what belongs to the code (ARCHITECTURE.md, the plans, contributor notes). The README
becomes a short front page that links to the site. This replaces the earlier idea of
`docs/SERVER_GUIDE.md` and `docs/ADDON_GUIDE.md` in the repo: two copies would drift.

### README (target: under 120 lines)

1. What MachineEngine is: two or three sentences ("mechanisms, not meanings"), and what a server
   gets with an addon such as Mekanized.
2. Requirements (table, unchanged).
3. Installing: the jars to put in `plugins/` (CraftEngine required; zMenu optional but needed for
   menus), first start, `/ce reload all`, resource pack required for menus.
4. Features at a glance, one line each, linking to its page on the site.
5. Documentation links: website (server owners, addon developers), Mekanized guide,
   ARCHITECTURE.md.
6. Building: the build command, where the jar lands, and a link to `CONTRIBUTING.md`.
7. Mekanized: short paragraph, Mekanism credit and non-affiliation.
8. License.

### Website: a `plugin-framework` kind

A new kind for plugins other plugins and packs build on. It keeps the `plugin` shape and adds a
**Building with it** category between Usage and Help, then the Developer API last and collapsed:

| Category | Pages (`docs/plugins/machineengine/…`) | From |
|---|---|---|
| Overview | `intro.mdx` | README intro, compatibility table |
| Installation | `installation/compatibility`, `installing`, `updating` | README requirements, Mekanized guide install steps |
| Configuration | `configuration/config-reference` (config.yml), `messages` (lang.yml translations) | README, config comments in code |
| Usage | `usage/commands`, `permissions`, `placeholders` (zMenu placeholders), `debug-view` | README commands, debug section |
| Building with it | `building/resources` (energy, fluids, colors), `definitions` (YAML), `multiblocks`, `blocks-and-items` (CE behaviors, per-block numbers, templates, keep-contents, hoppers, comparators), `transports`, `menus` (zMenu files, button types, textured menus), `player-data` | README, D29-D41, plans' docs stages |
| Integrations | `integrations` (CraftEngine, zMenu, Folia) | README |
| Help | `help/troubleshooting`, `faq` | `/machineengine report`, placeholders for missing addons (D3) |
| Developer API | `api/index` (depend, register in `onLoad`, D13), `events` (event bus and Bukkit events), `javadoc` | `api` module |

Site changes for the new kind (all in the site's own pattern): `ProjectKind` gets
`'plugin-framework'`; `projectSidebarPath` returns `./sidebarsPluginFramework.ts`;
`hasDeveloperApi` stays true; the new sidebar file imports `PLUGIN_SIDEBAR_ITEMS`; AGENTS.md and
site-architecture.md list the kind. No change to `docusaurus.config.ts`.

The site goes live only after the `api` is on JitPack (decision 3), so the Developer API pages use
`<DependencySnippet>` and `<JavadocLink>` like the other projects. Until then they stay on the
site branch.

### `CONTRIBUTING.md` (repo root)

Module layout and graph, the Paper baseline rule (`verifyPaperBaseline`), dev servers and their
properties (`-PwithAddons`, `-PwithZMenu`, `-PrefreshCraftEngine`, `-Pcraftengine_runtime`),
headless server checks, Mekanized's check commands, `:plugin:ceSnapshot`, where decisions
(ARCHITECTURE.md) and plans (`docs/*_PLAN.md`) live, and how a release is cut (stage 2).

### Website: Mekanized

Its own `PROJECTS` entry right after MachineEngine (registry order is navbar and listing order),
`kind: 'plugin'`, folder `docs/plugins/mekanized/`, written from MEKANIZED_GUIDE.md:

| Category | Pages | From |
|---|---|---|
| Overview | `intro.mdx`: what it adds, Mekanism credit and non-affiliation, link to MachineEngine | guide intro |
| Installation | `compatibility`, `installing` (CraftEngine, MachineEngine, zMenu, resource pack), `updating` (edited files kept) | guide "Installing" |
| Configuration | `config-reference` (`plugins/Mekanized/config.yml`), `messages` (CraftEngine lang) | guide, config keys |
| Usage | `commands`, `permissions`, `placeholders` | guide, command classes |
| Integrations | `integrations`: MachineEngine energy and fluids, zMenu menus, CraftEngine pack | guide |
| Help | `troubleshooting`, `faq` | guide |

Its machines list (tiers, multiblocks, radiation, QIO…) goes in `intro.mdx` or a Usage page; the
`plugin` sidebar is fixed, so no extra category. MEKANIZED_GUIDE.md (in the Mekanized repo after
the split) then shrinks to a link to the site. Its `repo` is `Mekanized`; it has no developer API,
so no `maven` field.

### Website: `PROJECTS` entry

```ts
{
  id: 'machineengine',
  label: 'MachineEngine',
  description: 'Machine and resource framework for Paper and Folia on CraftEngine: storages, transfers, recipes, networks, multiblocks and menus that addons and YAML packs build on.',
  icon: '⚙️',
  kind: 'plugin-framework',
  repo: 'MachineEngine',
  maven: {groupId: 'com.github.1robie.MachineEngine', artifactId: 'machineengine-api'}, // check on jitpack.io
  latestVersion: '0.1.0',         // the first tag (stage 2)
  platforms: ['paper', 'folia'],
  minecraftVersions: '1.20.6 – 26.3',
}
```

### Website: portfolio

A MachineEngine block in `docs/portfolio/projects.md`, in the page's format:
- Problem: Mekanism-style machines on a server with no client mod.
- Interesting decisions: transactional transfers with snapshot/rollback (D11), cross-region handoff
  on Folia (D1), one jar for 1.20.6 to 26.3 with a bytecode baseline check (D25), missing content
  kept as placeholders (D3).
- Stack: Java 21/25, Paper, Folia, CraftEngine, zMenu, Gradle with paperweight userdev.
- Source, link to the docs, status (in development).

It replaces the template block "Project name" (decision 5). Mekanized gets a short block of its own
below it, linking to its docs.

## Stages

**Stage 0: decide.** Done (see Decisions).

**Stage 1: inventory (MachineEngine).** Check every fact in today's README, MEKANIZED_GUIDE.md and
the plans' docs stages (D29-D41) against the code: commands, permissions, config keys and defaults,
placeholders, behaviors, button types, events, file paths. Output: a checklist at the end of this
plan, each fact mapped to its site page.

**Stage 2: ready to publish (MachineEngine).**
- `maven-publish` on `api` (sources and Javadoc jars), artifact `machineengine-api`. Whether `test`
  (the headless harness addons use) is published too: decided here, from how addons would use it.
- `jitpack.yml`: JDK 25, and build only what is published (`:api:publishToMavenLocal`), not the
  paperweight modules or Mekanized.
- Release job (GitHub Actions, on a tag), in each repo: attaches the jar to a GitHub release and
  uploads it to Modrinth (Minotaur Gradle plugin) and Hangar (`hangar-publish-plugin`), with the
  supported versions, loaders (Paper, Folia) and the changelog. Mekanized declares MachineEngine
  and CraftEngine as required dependencies there.
- The owner creates the Modrinth and Hangar projects (names, descriptions, icon; text drafted by
  Claude from the site overview) and adds their tokens as GitHub secrets. Claude never handles
  the tokens.
- Check nothing private is in the history before the repo goes public: secrets, `run/` files,
  local paths.
- Version `0.1.0`. Publishing to JitPack itself is done in MEKANIZED_SPLIT_PLAN stage 2; the
  owner makes the repos public and pushes the tags; Claude checks the JitPack build and the
  coordinates in the `PROJECTS` entries.
- **Hangar release done (2026-10-11), both repos:** `.github/workflows/release.yml` runs on a tag named
  like the version: `build -Pversion=<tag>`, a GitHub release with the jar (prerelease for a hyphenated
  tag), then `publishPluginPublicationToHangar` (hangar-publish-plugin 0.1.4, channel Release, or
  Snapshot for a hyphenated tag; Paper `hangar_paper_versions` = 1.20.6-26.3; dependencies CraftEngine
  by URL, zMenu optional, and MachineEngine for Mekanized). Needs the `HANGAR_API_TOKEN` secret.
  Modrinth is left for later. Tag MachineEngine first: Mekanized builds against its JitPack tag.

**Stage 3: site scaffolding (site repo, on a branch).** Starts once the owner has committed or
stashed the site's own work. The `plugin-framework` kind and sidebar, both `PROJECTS` entries
(MachineEngine, then Mekanized), a `downloads` field on `Project` (GitHub, Modrinth, Hangar URLs)
read by a new `<DownloadLinks project="…" />` component for the install pages (so no URL is typed
in Markdown, as AGENTS.md asks), every page as a stub marked with `:::note`, AGENTS.md and
site-architecture.md updated. `npm run check` passes.

**Stage 4: MachineEngine server pages.** Overview, Installation, Configuration, Usage,
Integrations, Help.

**Stage 5: Building with it.** The seven pages, with the missing topics written fresh
(transports, per-block numbers, textured menus, colors).

**Stage 6: Developer API.** Written from the `api` module, with a small example addon checked by
compiling it (or taken from `test`).

**Stage 7: Mekanized pages.** From MEKANIZED_GUIDE.md, checked against the code.

**Stage 8: screenshots.** Shot list for the owner, taken on `runPaper-26.3` with the resource pack:
1. A textured processing menu (crusher or enrichment chamber) mid-process.
2. The upgrades menu with a few upgrades installed.
3. A row of Mekanized machines joined by cables (cable arms visible).
4. A formed multiblock (dynamic tank or induction matrix).
5. The debug view (`/machineengine debug`): state dots, port dots, a network's sparks.
6. An energy cube or tank item's lore.

Claude crops them, saves them as `.webp` under the site's `static/img/machineengine/` and
`static/img/mekanized/`, and places them in the overview and topic pages with alt text.

**Stage 9: portfolio.** The MachineEngine block replacing the template block, then a Mekanized
block. `npm run check`.

**Stage 10: repo docs (MachineEngine).** README rewritten to the outline, linking to the site.
`CONTRIBUTING.md` at the root. The Mekanized docs have already left with the split; the README's
Mekanized section becomes a line linking to its repo and site page. ARCHITECTURE.md
§0 points to the site for user docs.

**Stage 11: go live.** Once the `api` is on JitPack: the owner reviews the site branch locally
(`npm start`), then merges to `main`, which deploys. Not done by Claude. Then the repo READMEs'
site links work. Update memory and this plan's status.

## Open questions

None. Order of work: DOCS_PLAN stage 1 (inventory) and MEKANIZED_SPLIT_PLAN stages 1-5, then
DOCS_PLAN stages 2-11.
