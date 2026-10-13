# MessageFlow plan (2026-10-13, in progress: stages 1-7 done, MessageFlow 1.1.1 released)

MachineEngine's text and logging are spread across three systems:

| What | How it is done today | Size |
|---|---|---|
| Player messages (chat, action bar) | `common/.../paper/Messages.java`: `Component.translatable(key, §-fallback)`, translated **client-side** from CraftEngine `lang` entries (`plugin/src/main/resources/craftengine-pack/configuration/lang.yml`, `en_us` + `fr_fr`, D23) | 26 keys in Java, 3 more only in menu YAML |
| Admin and command output | Hardcoded English `Component.text(..., NamedTextColor)` in `MachineEngineCommands`, `MachineStorageCommands`, `PlayerAdminCommand`, `EngineChecks`, `MachineDebugView` | ~70 call sites |
| Refusals (`MachineAction.Outcome`) | Hardcoded English `Component.text` in `api/.../upgrade/UpgradeActions.java` and `core/machine/MachineInstanceImpl.java` | 6 |
| Logging | `java.util.logging.Logger`, injected from `plugin.getLogger()` (`MachineEngineBuilder.logger`, `CoreMachineEngine.start`, `MachineServices.logger()`), plus `Bukkit.getLogger()` in `BlockConfigOptions` | ~70 call sites: api 7, common ~41, hooks:zmenu ~10, plugin ~12 |

Known defects found while mapping this: `machineengine.upgrade.installed` (`UpgradeItemBehavior.java:60`) has no `lang.yml` entry; nothing an admin types can change the English command output.

This plan moves **every message and every log line onto MessageFlow** (`github.com/1robie/MessageFlow`, owned by the same owner). MessageFlow first gets the features MachineEngine needs: **per-player language with a default-language fallback**, a Component API, safe placeholders, a reload-safe lifecycle, a logger that works everywhere, and 1.20.6 compatibility. Then MachineEngine adopts it.

## Decisions

Owner, 2026-10-13:
1. **MessageFlow learns per-player languages.** Each language has its own file. A message is picked from the player's client locale, falling back to a default language set by the server owner. This replaces client-side CE translations for messages, and amends D23.
2. **Logging goes through MessageFlow everywhere.** That covers `core`, `paper`, `craftengine`, `hooks:*` and `plugin`. The exception is the `api` signature in item 9 below.
3. **MessageFlow stays out of `api`**, like Paper-Dispatch and FoliaLib (D35). It is bundled and relocated in `common`. Addons such as Mekanized shade their own copy if they want it, in a separate Mekanized plan.

Delegated (confirmed by the owner, 2026-10-13; to be recorded as D44 and D45 in B6):
4. **Library changes ship as MessageFlow 1.1.0 on JitPack.** While it is in progress, MachineEngine builds against `mavenLocal()`. It is then pinned to the tag, or to a commit as Paper-Dispatch is today.
5. **Messages are an enum, `EngineMessage implements Message`**, in `common` (package `paper.message`). It replaces the factories in `Messages.java`. **Keys stay the same** (`machineengine.settings.changed`, …), so existing translations move over 1:1.
6. **Language files go in `plugins/MachineEngine/lang/<locale>.yml`** (`en_us.yml`, `fr_fr.yml`). They are copied from the jar on first start and synced afterwards:
   - Missing keys are added to the default language only. Other languages fall back to the default language and never get English text written into them.
   - The fallback chain is: exact locale (`fr_ca`) → language (`fr_*`, the first file found) → default language → the code default.
   - The console and anything without a player use the default language.
7. **MessageFlow is configured from `config.yml`, not `messageflow/messageflow.yml`** (the cleanup plan already chose config.yml for settings). New keys:
   - `messages.default-language: en_us`
   - `messages.prefix`
   - `logging.debug: false`

   MachineEngine sets `BYPASS_EXTERNAL_CONFIG=true`, so it writes no second settings file.
