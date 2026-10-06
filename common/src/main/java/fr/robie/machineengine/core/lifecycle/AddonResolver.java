package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.addon.AddonDependency;
import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.version.Version;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Orders addon registrations by their dependencies (§8.2 step 2) and rejects the ones that cannot
 * run: duplicate names, incompatible API, missing or incompatible dependencies, cycles, and
 * everything that requires a rejected addon.
 */
final class AddonResolver {

    record Resolution(@NotNull List<AddonRegistration> ordered, @NotNull List<Diagnostic> diagnostics) {
    }

    private AddonResolver() {
    }

    static @NotNull Resolution resolve(@NotNull Collection<AddonRegistration> registrations, @NotNull Version apiVersion) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<AddonId, AddonRegistration> byId = new LinkedHashMap<>();
        for (AddonRegistration registration : registrations) {
            if (byId.putIfAbsent(registration.addon(), registration) != null) {
                diagnostics.add(Diagnostic.error(DiagnosticCodes.DUPLICATE_ADDON,
                        "Addon " + registration.addon() + " submitted its registration twice; the second one is ignored")
                        .source(registration.addon()).build());
            }
        }

        Set<AddonId> rejected = new TreeSet<>();
        for (AddonRegistration registration : byId.values()) {
            if (!registration.apiRange().contains(apiVersion)) {
                rejected.add(registration.addon());
                diagnostics.add(Diagnostic.error(DiagnosticCodes.INCOMPATIBLE_API,
                                registration.addon() + " requires MachineEngine API " + registration.apiRange()
                                        + " but this is API " + apiVersion)
                        .source(registration.addon()).build());
            }
            for (AddonDependency dependency : registration.dependencies()) {
                AddonRegistration target = byId.get(dependency.addon());
                if (target == null) {
                    if (!dependency.optional()) {
                        rejected.add(registration.addon());
                        diagnostics.add(Diagnostic.error(DiagnosticCodes.MISSING_DEPENDENCY,
                                        registration.addon() + " requires " + dependency.addon() + " " + dependency.versions()
                                                + ", which is not installed")
                                .source(registration.addon()).build());
                    }
                } else if (!dependency.versions().contains(target.version())) {
                    rejected.add(registration.addon());
                    diagnostics.add(Diagnostic.error(DiagnosticCodes.INCOMPATIBLE_DEPENDENCY,
                                    registration.addon() + " requires " + dependency.addon() + " " + dependency.versions()
                                            + " but version " + target.version() + " is installed")
                            .source(registration.addon()).build());
                }
            }
        }

        List<AddonRegistration> ordered = topologicalOrder(byId, rejected, diagnostics);
        return new Resolution(ordered, diagnostics);
    }

    /**
     * Kahn's algorithm; ties are broken by addon name so the order does not depend on plugin load
     * order. Addons left over are in (or depend on) a cycle.
     */
    private static List<AddonRegistration> topologicalOrder(Map<AddonId, AddonRegistration> byId, Set<AddonId> rejected,
                                                            List<Diagnostic> diagnostics) {
        Map<AddonId, Set<AddonId>> requirements = new LinkedHashMap<>();
        for (AddonRegistration registration : byId.values()) {
            Set<AddonId> before = new LinkedHashSet<>();
            for (AddonDependency dependency : registration.dependencies()) {
                if (byId.containsKey(dependency.addon())) before.add(dependency.addon());
            }
            requirements.put(registration.addon(), before);
        }

        List<AddonRegistration> ordered = new ArrayList<>();
        Set<AddonId> placed = new LinkedHashSet<>();
        TreeSet<AddonId> ready = new TreeSet<>();
        requirements.forEach((addon, before) -> {
            if (before.isEmpty()) ready.add(addon);
        });
        while (!ready.isEmpty()) {
            AddonId next = ready.pollFirst();
            placed.add(next);
            ordered.add(byId.get(next));
            requirements.forEach((addon, before) -> {
                if (!placed.contains(addon) && !ready.contains(addon) && placed.containsAll(before)) ready.add(addon);
            });
        }

        Set<AddonId> cyclic = new TreeSet<>(requirements.keySet());
        cyclic.removeAll(placed);
        for (AddonId addon : cyclic) {
            rejected.add(addon);
            diagnostics.add(Diagnostic.error(DiagnosticCodes.DEPENDENCY_CYCLE,
                    addon + " is part of (or depends on) a dependency cycle among " + cyclic).source(addon).build());
        }

        // Anything that requires a rejected addon is rejected too, in order.
        List<AddonRegistration> result = new ArrayList<>();
        for (AddonRegistration registration : ordered) {
            if (rejected.contains(registration.addon())) continue;
            AddonId disabledDependency = registration.dependencies().stream()
                    .filter(dependency -> !dependency.optional() && rejected.contains(dependency.addon()))
                    .map(AddonDependency::addon).findFirst().orElse(null);
            if (disabledDependency != null) {
                rejected.add(registration.addon());
                diagnostics.add(Diagnostic.error(DiagnosticCodes.DEPENDENCY_DISABLED,
                                registration.addon() + " requires " + disabledDependency + ", which is disabled")
                        .source(registration.addon()).build());
                continue;
            }
            result.add(registration);
        }
        return result;
    }
}
