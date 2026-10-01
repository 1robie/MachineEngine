package fr.robie.machineengine.core.lifecycle;

import fr.robie.machineengine.api.addon.AddonDependency;
import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.diagnostic.Validator;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.registry.ReloadableProvider;
import net.kyori.adventure.key.Keyed;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** What one addon contributed during registration. */
final class AddonRecord {
    final AddonRegistration registration;
    final List<Validator> validators = new ArrayList<>();
    final List<ProviderEntry<?>> providers = new ArrayList<>();

    AddonRecord(AddonRegistration registration) {
        this.registration = registration;
    }

    AddonId id() {
        return this.registration.addon();
    }

    /** @return whether this addon requires (not just orders after) one of {@code addons} */
    boolean requiresAny(Set<AddonId> addons) {
        for (AddonDependency dependency : this.registration.dependencies()) {
            if (!dependency.optional() && addons.contains(dependency.addon())) return true;
        }
        return false;
    }

    record ProviderEntry<T extends Keyed>(RegistryType<T> type, ReloadableProvider<T> provider) {
    }
}
