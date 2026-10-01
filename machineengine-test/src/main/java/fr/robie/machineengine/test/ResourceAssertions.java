package fr.robie.machineengine.test;

import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assertions about resources across handlers. Framework-neutral: failures throw {@link AssertionError}. */
public final class ResourceAssertions {

    private ResourceAssertions() {
    }

    /** @return the total amount of each resource type held by {@code handlers} */
    public static @NotNull Map<ResourceType, Long> totals(@NotNull Collection<? extends ResourceHandler> handlers) {
        Map<ResourceType, Long> totals = new LinkedHashMap<>();
        for (ResourceHandler handler : handlers) {
            for (StorageView view : handler.views()) {
                if (!view.isEmpty()) totals.merge(view.variant().type(), view.amount(), Math::addExact);
            }
        }
        return totals;
    }

    /** @return every slot of every handler, for comparing exact states */
    public static @NotNull List<StorageView> snapshot(@NotNull Collection<? extends ResourceHandler> handlers) {
        return handlers.stream().flatMap(handler -> handler.views().stream()).toList();
    }

    /**
     * Fails unless {@code handlers} hold exactly {@code expected} of each type (§10.3 conservation
     * invariant).
     */
    public static void assertConserved(@NotNull Map<ResourceType, Long> expected,
                                       @NotNull Collection<? extends ResourceHandler> handlers) {
        Map<ResourceType, Long> actual = totals(handlers);
        Map<ResourceType, Long> nonZeroExpected = new LinkedHashMap<>(expected);
        nonZeroExpected.values().removeIf(amount -> amount == 0);
        if (!actual.equals(nonZeroExpected)) {
            throw new AssertionError("Resources were not conserved: expected " + nonZeroExpected + " but found " + actual);
        }
    }
}
