# Player data plan (2026-10-08, in progress)

MachineEngine has no object for a player. Per-player state is spread across addon code, in different forms:

| Where | What | How it is kept today |
|---|---|---|
| `PaperRadiation` (Mekanized) | radiation dose, in Sv | `mekanized:radiation_dose`, a `DOUBLE` in the player's PDC, removed on death |
| `SuitTicker` (Mekanized) | players the plugin let fly; players whose step height the servos raised | two `Set<UUID>` fields, never cleaned on quit |
| `PaperAutomation` (Mekanized) | last teleport time (teleporter cooldown) | a `Map<UUID, Long>` field, never cleaned on quit |
| `MachineDebugView` (engine) | open debug views | a `Map<UUID, Task>` field |

Each place picks its own key, its own type and its own cleanup. Nothing can be inspected or reset by an admin, nothing shows in a menu, and players cannot change anything about how the content treats them (for example hiding the radiation action bar).

This plan adds **one engine object per player, `MachinePlayer`**, holding typed values that any addon declares (`PlayerDataKey`), some of which players may change themselves (`PlayerSetting`). Saving goes through a **pluggable storage backend**: the player's PDC by default, per-player files as a built-in alternative, and room for an addon to add another (SQL, …). Mekanized moves its radiation dose and its `UUID` maps onto it and adds its first player settings.

## Decisions

Owner, 2026-10-08:
1. **An engine feature in `api`**, like multiblocks (D33), not a Mekanized class: any addon declares its own keys; Mekanized is the first user.
2. **Pluggable storage.** An interface, `PlayerDataStorage`, with the player's PDC as the default backend and per-player files as a second built-in one. Addons may register their own.
3. **Players change their settings with a command and a zMenu menu** (placeholders and a button type in `hooks:zmenu`).

Delegated (confirmed by the owner, 2026-10-08; recorded as D37):
4. **Keys are Adventure `Key`s** (D6) in the owning addon's namespace (`mekanized:radiation_dose`), registered in a static registry that freezes with the others (D5, D13).
5. **Unknown keys are kept** (D3): a saved value whose key is not registered (addon removed) stays in the saved data, untouched, and is written back.
6. **Values are immutable and go through `VersionedCodec`** (`api.data`), the same codecs machine data uses, so a key can migrate its format. `Codecs.DOUBLE` is added (`DataValue.DataDouble` already exists); D10 forbids floating point in transfers only, and a dose is not a transfer.
7. **Two lifetimes:** `PERSISTENT` (saved) and `SESSION` (kept while the player is online, dropped on quit; replaces the `UUID` maps). A key may also **reset on death** (the dose does today).
8. **A player's data is touched on that player's thread** (their entity scheduler on Folia, `Scheduler#run(Entity, …)`), like a machine's on its region. Single reads and writes are safe from any thread (the values sit in a concurrent map), but two threads updating the same value race.
9. **The backend is chosen in `config.yml`** (`players.storage`, restart-only, D30). Switching backend does not move existing data; a `/machineengine players migrate <from> <to>` command is a later, separate step (see *Not in this plan*).
10. **Server owners can set a setting's default and lock it** in `config.yml`: `players.setting-defaults` (`"<namespace>:<id>": <value>`) and `players.locked-settings` (a list of ids). A locked setting keeps the owner's value (or the built-in default) and players cannot change it.
11. **The player command is its own root, `/machinesettings`** (alias `/msettings`, permission `machineengine.settings`): every `/machineengine` sub-command inherits the root's `machineengine.admin` permission (Paper-Dispatch puts it on the root node).

## Design

### API (`fr.robie.machineengine.api.player`)

