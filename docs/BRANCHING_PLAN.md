# Branching plan: `develop` for work, `main` for releases

Status: all stages done (2026-10-22), D48. The owner added: only the owner opens pull requests into
`main` (the `Release source` check), everyone else targets `develop`. Earlier: stages 1-4 done (2026-10-22). The owner took every recommended answer, plus Hangar: dev
builds as a rolling GitHub `dev` pre-release and on the Hangar Snapshot channel (Maven snapshots
later), `develop` as the default branch, no
bypass on `main`, tags by hand.

Stage 1 notes: `develop` was cut from local `main`, which held 16 unpushed post-0.2.0 fixes; they
are on `develop` only and reach `main` with the next release. `origin/main` stays at `a8c910e`.
`feature/fluid-types` and `worktree-agent-a02fdc4c2b77623c3` were merged and are deleted;
`spike/world-fluids` (unmerged) and the `claude/charming-hodgkin-25db2b` worktree stay.

## Goal

- **`develop`**: where the work happens. Every push makes a **dev build**.
- **`main`**: protected. It only moves by a pull request from `develop` (or a hotfix branch), and
  every merge into it is a **release**: tagged, then published by the existing `release.yml` and
  `maven.yml`.

## Where things stand

- `main` has no protection and no rulesets; the repository is public.
- `build.yml` runs on every push and pull request on any branch, plus a daily run (on the default
  branch) against the newest CraftEngine snapshot. Jobs: `Build (CraftEngine pinned)` and
  `Build (CraftEngine latest)`.
- `release.yml` and `maven.yml` run on a version tag (`0.2.0`, `0.2.0-beta.1`). They do not check
  which branch the tagged commit is on.
- `hangar-page.yml` syncs the README to Hangar on a push to `main`.
- `gradle.properties` has `version=0.2.1-SNAPSHOT`; a release passes `-Pversion=<tag>`.
- CONTRIBUTING.md "Releases" describes tagging, with no branch model.

## The model

```
feature/* ──PR or merge──▶ develop ──PR (merge commit)──▶ main ──tag 0.x.y──▶ release.yml, maven.yml
                              ▲                            │
                              └──────── back-merge ────────┘   (only after a hotfix)
hotfix/* (from main) ──PR──▶ main, then main ─▶ develop
```

- Daily work: commit on `develop`, or on `feature/*` branches merged into `develop`.
- A release: a pull request `develop` → `main`, merged with a **merge commit** (not squash, not
  rebase), so `main` stays an ancestor of `develop` and the two never diverge.
- A hotfix: branch `hotfix/0.2.2` from `main`, PR into `main`, release, then merge `main` back into
  `develop`.

## Stages

### Stage 1: create `develop` (done)

1. `git switch -c develop main` and `git push -u origin develop`.
2. Make `develop` the **default branch** on GitHub (Settings → General). Then contributors' PRs
   target it by default, clones get the current code, and the daily CraftEngine-snapshot run in
   `build.yml` watches `develop`, where breakage matters first.
3. Clean up old local branches while at it (`claude/charming-hodgkin-25db2b`,
   `worktree-agent-a02fdc4c2b77623c3`; `feature/fluid-types` is already released in 0.2.0).

### Stage 2: protect `main` (ruleset) (done)

Done: rulesets `main: releases only` (id 25622042) and `develop: no force push or delete`
(id 25622049), both active, no bypass. `Release source` is not required yet: the check does not
exist until stage 4, which adds it to the `main` ruleset.

A repository ruleset on `main` (Settings → Rules → Rulesets), rather than classic branch protection:

- Require a pull request before merging; **0 required approvals** (a solo owner cannot approve their
  own PR); allowed merge method: **merge commit** only.
- Require status checks: `Build (CraftEngine pinned)` and a new `Release source` check (stage 4).
  `Build (CraftEngine latest)` stays optional: an upstream snapshot break must not block a release.
- Require the branch to be up to date before merging.
- Block force pushes and deletion.
- Bypass list: empty, or the owner only "for pull requests" (an emergency escape that still leaves
  a PR on record). Recommended: empty.

A lighter ruleset on `develop`: block force pushes and deletion only. Direct pushes stay allowed.

### Stage 3: dev builds from `develop` (done)

Done: `.github/workflows/dev-build.yml`. The owner chose **A and B**: each push to `develop`
(docs-only and `*.md` changes skipped) replaces the rolling `dev` pre-release and uploads the jar to
the Hangar Snapshot channel. `plugin/build.gradle.kts` takes `-Phangar_changelog` so a dev build's
Hangar changelog links its commit (a dev build has no release page). Maven snapshots (C) stay for
later. Owner follow-up: a newer push cancels a run still in its `build` job (concurrency group
`dev-build-build`, cancel in progress); the `publish` job (group `dev-build-publish`) is never
cancelled, so the `dev` pre-release and Hangar are never left half done. Tested: the first run's
build was cancelled and its publish skipped. Same in Mekanized.

