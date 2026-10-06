package fr.robie.machineengine.api.resource;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.config.ConfigKey;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.NotNull;

/**
 * MachineEngine's energy, {@code machineengine:energy}: one category holding one resource type, shared
 * by every addon and definition (D29). Its unit and scale are set in {@code config.yml}
 * ({@code resources.energy}) and read when it is built.
 */
public final class Energy {
    public static final Key KEY = MachineEngineKeys.key("energy");

    public static final ConfigKey<String> UNIT = ConfigKey.string("resources.energy.unit", "J")
            .comment("Unit shown after energy amounts.").restartOnly();
    public static final ConfigKey<Integer> SCALE = ConfigKey.integer("resources.energy.scale", 0, 0, 18)
            .comment("Decimal digits of energy amounts: with 3, 1500 is shown as 1.500.").restartOnly();

    private Energy() {
    }

    /** The category {@code machineengine:energy}, with the configured unit and scale. */
    public static @NotNull ResourceCategory category() {
        return BuiltInResources.get().energyCategory();
    }

    /** The resource type {@code machineengine:energy}. */
    public static @NotNull ResourceType type() {
        return BuiltInResources.get().energy();
    }

    /** Matches energy. */
    public static @NotNull ResourcePredicate is() {
        return ResourcePredicate.type(type());
    }
}
