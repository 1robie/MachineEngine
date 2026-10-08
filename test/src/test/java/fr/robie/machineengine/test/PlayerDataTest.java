package fr.robie.machineengine.test;

import fr.robie.machineengine.api.MachineEngineAccess;
import fr.robie.machineengine.api.config.ConfigKey;
import fr.robie.machineengine.api.config.ConfigValues;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataBinary;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.event.EventPhase;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerEvents;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.player.CorePlayerData;
import fr.robie.machineengine.core.player.CorePlayers;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Per-player data (D37): keys, settings, the saved form, unknown keys, death and offline players. */
class PlayerDataTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e5");

    private static final PlayerDataKey<Double> DOSE = PlayerDataKey.persistent(Key.key("rad", "dose"),
            VersionedCodec.builder(Key.key("rad", "dose"), Codecs.DOUBLE).build(), 0.0).resetOnDeath();
    private static final PlayerDataKey<Long> VISITS = PlayerDataKey.persistent(Key.key("rad", "visits"),
            VersionedCodec.builder(Key.key("rad", "visits"), Codecs.LONG).build(), 0L);
    private static final PlayerDataKey<Long> LAST_TELEPORT = PlayerDataKey.session(Key.key("rad", "last_teleport"), 0L);
    private static final PlayerSetting<Boolean> HUD = PlayerSetting.bool(Key.key("rad", "hud"), true);
    private static final PlayerSetting<Long> VOLUME = PlayerSetting.whole(Key.key("rad", "volume"), 50, 0, 100, 10);

    enum Mode { SLOW, FAST_MODE, OFF }

    private static final PlayerSetting<Mode> MODE = PlayerSetting.choice(Key.key("rad", "mode"), Mode.SLOW);

    @AfterEach
    void resetConfig() {
        MachineEngineAccess.installConfig(null);
    }

    private static CoreMachineEngine engine() {
        return TestEngine.builder().addon("rad", ctx -> {
            ctx.playerData().register(DOSE);
            ctx.playerData().register(VISITS);
            ctx.playerData().register(LAST_TELEPORT);
            ctx.playerSettings().register(HUD);
            ctx.playerSettings().register(VOLUME);
            ctx.playerSettings().register(MODE);
        }).start();
    }

    /** The saved form, through bytes as a storage keeps it. */
    private static DataMap saved(CorePlayers players, CorePlayerData data) throws Exception {
        return DataBinary.read(DataBinary.write(players.encode(data)));
    }

    @Test
    void valuesStartAtTheirDefault() {
        CorePlayers players = engine().players();
        MachinePlayer alex = players.join(ALEX, null, DataMap.EMPTY);
        assertEquals(0.0, alex.get(DOSE));
        assertEquals(0L, alex.get(LAST_TELEPORT));
        assertTrue(alex.get(HUD));
        assertEquals(Optional.of(alex), players.find(ALEX));
    }

    @Test
    void persistentValuesAreSavedAndSessionValuesAreNot() throws Exception {
        CorePlayers players = engine().players();
        CorePlayerData alex = players.join(ALEX, null, DataMap.EMPTY);
        alex.set(DOSE, 1.25);
        alex.update(VISITS, visits -> visits + 1);
        alex.set(LAST_TELEPORT, 123L);
        assertTrue(alex.set(HUD, "off").isOk());
        DataMap saved = saved(players, alex);

        assertFalse(saved.getMap("values").value().has("rad:last_teleport"));
        CorePlayers again = engine().players();
        MachinePlayer back = again.join(ALEX, null, saved);
        assertEquals(1.25, back.get(DOSE));
        assertEquals(1L, back.get(VISITS));
        assertEquals(0L, back.get(LAST_TELEPORT));
        assertFalse(back.get(HUD));
    }

    @Test
    void onlyPersistentChangesMakeTheDataDirty() {
        CorePlayerData alex = engine().players().join(ALEX, null, DataMap.EMPTY);
        alex.set(LAST_TELEPORT, 5L);
        assertFalse(alex.isDirty());
        alex.set(DOSE, 0.5);
        assertTrue(alex.isDirty());
        alex.markSaved();
        assertFalse(alex.isDirty());
    }

    @Test
    void unknownKeysAreKeptAndSavedAgain() throws Exception {
        CorePlayers withAddon = engine().players();
        CorePlayerData alex = withAddon.join(ALEX, null, DataMap.EMPTY);
        alex.set(DOSE, 2.0);
        DataMap saved = saved(withAddon, alex);

        // The addon is removed: its value stays in the data.
        CorePlayers without = TestEngine.builder().start().players();
        CorePlayerData kept = without.join(ALEX, null, saved);
        assertTrue(kept.unknownKeys().contains(Key.key("rad", "dose")));
        DataMap savedAgain = saved(without, kept);

        // And comes back with the addon.
        assertEquals(2.0, engine().players().join(ALEX, null, savedAgain).get(DOSE));
    }

    @Test
    void aValueThatDoesNotDecodeIsKeptAndTheDefaultUsed() throws Exception {
        CorePlayers players = engine().players();
        DataMap broken = DataMap.builder().put("version", 1).put("values", DataMap.builder()
                .put("rad:visits", VISITS.codec().encode(4L).with("data", DataValue.of("four"))).build()).build();
        CorePlayerData alex = players.join(ALEX, null, broken);
        assertEquals(0L, alex.get(VISITS));
        assertTrue(alex.unknownKeys().contains(Key.key("rad", "visits")));
        assertEquals("four", saved(players, alex).getMap("values").value().getMap("rad:visits").value().getString("data").value());

        alex.set(VISITS, 7L);
        assertFalse(alex.unknownKeys().contains(Key.key("rad", "visits")));
    }

    @Test
    void anUnregisteredKeyIsRefused() {
        MachinePlayer alex = engine().players().join(ALEX, null, DataMap.EMPTY);
        PlayerDataKey<Long> stranger = PlayerDataKey.session(Key.key("rad", "stranger"), 0L);
        assertThrows(IllegalArgumentException.class, () -> alex.get(stranger));
        // Same id, but not the registered object.
        PlayerDataKey<Double> copy = PlayerDataKey.persistent(Key.key("rad", "dose"), DOSE.codec(), 0.0);
        assertThrows(IllegalArgumentException.class, () -> alex.set(copy, 1.0));
    }

    @Test
    void deathResetsOnlyTheKeysThatAskForIt() {
        CorePlayers players = engine().players();
        MachinePlayer alex = players.join(ALEX, null, DataMap.EMPTY);
        alex.set(DOSE, 3.0);
        alex.set(VISITS, 3L);
        players.died(alex);
        assertEquals(0.0, alex.get(DOSE));
        assertEquals(3L, alex.get(VISITS));
    }

    @Test
    void quittingForgetsThePlayer() {
        CorePlayers players = engine().players();
        CorePlayerData alex = players.join(ALEX, null, DataMap.EMPTY);
        assertEquals(alex, players.quit(ALEX));
        assertTrue(players.find(ALEX).isEmpty());
        assertNull(players.quit(ALEX));
    }

    @Test
    void settingsParseAndRefuseInvalidText() {
        CorePlayers players = engine().players();
        MachinePlayer alex = players.join(ALEX, null, DataMap.EMPTY);
        assertEquals(Result.ok(80L), alex.set(VOLUME, "80"));
        Result<Long> tooLoud = alex.set(VOLUME, "180");
        assertTrue(tooLoud.isErr());
        assertEquals(CorePlayers.INVALID_SETTING_VALUE, tooLoud.error().code());
        assertEquals(80L, alex.get(VOLUME));
        assertEquals(Result.ok(Mode.FAST_MODE), alex.set(MODE, "fast_mode"));
    }

    @Test
    void settingsStep() {
        assertFalse(HUD.step(true, true));
        assertEquals(Mode.FAST_MODE, MODE.step(Mode.SLOW, true));
        assertEquals(Mode.OFF, MODE.step(Mode.SLOW, false));
        assertEquals(100L, VOLUME.step(95L, true));
        assertEquals(0L, VOLUME.step(5L, false));
        assertEquals("fast-mode", MODE.format(Mode.FAST_MODE));
        assertEquals("setting.rad.hud", HUD.nameKey());
    }

    @Test
    void aSettingDefaultMustBeValid() {
        assertThrows(IllegalArgumentException.class, () -> PlayerSetting.whole(Key.key("rad", "bad"), 200, 0, 100, 1));
    }

    @Test
    void theServerOwnerSetsDefaultsAndLocks() {
        install(Map.of(EngineConfig.PLAYER_SETTING_DEFAULTS, Map.of("rad:hud", "false", "rad:volume", "nonsense"),
                EngineConfig.PLAYER_LOCKED_SETTINGS, List.of("rad:mode")));
        CorePlayers players = engine().players();
        MachinePlayer alex = players.join(ALEX, null, DataMap.EMPTY);
        assertFalse(alex.get(HUD), "the owner's default");
        assertEquals(50L, alex.get(VOLUME), "an invalid owner default falls back to the setting's");
        assertTrue(alex.set(HUD, "on").isOk());
        assertTrue(alex.get(HUD), "the player's choice beats the owner's default");

        Result<Mode> locked = alex.set(MODE, "off");
        assertEquals(CorePlayers.SETTING_LOCKED, locked.error().code());
        assertEquals(Mode.SLOW, alex.get(MODE));
    }

    @Test
    void aChangedSettingPostsAnEvent() {
        CoreMachineEngine engine = engine();
        List<PlayerEvents.SettingChanged> seen = new ArrayList<>();
        engine.events().subscribe(new AddonId("rad"), PlayerEvents.SETTING_CHANGED, EventPhase.POST, seen::add);
        MachinePlayer alex = engine.players().join(ALEX, null, DataMap.EMPTY);
        alex.set(HUD, "true"); // unchanged
        alex.set(HUD, "false");
        assertEquals(1, seen.size());
        assertEquals(true, seen.getFirst().oldValue());
        assertEquals(false, seen.getFirst().newValue());
    }

    @Test
    void aSettingWithTheIdOfADataKeyIsReported() {
        CoreMachineEngine engine = TestEngine.builder().addon("rad", ctx -> {
            ctx.playerData().register(VISITS);
            ctx.playerSettings().register(PlayerSetting.bool(Key.key("rad", "visits"), true));
        }).start();
        assertTrue(engine.startupReport().warnings().stream().anyMatch(d -> d.code().equals(DiagnosticCodes.DUPLICATE_KEY)));
        assertNull(engine.players().setting(Key.key("rad", "visits")));
    }

    @Test
    void offlinePlayersLoadAndSaveThroughTheStorage() {
        CorePlayers players = engine().players();
        MachinePlayer offline = players.loadOffline(ALEX).join().orElseThrow();
        assertNull(offline.player());
        offline.set(VISITS, 9L);
        players.saveOffline(offline).join();
        assertTrue(players.find(ALEX).isEmpty());

        MachinePlayer joined = players.join(ALEX, null, players.storage().load(ALEX, null).join());
        assertEquals(9L, joined.get(VISITS));
        assertEquals(joined, players.loadOffline(ALEX).join().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> players.saveOffline(joined));
    }

    @Test
    void doublesDecodeFromWholeNumbers() {
        assertEquals(Result.ok(3.0), Codecs.DOUBLE.decode(DataValue.of(3)));
        assertNotNull(Codecs.DOUBLE.decode(DataValue.of("x")).error());
    }

    /** Settings with {@code overrides}, every other key at its default. */
    private static void install(Map<ConfigKey<?>, Object> overrides) {
        ConfigValues defaults = EngineConfig.SPEC.defaults();
        MachineEngineAccess.installConfig(new ConfigValues() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(ConfigKey<T> key) {
                return overrides.containsKey(key) ? (T) overrides.get(key) : defaults.get(key);
            }
        });
    }
}
