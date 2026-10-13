package fr.robie.machineengine.core.machine;

import fr.robie.machineengine.api.event.EventBus;
import fr.robie.machineengine.api.recipe.RecipeLookup;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.resource.tag.ResourceTags;
import fr.robie.machineengine.api.transfer.TransactionManager;
import fr.robie.machineengine.core.machine.handoff.HandoffService;
import fr.robie.machineengine.core.network.CoreNetworks;
import fr.robie.machineengine.core.resource.ResourceCodecs;
import fr.robie.messageflow.logger.Logger;
import org.jetbrains.annotations.NotNull;


/** Engine services machines use. */
public interface MachineServices {

    @NotNull TransactionManager transactions();

    @NotNull ResourceTags tags();

    @NotNull RegistryAccess registries();

    @NotNull RecipeLookup recipes();

    @NotNull EventBus events();

    @NotNull ResourceCodecs resourceCodecs();

    @NotNull HandoffService handoff();

    @NotNull CoreNetworks networks();

    @NotNull Logger logger();
}