8. **`Component.translatable` keeps working from code that can't see MessageFlow** (`api`, and addon-facing refusals). MessageFlow registers an Adventure `Translator` into `GlobalTranslator`, so Paper renders `machineengine.*` translatable components server-side from the language files, for each player. Keys that MessageFlow doesn't know fall through to the client, which means CE `lang`.
9. **Item lore and zMenu menus stay on CE `lang`** (client-side). Item stacks are shared between viewers, and the server doesn't re-render lore per player. This covers `machineengine.lore.*`, `contents.*` lore, the setting names and descriptions shown in menus, and the `<lang:…>` tags in zMenu YAML. `lang.yml` keeps only those keys.
10. **`api` keeps `java.util.logging.Logger` in `ConfigFile.load(Path, spec, Logger)`.** It is addon-facing (Mekanized passes its own logger) and must not expose MessageFlow types (item 3). Inside the engine the argument is a JUL logger that forwards to MessageFlow's logger (stage B4).
11. **Admin command output becomes messages too** (D23 allowed it to stay English). It gets keys under `machineengine.admin.*`. `MachineDebugView`'s live action bar stays as code-built components: it is a developer tool, and it is redrawn every tick.

## Design

### Part A — MessageFlow 1.1.0 (`C:\Users\clare\IdeaProjects\MessageFlow`)

**A1. Build and publishing**
- **Coordinates.** The published coordinate is `com.github.1robie.MessageFlow:message-flow`. The README's `com.github.1robie:message-flow` returns "Repo not found" (checked against JitPack today). Fix the README.
- **Published pom.** Set `createDependencyReducedPom=true` in `core/pom.xml`. Today the published pom still declares compile dependencies on `common` and `BOM`, so consumers download those classes twice.
- **`jitpack.yml`.** Pin `openjdk21`.
- **1.20.6 compatibility.** MachineEngine's `verifyBundledPaperBaseline` checks bundled code against the 1.20.6 API, and these references will fail it:
  - `org.bukkit.Sound` is an interface in 1.21.11 but an enum in 1.20.6, so `SoundMessage`'s `sound.key()` compiles to `invokeinterface` and throws `IncompatibleClassChangeError` on 1.20.6. *Done:* it calls `((Keyed) sound).getKey()`, which is an interface call on both. That was the only problem MachineEngine's `VerifyPaperBaseline` found in the jar.
  - The `getPluginMeta()` fallback must catch `NoSuchMethodError` (it catches `Exception` today). *Done:* it catches `Exception | LinkageError`.
  - Keep compiling against 1.21.11. The 1.20.6 check is MachineEngine's `verifyBundledPaperBaseline` from stage 7 on; MessageFlow gets no check of its own.

**A2. Lifecycle, state and bug fixes**
- **Logger singleton.** The `Logger` singleton must no longer throw on a second `MessageManager`; the newest manager replaces it. Add `MessageManager.close()`, which unregisters the translator (A4) and releases the logger. *Done:* a logger of the same plugin replaces the global one; a logger of another plugin still throws, because that means an unrelocated copy. `IMessageManager#close()` and `Logger#close()` exist. A closed logger that was already replaced does nothing.
- **Global placeholders.** `reload()` stops calling `GlobalPlaceholderRegistry.clear()`. Today every reload, including the first, wipes the registered globals.
- **Thread safety for Folia.** Sends come from region threads while `/reload` runs on the global thread:
  - Loaded messages become one immutable snapshot per language, swapped atomically on reload (a `volatile` reference). *Moved to A4:* the loaded lists still live in the plugin's own `Message` objects (`setLoaded`), which the library can't make `volatile`. A4 moves them into the manager, per locale, and the snapshot comes with that. Until then a reader sees the old list or the new one, never a mix: each list is swapped as one reference.
  - `TextResolverRegistry` becomes a `CopyOnWriteArrayList` in registration order. This also fixes the documented resolver order, which is random today because it is a `HashSet`.
- **Cheap bug fixes from the review:**
  - `clearCache()` must call `invalidateAll()`.
  - The prefix placeholder name: `%plugin-full-name%` vs `plugin-full`.
  - `MessageBuilder.send` mutates shared adapters. *Dropped:* the adapters it changes are the builder's own, made by `chat()`, `title()` and so on, so nothing shared is touched.
  - `BROADCAST` ignores the recipient settings.
  - The legacy boss-bar duration unit.
  - The README samples are stale.
  - *Also done:*
    - The message cache is used again. It was skipped whenever a resolver was registered, which was always. It is now skipped only when a resolver would change that text.
    - The legacy formatter plays sounds and no longer converts colors twice.

