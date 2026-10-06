package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.RegistrationContext;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.resource.MergePolicy;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceComponentType;
import fr.robie.machineengine.api.resource.ResourceType;
import net.kyori.adventure.key.Key;

/** Example resource systems defined the way an addon would. */
final class Fixtures {
    static final ResourceCategory ENERGY = ResourceCategory.builder(Key.key("energyplugin", "energy")).unit("FE").build();
    static final ResourceType FE = ResourceType.of(Key.key("energyplugin", "fe"), ENERGY);
    static final ResourceType EU = ResourceType.of(Key.key("energyplugin", "eu"), ENERGY);

    static final ResourceComponentType<Long> TEMPERATURE =
            ResourceComponentType.of(Key.key("mymod", "temperature"), Long.class, Codecs.LONG);
    static final ResourceCategory FLUID = ResourceCategory.builder(Key.key("mymod", "fluid"))
            .unit("mB").mergePolicy(MergePolicy.weightedAverage(TEMPERATURE)).build();
    static final ResourceType WATER = ResourceType.of(Key.key("mymod", "water"), FLUID);
    static final ResourceType STEAM = ResourceType.of(Key.key("mymod", "steam"), FLUID);
    static final ResourceType OIL = ResourceType.of(Key.key("mymod", "oil"), FLUID);

    private Fixtures() {
    }

    static void registerEnergy(RegistrationContext context) {
        context.resourceCategories().register(ENERGY);
        context.resourceTypes().register(FE);
        context.resourceTypes().register(EU);
    }

    static void registerFluids(RegistrationContext context) {
        context.componentTypes().register(TEMPERATURE);
        context.resourceCategories().register(FLUID);
        context.resourceTypes().register(WATER);
        context.resourceTypes().register(STEAM);
        context.resourceTypes().register(OIL);
    }
}
