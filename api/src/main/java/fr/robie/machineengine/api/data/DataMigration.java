package fr.robie.machineengine.api.data;

import fr.robie.machineengine.api.error.Result;
import org.jetbrains.annotations.NotNull;

import java.util.function.Function;

/**
 * Upgrades data from {@link #fromVersion()} to {@code fromVersion + 1}.
 *
 * @param fromVersion the data version this migration accepts
 * @param migration   the upgrade; returns an error result if the data cannot be migrated
 */
public record DataMigration(int fromVersion, @NotNull Function<DataValue, Result<DataValue>> migration) {

    public @NotNull Result<DataValue> apply(@NotNull DataValue data) {
        return this.migration.apply(data);
    }
}