```java
// A value kept per player.
public final class PlayerDataKey<T> implements Keyed {
    static <T> PlayerDataKey<T> persistent(Key id, VersionedCodec<T> codec, T defaultValue);
    static <T> PlayerDataKey<T> session(Key id, T defaultValue);         // never saved, no codec needed
    PlayerDataKey<T> resetOnDeath();
    PlayerDataKey<T> migratePdc(String oldPdcKey, PersistentDataType<?, T> type); // read once at join, then removed
}

// A per-player value players change themselves. Its value is saved as text, so no codec to write.
public final class PlayerSetting<T> implements Keyed {
    static PlayerSetting<Boolean> bool(Key id, boolean defaultValue);                       // a click toggles
    static <E extends Enum<E>> PlayerSetting<E> choice(Key id, E defaultValue);             // a click cycles
    static PlayerSetting<Long> whole(Key id, long defaultValue, long min, long max, long step);
    static PlayerSetting<Double> decimal(Key id, double defaultValue, double min, double max, double step);
    PlayerDataKey<T> data();                                             // a persistent key of the same id
    @Nullable T parse(String text); String format(T value); T step(T current, boolean forward);
    String nameKey(); String descriptionKey();                           // setting.<ns>.<id>[.description] (D23)
}

// The object for one player.
public interface MachinePlayer {
    UUID uuid();
    @Nullable Player player();                                           // null for offline data (loadOffline)
    <T> T get(PlayerDataKey<T> key);
    <T> void set(PlayerDataKey<T> key, T value);
    <T> T update(PlayerDataKey<T> key, UnaryOperator<T> change);
    void reset(PlayerDataKey<?> key);
    <T> T get(PlayerSetting<T> setting);                                 // owner lock/default applied
    <T> Result<T> set(PlayerSetting<T> setting, String text);            // parsed and checked; refused when locked
    Set<Key> unknownKeys();                                              // kept values of missing addons (D3)
}

public interface MachinePlayers {                                        // MachineEngine.get().players()
    MachinePlayer get(Player player);                                    // online players, from join to quit
    Optional<MachinePlayer> find(UUID uuid);
    Collection<MachinePlayer> online();
    CompletableFuture<Optional<MachinePlayer>> loadOffline(UUID uuid);   // empty if the storage cannot (PDC)
    CompletableFuture<Void> saveOffline(MachinePlayer player);
    PlayerDataStorage storage();
}

// Where PERSISTENT values go. Implementations get and return the whole saved DataMap (DataBinary makes bytes).
public interface PlayerDataStorage {
    CompletableFuture<DataMap> load(UUID uuid, @Nullable Player online);
    CompletableFuture<Void> save(UUID uuid, @Nullable Player online, DataMap data);
    default boolean needsOnlinePlayer() { return false; }               // true: loaded at join, no offline players
    default void close() {}
    interface Factory { PlayerDataStorage create(Plugin machineEngine, ConfigValues config); }
    record Type(Key key, Factory factory) implements Keyed {}
}
```

Entry points: `MachineEngine.get().players()`; registration in an addon's callback through `RegistrationContext`: `playerData().register(key)`, `playerSettings().register(setting)`, `playerStorages().register(Key, Factory)`. New static registries `Registries.PLAYER_DATA`, `PLAYER_SETTING`, `PLAYER_STORAGE`. Engine events (D12, not on hot paths): `PlayerEvents.SETTING_CHANGED` (a player or admin changed a setting). `DataBinary` moves from `common` to `api.data` so addon storages can turn data into bytes. The platform registers its storages through `MachineEngineBuilder#playerStorage`, before any addon.

### Saved format

One `DataMap`: `{"version": 1, "values": {"<namespace>:<id>": <encoded value>, …}}`, written with `DataBinary`. Each value is encoded with its key's `VersionedCodec`, so a key migrates on its own. Unknown keys (D3) are copied through as raw `DataValue`s.

### Backends

- **`machineengine:pdc`** (default): one `BYTE_ARRAY` under `machineengine:player_data` in the player's PDC. Load on join and save at the save points below, both on the player's thread, synchronous (completed futures); vanilla writes it with the player's `.dat` file, so it is saved atomically with the inventory. `supportsOffline() == false` [VERIFY: whether 1.20.6 Paper can read an offline player's PDC; if not, offline admin commands answer "not supported by this backend"].
- **`machineengine:files`**: `plugins/MachineEngine/players/<first two hex digits>/<uuid>.dat`. Loaded asynchronously in `AsyncPlayerPreLoginEvent` (the player waits at login, never in the world without data); written asynchronously (`Scheduler#runAsync`) to a temporary file and moved over the old one. Writes for the same UUID are chained, and a load waits for a pending write, so a quick rejoin reads the latest data.
- **Addon backends** register a `Factory` under their own key and are chosen with `players.storage: <key>`.

### Lifecycle (common, package `paper`)

- **Join**: build the `MachinePlayer` from the loaded data, run PDC migrations (`migratePdc`: read the old key, store the value under the new key, remove the old key), reset `resetOnDeath` keys if the player joins dead.
- **Save points**: every `players.save-interval` seconds (default 60) for players with changes, on quit, and on engine disable (synchronously for every online player).
- **Quit**: save, then drop the object and all `SESSION` values.
- **Death**: reset the `resetOnDeath` keys.
- **Reload** (`/machineengine reload config`): setting defaults and locks swap; the backend stays (restart-only).

### Commands

- Players: `/machinesettings` lists their settings (name, value, description, translated, D23); `/machinesettings <setting> <value>` changes one; `/machinesettings <setting> reset`. Opens the zMenu menu instead of listing when zMenu is installed and the player gave no argument. Permission `machineengine.settings` (default: everyone).
- Admins: `/machineengine player <player> get|set|reset <key> [value]`, any key including non-settings, offline players when the backend supports it. Permission `machineengine.admin.player`.

Mekanized registers no command of its own for this; its settings appear in `/machinesettings` under its namespace.

### zMenu (`hooks:zmenu`)

- Placeholders `%zmenu_machineengine_player_<namespace>_<id>%` (a key's value, formatted by its setting's `ConfigKey` when it has one) and `…_player_<namespace>_<id>_name%` (the translated name).
- Button type `machineengine_player_setting` (`setting: mekanized:radiation_hud`): a click toggles a boolean, cycles a choice, or steps a number by `step:` (left adds, right removes), within its range.
- The engine writes `player_settings.yml` with one button per registered setting (up to 28, in a bordered grid), rewritten at every start while its first line is MachineEngine's marker; an owner who edits it deletes that line. Names and lore use `<lang:setting.<namespace>.<id>>`.

