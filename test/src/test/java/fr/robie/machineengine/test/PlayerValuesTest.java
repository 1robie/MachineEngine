package fr.robie.machineengine.test;

import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.VersionedCodec;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.player.PlayerDataKey;
import fr.robie.machineengine.api.player.PlayerSetting;
import fr.robie.machineengine.core.player.CorePlayers;
import fr.robie.machineengine.paper.player.PlayerValues;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Player values as text (D37): placeholders and what admins type. */
class PlayerValuesTest {
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e5");
    private static final PlayerDataKey<Double> DOSE = PlayerDataKey.persistent(Key.key("rad", "dose"),
            VersionedCodec.builder(Key.key("rad", "dose"), Codecs.DOUBLE).build(), 0.0)
            .display(dose -> String.format(Locale.ROOT, "%.2f Sv", dose));
    private static final PlayerDataKey<Boolean> FLYING = PlayerDataKey.session(Key.key("rad", "suit/flying"), false);
    private static final PlayerSetting<Boolean> HUD = PlayerSetting.bool(Key.key("rad", "hud"), true);

    private static CorePlayers players() {
        return TestEngine.builder().addon("rad", ctx -> {
            ctx.playerData().register(DOSE);
            ctx.playerData().register(FLYING);
            ctx.playerSettings().register(HUD);
        }).start().players();
    }

    @Test
    void placeholdersShowValuesTheirWay() {
        CorePlayers players = players();
        MachinePlayer alex = players.join(ALEX, null, DataMap.EMPTY);
        alex.set(DOSE, 1.5);
        assertEquals("1.50 Sv", PlayerValues.placeholder(players, alex, "rad_dose"));
        assertEquals("false", PlayerValues.placeholder(players, alex, "rad_suit_flying"));
        assertEquals("true", PlayerValues.placeholder(players, alex, "rad_hud"));
        assertNull(PlayerValues.placeholder(players, alex, "rad_nothing"));
    }

    @Test
    void adminsTypeValuesOfTheKeysType() {
        CorePlayers players = players();
        MachinePlayer alex = players.join(ALEX, null, DataMap.EMPTY);
        assertSame(DOSE, PlayerValues.key(players, "RAD:DOSE"));
        assertNull(PlayerValues.set(players, alex, DOSE, "2.5"));
        assertEquals(2.5, alex.get(DOSE));
        assertNotNull(PlayerValues.set(players, alex, DOSE, "lots"));
        assertNull(PlayerValues.set(players, alex, FLYING, "true"));
        assertEquals(true, alex.get(FLYING));
        assertNull(PlayerValues.set(players, alex, HUD.data(), "off"));
        assertEquals(false, alex.get(HUD));
    }
}