New workflow `dev-build.yml`, on push to `develop` (and by hand):

- Version: `<next>-dev.<run number>` taken from `gradle.properties` (e.g. `0.2.1-dev.57`), passed as
  `-Pversion`. It never matches a release tag, so it never triggers `release.yml`.
- Build and test, `:api:japicmp`.
- Publish to (owner picks, see open questions):
  - **A. Rolling GitHub pre-release** `dev`: the tag `dev` is moved to the commit and the jar
    replaced on each push. One stable download link, no clutter. (Recommended.)
  - **B. Hangar Snapshot channel**: a hyphenated version already goes there. Good for server owners
    who want dev builds, but one Hangar version per push is noisy; could be limited to a
    `workflow_dispatch` or a daily run.
  - **C. Maven snapshots**: publish `0.2.1-SNAPSHOT` to `1robie/maven` so addon authors (Mekanized)
    can build against unreleased API. The repository grows with every push; would need pruning.
- The existing artifact upload in `build.yml` stays (every branch, 90-day retention).

### Stage 4: guard releases (done)

Done: `.github/workflows/release-source.yml` (job `Release source`, also rejects PRs from forks),
now required by the `main` ruleset next to `Build (CraftEngine pinned)`; `release.yml` and
`maven.yml` check out full history and stop unless the tagged commit is on `origin/main` (0.1.0 and
0.2.0 pass). No tag ruleset: tags stay by hand, owner only in practice.

- **`Release source` check**: a small job in `build.yml` (or its own workflow) on `pull_request`
  to `main` that fails unless the head branch is `develop` or `hotfix/*`. GitHub rulesets cannot
  restrict the source branch on their own.
- **`release.yml` and `maven.yml`**: a first step that fails unless the tagged commit is on `main`
  (`git merge-base --is-ancestor "$GITHUB_SHA" origin/main`, after fetching `main`). A tag pushed
  from `develop` by mistake then publishes nothing.
- Optional: a tag ruleset so only the owner can create tags matching `[0-9]*`.

### Stage 5: release procedure (CONTRIBUTING.md) (done)

Done: CONTRIBUTING.md has a "Branches" section and the release and hotfix steps below.

Replace "Releases" with:

1. On `develop`: in CHANGELOG.md turn "Unreleased" into the version and date.
2. Open the PR `develop` → `main` titled `Release 0.2.1`; wait for the checks; merge (merge commit).
3. Tag the merge commit on `main`: `git tag 0.2.1 origin/main && git push origin 0.2.1`.
   `release.yml` and `maven.yml` do the rest, as today.
4. On `develop`: raise `version` to the next `-SNAPSHOT` and `apiBaseline` in
   `api/build.gradle.kts` to the version just released.

Hotfix: same, from a `hotfix/x.y.z` branch cut from `main`, then merge `main` into `develop`.

Optional later: a `workflow_dispatch` "Prepare release" workflow that does steps 1-2 (changelog,
PR) and a job on PR merge that tags automatically.

### Stage 6: docs and tooling (done)

Done: README "Dev builds" paragraph, D48 in docs/ARCHITECTURE.md, the memory note for Claude Code
sessions. Follow-up (2026-10-22): Mekanized has the same model (develop default, rulesets 25622958 and
25622960, `Release source`, `dev-build.yml`, tag guard in `release.yml`, README "Branches and
releases"; its version raised to 0.2.0-SNAPSHOT, 0.1.0 being out), and the documentation site's
install pages link the dev builds of both.

- CONTRIBUTING.md: the branch model, "PRs target `develop`", the release steps above.
- README: where dev builds are (the `dev` pre-release / Hangar Snapshot channel), marked unstable.
- `hangar-page.yml` stays on `main`, so the Hangar page follows released READMEs only.
- Decision log: a D48 entry in docs/ARCHITECTURE.md once the owner confirms the choices.
- Claude Code sessions: worktrees and PRs should be cut from and target `develop`.
- Mekanized (own repo): same model later, as a separate step.

## Open questions for the owner

1. Dev builds go where? A (rolling GitHub `dev` pre-release), B (Hangar Snapshot), C (Maven
   snapshots), or a mix. Recommended: A now, C when Mekanized needs unreleased API.
2. `develop` as the GitHub default branch? Recommended: yes.
3. Bypass on the `main` ruleset: none, or owner-only via PR? Recommended: none.
4. Automatic tagging on merge to `main`, or keep tagging by hand? Recommended: by hand first.
