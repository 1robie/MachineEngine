package fr.robie.machineengine.craftengine.item;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.MachineTool;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.paper.PaperItemBehavior;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.messageflow.formatter.Placeholder;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviorFactory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * The CraftEngine item behavior {@code machineengine:storage_reader} (D32): right-click a machine to
 * read what each of its storages holds, in chat.
 *
 * <pre>{@code
 * behaviors:
 *   - type: machineengine:storage_reader
 * }</pre>
 */
public final class StorageReaderBehavior extends PaperItemBehavior implements MachineTool {
    public static final ItemBehaviorFactory<StorageReaderBehavior> FACTORY = (pack, path, id, section) -> new StorageReaderBehavior();

    @Override
    protected boolean use(@NotNull Use use) {
        Block block = use.block();
        if (block == null) return false;
        Player player = use.player();
        MachineInstance machine = MachineEngine.get()
                .machineAt(WorldPosition.of(block.getWorld().getKey(), block.getX(), block.getY(), block.getZ())).orElse(null);
        if (machine == null) {
            EngineMessages.send(player, EngineMessage.READER_NOT_MACHINE);
            return true;
        }
        EngineMessages.send(player, EngineMessage.READER_MACHINE, Placeholder.of("machine", machine.inspect().type().value().replace('_', ' ')));
        machine.inspect().storages().forEach((storage, views) -> {
            long amount = views.stream().mapToLong(StorageView::amount).sum();
            long capacity = views.stream().mapToLong(StorageView::capacity).sum();
            String held = views.stream().filter(view -> !view.isEmpty()).map(view -> view.variant().type().key().value()).distinct()
                    .reduce((a, b) -> a + ", " + b).orElse("-");
            EngineMessages.send(player, EngineMessage.READER_STORAGE, Placeholder.builder()
                    .register("storage", String.valueOf(storage))
                    .register("amount", String.format(Locale.ROOT, "%,d", amount))
                    .register("capacity", String.format(Locale.ROOT, "%,d", capacity))
                    .register("held", held)
                    .build());
        });
        return true;
    }
}