**A3. Component API and safe placeholders**
- **Component API.** Add:
  - `Component component(Message, @Nullable Audience viewer, Placeholder…)`
  - `List<Component> components(…)`, for multi-line messages and lore-like use
  - `send(…)` overloads that take an `Audience`, not only a `CommandSender`

  `MachineAction.Outcome`, Brigadier errors and action bars need a `Component` rather than a send.

  *Done, with different names:*
  - `MessageFormatter#formatLines(Message, @Nullable Player, Placeholder)` returns `List<V>`. It covers chat, action-bar, broadcast and no-prefix lines; titles, boss bars and sounds are skipped.
  - `#formatMessage(...)` returns those lines joined by line breaks.
  - `MessageManager#adventure()` returns the formatter typed for `Component`.
  - The viewer is a `Player`, not an `Audience`, because placeholders take a `Player`.
  - The `Audience` send overloads are dropped: every recipient MachineEngine has is a `CommandSender`, which on Paper is already an `Audience`.
- **Safe placeholders.** `%key%` stays the YAML syntax. Each placeholder is turned into a MiniMessage `TagResolver` before parsing, instead of replacing raw text:
  - Values are inserted unparsed, which stops `<click:…>` injection from player names or item names.
  - `Placeholder.component(key, Component)` is new.
  - Player placeholders get the viewer. Today local `Function<Player,String>` placeholders always resolve to `""`.
  - *Done:*
    - Local placeholders become `<mfph_N>` tags, resolved by MiniMessage's `Placeholder.component`.
    - A placeholder inside a tag argument (`<click:run_command:'/tp %player%'>`) can't be a tag, so it stays text, with `< > ' " \` removed.
    - Global placeholders and PlaceholderAPI still substitute text before parsing. Their values come from the plugin or from PAPI expansions, not from players, and PAPI output often carries legacy colors on purpose.
    - Values are no longer read as MiniMessage or legacy codes. This is a behaviour change, noted in MessageFlow's CHANGELOG.

**A4. Per-player languages**
- **Language set.** `LanguageConfiguration` loads all its languages at once instead of one active language. Each message keeps a `Map<Locale, LoadedMessage>`.
- **Locale lookup.** The locale comes from `Player.locale()` (available on 1.20.6) and runs through the fallback chain in decision 6.
- **Default language.** `MessageManager.defaultLanguage(Locale)` sets it; the console uses it.
- **Sync.** Missing keys are added to the default file only (a setting).
- **Translator bridge.** `MessageManager.registerTranslator(String keyPrefix)` adds a `net.kyori.adventure.translation.Translator` to `GlobalTranslator`:
  - It renders `TranslatableComponent`s whose key starts with the prefix, using that locale's file.
  - The `%s` / `{0}` arguments map to `%0%`, `%1%` placeholders.
  - [VERIFY] Check which `Translator#translate(TranslatableComponent, Locale)` default method exists in the Adventure that 1.20.6 ships (4.17). If it is missing, return a `MessageFormat` and pre-render the arguments.
- **Unchanged default.** Single-language plugins (`withSingleFile`) keep today's behaviour.
- *Done, as follows:*
  - **Switch.** `MessageManager#setPerPlayerLanguages(true)` turns it on (off by default).
  - **Default language.** The default language is the *active* language (`setActiveLanguage`), not a new `defaultLanguage(Locale)`. Its text stays in each `Message#loaded()`.
  - **Other languages.** They are parsed into an immutable `Translations` snapshot. It is the formatter's `MessageLocalizer` and is swapped as one reference on reload, which covers the thread-safety item moved here from A2.
  - **Sending.** A send makes one pass per language among its recipients: the senders, the online players if the message broadcasts, and the default language. Each pass reaches only its own recipients.
  - **Language files.** A language without a file is skipped. Another language's file is created only from a bundled resource, never from the English defaults.
  - **Adventure versions.** The [VERIFY] is answered:
    - `Translator#translate(TranslatableComponent, Locale)` exists in Adventure 4.17 (Paper 1.20.6) and in 5.2.
    - **Paper 26 ships Adventure 5.2**, which removed `TranslatableComponent#args()`. The bridge reads `arguments()`, which both versions have.
    - In 4.17, a translator's result is used as is. The bridge therefore keeps the translatable's style and children itself.
    - 5.2 asks `canTranslate` first. The bridge answers that cheaply, by key.
  - **Version check.** The scratch API check now runs against both 1.20.6 and 26.3. The tests run on the Adventure of Paper 1.21.11, so the 4.17 and 5.2 code paths still need the dev-server check in stage 9.

