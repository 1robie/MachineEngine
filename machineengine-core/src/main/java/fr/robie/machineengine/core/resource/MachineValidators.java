package fr.robie.machineengine.core.resource;

import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.ValidationContext;
import fr.robie.machineengine.api.machine.CapabilityBinding;
import fr.robie.machineengine.api.machine.MachineDataKey;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.Recipe;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.Registry;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.upgrade.Modifier;
import fr.robie.machineengine.api.upgrade.UpgradeType;
import net.kyori.adventure.key.Key;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validation of machine types, upgrades and recipes (§24). */
final class MachineValidators {

    private MachineValidators() {
    }

    static void machineTypes(ValidationContext context) {
        Registry<MachineType> machines = context.registries().registry(Registries.MACHINE_TYPE);
        var capabilities = context.registries().registry(Registries.CAPABILITY_TYPE);
        for (MachineType type : machines) {
            Reporter report = new Reporter(context, machines, type);
            Set<String> storages = new HashSet<>();
            for (StorageDefinition storage : type.storages()) {
                if (!storages.add(storage.id())) report.error("storage '" + storage.id() + "' is defined twice");
                if (storage.slots() < 1) report.error("storage '" + storage.id() + "' needs at least one slot");
                if (storage.capacity() < 1) report.error("storage '" + storage.id() + "' needs a positive capacity");
            }
            Set<String> ports = new HashSet<>();
            for (PortDefinition port : type.ports()) {
                if (!ports.add(port.id())) report.error("port '" + port.id() + "' is defined twice");
                if (!storages.contains(port.storage())) {
                    report.error("port '" + port.id() + "' exposes unknown storage '" + port.storage() + "'");
                }
                if (port.direction() != PortDirection.DISABLED && port.sides().isEmpty()) {
                    report.error("port '" + port.id() + "' is not exposed on any side");
                }
                if (port.ratePerTick() < 1) report.error("port '" + port.id() + "' needs a positive rate");
            }
            Set<String> data = new HashSet<>();
            for (MachineDataKey<?> key : type.data()) {
                if (!data.add(key.id())) report.error("data '" + key.id() + "' is declared twice");
                key.codec().definitionProblems().forEach(problem -> report.error("data '" + key.id() + "': " + problem));
            }
            for (CapabilityBinding<?> binding : type.capabilities()) {
                if (!capabilities.containsValue(binding.type())) {
                    context.report(BuiltInValidators.unregistered(machines, type, "capability type " + binding.type().key().asString()));
                }
            }
            if (type.upgradeSlots() < 0) report.error("upgrade slots must not be negative");
        }
    }

    static void upgradeTypes(ValidationContext context) {
        Registry<UpgradeType> upgrades = context.registries().registry(Registries.UPGRADE_TYPE);
        var modifiers = context.registries().registry(Registries.MODIFIER_TYPE);
        for (UpgradeType upgrade : upgrades) {
            for (Modifier modifier : upgrade.modifiers()) {
                if (!modifiers.containsValue(modifier.type())) {
                    context.report(BuiltInValidators.unregistered(upgrades, upgrade, "modifier type " + modifier.type().key().asString()));
                }
            }
        }
    }

    static void recipes(ValidationContext context) {
        Registry<Recipe> recipes = context.registries().registry(Registries.RECIPE);
        var recipeTypes = context.registries().registry(Registries.RECIPE_TYPE);
        Registry<ResourceType> resourceTypes = context.registries().registry(Registries.RESOURCE_TYPE);
        for (Recipe recipe : recipes) {
            Reporter report = new Reporter(context, recipes, recipe);
            if (!recipeTypes.containsValue(recipe.type())) {
                context.report(BuiltInValidators.unregistered(recipes, recipe, "recipe type " + recipe.type().key().asString()));
            }
            if (!(recipe instanceof ProcessingRecipe processing)) continue;
            if (processing.duration() < 1) report.error("duration must be at least 1 tick");
            for (ResourceMatcher input : processing.inputs()) {
                if (input.amount() < 1) report.error("every input needs a positive amount");
            }
            for (List<ResourceStack> stacks : List.of(processing.outputs(), processing.costPerTick())) {
                for (ResourceStack stack : stacks) {
                    if (!resourceTypes.containsValue(stack.type())) {
                        context.report(BuiltInValidators.unregistered(recipes, recipe, "resource type " + stack.type().key().asString()));
                    }
                    if (stack.amount() < 1) report.error("output and cost amounts must be positive");
                }
            }
        }
    }

    /** Reports errors about one entry, attributed to its owner. */
    private record Reporter(ValidationContext context, Registry<?> registry, net.kyori.adventure.key.Keyed entry) {
        void error(String message) {
            Key key = this.entry.key();
            this.context.report(Diagnostic.error(DiagnosticCodes.INVALID_DEFINITION, key.asString() + ": " + message)
                    .source(this.registry.owner(key).orElse(null)).subject(key)
                    .with("registry", this.registry.type().toString()).build());
        }
    }
}