### Mekanized

| Key | Kind | Replaces |
|---|---|---|
| `mekanized:radiation_dose` (double, Sv) | persistent, reset on death, `migratePdc("mekanized:radiation_dose", DOUBLE)` | `PaperRadiation.DOSE` in the PDC |
| `mekanized:flying` (bool) | session | `SuitTicker.flying` |
| `mekanized:raised_step` (bool) | session | `SuitTicker.stepping` |
| `mekanized:last_teleport` (long, ms) | session | `PaperAutomation.lastTeleport` |
| `mekanized:radiation_hud` (bool, default true) | setting | new: show the MekaSuit Geiger/dosimeter action bar |

Further settings candidates (each only if it maps to something the gear already does; decided in Stage 5): MekaSuit module toggles that are per player rather than per item, such as the magnetic attraction unit and the step-assist height.

The dosimeter and the `%…_player_mekanized_radiation_dose%` placeholder read the same value, so a menu can show a player their dose.

## Stages

Each stage builds, passes `check`, and is committed on its own.

0. **Decisions.** *Done.* Owner confirmed 4–10 above; **D37** in the decision log, *Players* in ARCHITECTURE §23.3, the keys in §23.5. Checked: 1.20.6 Paper has no `OfflinePlayer#getPersistentDataContainer` (26.x has only a read-only view), so the PDC storage cannot serve offline players.
1. **API and headless core.** *Done.* `api.player` types, the three registries, `Codecs.DOUBLE`, `MachinePlayer` implementation in `common` (`core.player`), the saved format and unknown-key passthrough. An in-memory backend in `test` (`MemoryPlayerStorage`, the headless default). Tests (`PlayerDataTest`): codec round trip, unknown keys kept, session keys never saved, defaults and locked settings, setting parsing and range through `ConfigKey`.
2. **Paper lifecycle and the PDC backend.** *Done* (`paper.player.PaperPlayers`, `PdcPlayerStorage`; dev-checked on Paper 26.3 and 1.20.6: the storage is picked and listed, no errors). The listener (join, quit, death, save timer, disable), `players.storage` / `players.save-interval` / `players.settings` in `EngineConfig.SPEC`, PDC migrations. Headless dev-server check: the engine starts with `players.storage: pdc` and reports the backend.
3. **File backend, addon backends, offline load.** *Done* (`FilePlayerStorage`, `FilePlayerStorageTest`; dev-checked on Paper 26.3 with `players.storage: machineengine:files`). A damaged file is moved aside (`<uuid>.dat.damaged-<time>`) and the player starts empty. `machineengine:files` with atomic writes and per-UUID ordering; `playerStorages().register`; `loadOffline`. Tests: write/read, a crash between temp file and move keeps the old file, a load waits for a pending write. Headless dev-server check with `players.storage: files`.
4. **Commands, events and zMenu.** *Done* (`PlayerSettingsCommand`, `PlayerAdminCommand`, `PlayerValues`, `PlayerSettingButton`, `PlayerSettingsMenu`; dev-checked on Paper 26.3 from the console; in-game clicks are the owner's check). `PlayerDataKey#display` formats a value for placeholders and admin output. The menu is rewritten at every start while its first line is MachineEngine's marker. `/machinesettings`, `/machineengine player`, the placeholders, the button type and `player_settings.yml`, lang entries in the engine pack (D23).
5. **Mekanized.** The keys above; `PaperRadiation`, `SuitTicker` and `PaperAutomation` use them; the PDC code and the `UUID` fields go. The radiation HUD obeys `mekanized:radiation_hud`. Tests: dose migration from the old PDC key, dose reset on death, existing radiation tests unchanged.
6. **Docs.** MEKANIZED_GUIDE (settings, placeholders), README (config keys, permissions), memory note.

Real-player behaviour (join/quit timing, death, menus) cannot run on a headless dev server; Stages 2, 4 and 5 end with a short manual check on the dev server with a client, listed in the commit message.

## Not in this plan

- Moving data between backends (`/machineengine players migrate`).
- Data shared between players (teams, frequencies owned by a player): Mekanized's frequencies stay where they are.
- Per-player data for non-player entities.
- Cross-server sync for networks of servers: a SQL or Redis backend can be written by an addon on top of `PlayerDataStorage`, but locking a player across servers is not designed here.

## Risks

- **PDC backend and offline players**: admin commands on offline players may not work with the default backend ([VERIFY] in Stage 2). The command says so instead of failing silently.
- **Async load at login (files)**: a slow disk delays logins; the load has a timeout (`players.load-timeout`, default 5 s) after which the player is refused with a message rather than let in with empty data that would overwrite theirs.
- **Folia**: every read and write on the player's own thread; the save timer schedules each player's save on that player's scheduler, not one global loop.