**A5. Logger**
- **Usable before and without a manager.** Each logger wraps a `java.util.logging.Logger` (the plugin's). Before a manager exists, and in unit tests, lines go straight to JUL instead of being dropped. Once a manager exists, lines are formatted (prefix, colors, context) and sent to the console.
- **`toJul()`.** Add a JUL `Logger` view (a `Handler`) for APIs that require JUL, such as `ConfigFile` (decision 10).
- **Levels.** `DEBUG` lines are skipped unless debug is on, and the message string is not even built: a `Supplier<String>` overload avoids string work on the tick path.
- **Exceptions.** They log the stack trace through JUL's `Level.SEVERE` with the throwable attached, rather than as red text, so log files and panels show them properly.
- *Done, as follows:*
  - **No manager.** Static calls made without a manager go to the `MessageFlow` JUL logger. Before this they were dropped, and the placeholder-registry tests now show warnings that were always being lost.
  - **`Logger.jul(julLogger)`.** It makes a logger that is not global. Its lines are plain: placeholders are replaced, and tags are stripped from the template but kept in the values. This is what `TestEngine` will use (B4).
  - **The manager's logger.** It keeps sending lines to the console with colors. A line with an exception goes, as plain text, to the plugin's own `getLogger()` at the level of its type (error → `SEVERE`, warning → `WARNING`).
  - **`toJul()`.** It is an anonymous JUL logger with a forwarding handler. Below `INFO` counts as debug. Record text (with `{0}` parameters filled) goes in as a placeholder value, so it is never read as tags.
  - **Debug suppliers.** `Logger.debug(Supplier)` and `logDebug(Supplier)` build nothing when debug is off. Debug lines use JUL `INFO`, because default handlers hide `FINE`.
**A6. Tests and release**
- **New tests:**
  - YAML parsing for each value shape
  - locale fallback chain
  - the translator bridge
  - placeholder escaping
  - reload swap
  - logger fallback

  Paper's API is mocked as in the existing tests; MockBukkit is avoided.
- **Release.** Update the CHANGELOG and README (per-language section), then tag `1.1.0`.
- *Tests done:*
  - `YamlParsingTest`: every value shape (string, list, typed map, list of maps with per-entry settings, wrapped `messages:` with shared settings, dotted keys), an unknown type falling back to the defaults, a missing key written with its default, and `reload()` picking up edited files for per-player languages too.
  - Placeholder escaping, the locale fallback, the translator and the logger were covered in stages 3–5.
  - MessageFlow has 73 tests in all.
  - Sounds are left untested: `Registry.SOUNDS` needs a running server.
- *Released as 1.1.0* (tag `1.1.0`, commit `c3961ff`).
  - **The owner's own additions went in too.** They cover shared formatting, the template and global caches, placeholder modifiers and plurals, Folia scheduling with `Tasks` and `sendMessageAsync`, and JMH benchmarks (in the `benchmarks` profile, so not part of the JitPack build).
  - **Review fixes made before tagging:**
    - The template cache named `VirtualComponent`, and `FoliaTasks` called `Bukkit.isGlobalTickThread()`. Neither exists on 1.20.6, so both would have crashed there. They are now looked up by reflection.
    - Custom `TextResolver`s are now player-dependent by default. Before, `{name}`-style resolvers were formatted once with no player.
  - **1.1.1** (tag `1.1.1`) fixes every remaining review item, at the owner's request:
    - An unknown `%key%` keeps its closing `%`.
    - Texts changed by resolvers, or with a value inside a tag, get their own small cache that expires.
    - In-tag values also lose `%`, `&` and `§`.
    - Modifier arguments keep capitalized words, and `pad` is capped at 256.
    - Marker characters are stripped from resolver output.
  - **MachineEngine uses 1.1.1.** The 1.1.0 tag stays as it is.
  - **For part B:** `Tasks` and `sendMessageAsync` overlap with MachineEngine's own scheduling (FoliaLib, D35). The engine keeps its own `Scheduler` and calls MessageFlow sends on the thread it already owns.

### Part B — MachineEngine

**B0. Build wiring.** The uncommitted wiring is wrong in three places:
- **Coordinate.** `libs.versions.toml` should be `com.github.1robie.MessageFlow:message-flow`.
- **JitPack repository filter.** `settings.gradle.kts` uses `includeGroup("com.github.1robie")`, and `includeGroup` is an **exact** match. So it no longer matches `com.github.1robie.paper-dispatch` or `com.github.1robie.MessageFlow`. Use `includeGroupByRegex("com\\.github\\.1robie(\\..+)?")`.
- **Relocation.** `plugin/build.gradle.kts` has no relocation for MessageFlow. Add `relocate("fr.robie.messageflow", "fr.robie.machineengine.libs.messageflow")` and add `fr/robie/messageflow/` to `verifyRelocation`.

Then:
- `verifyBundledPaperBaseline` must pass. Fix MessageFlow (A1) rather than adding allow-list entries.
- Add `mavenLocal()` for `com.github.1robie.MessageFlow` while A is in progress, and remove it after pinning.
- *Done (2026-10-13), as follows:*
  - **Version.** 1.1.1 was already released, so no `mavenLocal()` was needed.
  - **Repository filter.** The JitPack filter is `includeGroupByRegex("""com\.github\.1robie\..+""")`, a Kotlin raw string. Paper-Dispatch still resolves through it.
  - **`./gradlew check` passes.**
    - The bundled 1.20.6 check covers 115 classes, with the same 13 allowed problems as before and none new for MessageFlow.
    - `verifyRelocation` finds no `fr/robie/messageflow/` entries.
    - The plugin jar holds 64 classes under `fr/robie/machineengine/libs/messageflow/`.

**B1. Engine messages** (`common`, package `fr.robie.machineengine.paper.message`)
- **Enum.** Add `EngineMessage implements Message` with every chat and action-bar key now in `Messages.java` and `Messages.message(...)` calls. Each entry keeps its English default in code.
- **Bundled language files.** Bundle `lang/en_us.yml` and `lang/fr_fr.yml`. The French text moves from CE `lang.yml`, with `%s` turned into named placeholders (`%machine%`, `%count%`).
- **`EngineMessages` service.** It owns the `MessageManager`:
  - it is built in `MachineEnginePlugin.onEnable` before the engine starts, because logging needs it (B4);
  - it is reloaded by `/machineengine reload`;
  - it is closed in `onDisable`.
- **Call sites.** `Messages.java` is removed and its callers use `EngineMessage`: `PlayerSettingsCommand`, `PaperPlayers`, `CraftEngineMachineHost`, `ResourceItemBehavior`, `UpgradeItemBehavior`, `WrenchBehavior`, `StorageReaderBehavior` and `MachineEnginePlugin`.
- **Missing key.** Add the missing `upgrade.installed` entry.
- **Folia.** Sends happen on the thread that already owns the player. That is a rule of the callers, which already hold it.

**B2. Admin output.** Every `Component.text` in these classes becomes an `EngineMessage` under `machineengine.admin.*`:
- `MachineEngineCommands`
- `MachineStorageCommands`
- `PlayerAdminCommand`
- `EngineChecks`
- `PositionArguments` (in `api`: it uses a translatable key, per decision 8)

The `line(label, value)` helpers become one message with `%label%` / `%value%` placeholders.

**B3. Refusals from `api` and `core`.** The four outcomes in `UpgradeActions` and the two in `MachineInstanceImpl` become `Component.translatable("machineengine.upgrade.…", fallback)`. The translator (A4, decision 8) renders them per player. `api` gains no MessageFlow types.

**B4. Logging everywhere.**
- **Core plumbing.** `MachineEngineBuilder.logger(...)`, `CoreMachineEngine.start(..., logger)` and `MachineServices.logger()` take MessageFlow's logger instead of JUL. This reaches `CoreEventBus`, `CoreTransactionManager`, `ValidationRunner`, `CoreMachines` and `CorePlayers`.
- **Paper side.** These classes use it instead of `plugin.getLogger()`:
  - `PaperPlayers`, `YamlDefinitions`, `CraftEngineItemBridge`, `EngineChecks`
  - `CraftEngineMachineHost.logger()` and its users
  - `PdcPlayerStorage`, `FilePlayerStorage`, `CraftEngineProtection`, `CraftEngineUpgradeItems`
  - `NmsLoader`
  - the zMenu buttons and menus
- **Static fallback.** `BlockConfigOptions` drops `Bukkit.getLogger()`.
- **Context.** Log lines carry a context tag for their subsystem (`[core]`, `[craftengine]`, `[zmenu]`, `[players]`).
- **`api`.** `ConfigFile` keeps its JUL parameter. The engine passes `logger.toJul()`.
- **Tests.** `TestEngine` passes a logger with no manager (the JUL fallback, A5), so tests need no server.
- **Debug.** `logging.debug` in `config.yml` drives MessageFlow's debug flag. It replaces any debug `if`s that exist today.

**B5. CE `lang.yml` cleanup.**
- Remove the keys that moved to the language files.
- Keep the lore, menu and setting-name keys (decision 9).
- Existing servers keep their old copy, because `MachineEnginePlugin` never overwrites it. The moved keys are then just unused, which is harmless: server-side translation happens first.
- Note the change in the CHANGELOG.

**B6. Docs.**
- `ARCHITECTURE.md`:
  - **D44**: messages via MessageFlow, per-player language and the translator bridge. It amends D23.
  - **D45**: logging via MessageFlow everywhere except the `api` JUL signature.
  - §23 gets a *Messages* subsection, and the stale "§23.4" javadoc reference is fixed.
- The docs site gets two pages: "Translating MachineEngine" (the `lang/` files, fallback, default language) and "Configuration" (`messages.*`, `logging.*`).

### Mekanized

Not changed by this plan (decision 3). It keeps working: its `Component.translatable("mekanized.…")` keys don't match the engine's translator prefix, so they still go to the client. A later Mekanized plan can shade MessageFlow 1.1.0 with its own `mekanized.` prefix.

## Stages

| # | Repo | Stage | Check |
|---|---|---|---|
| 1 ✅ | MessageFlow | A1 build/publishing + 1.20.6 fixes; version `1.1.0-SNAPSHOT` | `mvn verify`; JitPack builds a snapshot; the pom has no `common`/`BOM` deps |
| 2 ✅ | MessageFlow | A2 lifecycle, thread safety, bug fixes | unit tests: second manager, reload keeps globals |
| 3 ✅ | MessageFlow | A3 Component API + safe placeholders | tests: `<click>` in a value stays text; player placeholder resolves |
| 4 ✅ | MessageFlow | A4 per-player languages + translator | tests: fallback chain, translator renders `fr_fr` |
| 5 ✅ | MessageFlow | A5 logger | tests: logs before manager reach JUL; debug off skips supplier |
| 6 ✅ | MessageFlow | A6 tests, docs, tag 1.1.0 | JitPack build green |
| 7 ✅ | MachineEngine | B0 wiring (1.1.1 from JitPack, no mavenLocal) | `./gradlew check` incl. `verifyRelocation`, `verifyBundledPaperBaseline` |
| 8 | MachineEngine | B4 logging (first: B1 needs the manager built early anyway) | tests pass; dev server log looks right on 1.20.6 and 26.3 |
| 9 | MachineEngine | B1 engine messages | dev server: `/machinesettings` in en_us and fr_fr clients |
| 10 | MachineEngine | B2 admin output | every `/machineengine` sub-command once |
| 11 | MachineEngine | B3 refusals + B5 lang.yml cleanup | upgrade refusal shows in French for a fr_fr client |
| 12 | MachineEngine | pin 1.1.0, drop mavenLocal, B6 docs | `./gradlew check`; docs site builds |

The dev-server checks run headless and with no build while a server runs. The owner checks the French client in game.

## Not in this plan

- Mekanized adopting MessageFlow (its own ~95 hardcoded strings and its 15.8k-line `lang.yml`).
- Translating item lore or zMenu menus server-side: they stay CE `lang` (decision 9).
- A `/machineengine lang` command for players to override their client locale. This could be a `PlayerSetting` later (D37).
- PlaceholderAPI in engine messages. The hook is there in MessageFlow but is off for the engine until someone needs it.

## Risks

- **Two translation layers.** A key present in both the language files and CE `lang` renders from MessageFlow, so stale CE entries are silently ignored. B5 removes them from the bundled file. Old installs keep dead entries.
- **The Adventure `Translator` API differs between 1.20.6 (Adventure 4.17) and 26.3.** This is the [VERIFY] item in A4. If the component-returning method is missing on 4.17, the arguments lose their styling on 1.20.6.
- **`GlobalTranslator` is server-wide.** A relocated MessageFlow in another plugin registers its own translator. The key prefixes (`machineengine.`, `mekanized.`) keep them apart. `close()` must unregister, or `/reload` leaves a stale translator behind.
- **Logging on the tick path.** The MiniMessage formatting is heavier than JUL. Debug lines use suppliers (A5), and hot paths must not log at INFO.
- **JitPack** builds multi-module Maven repos slowly and sometimes fails on the first request. Pinning a commit (as for Paper-Dispatch) is the fallback.
