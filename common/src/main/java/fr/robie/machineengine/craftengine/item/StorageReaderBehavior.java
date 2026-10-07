package fr.robie.machineengine.craftengine.item;

import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.MachineTool;
import fr.robie.machineengine.api.machine.MachineInstance;
import fr.robie.machineengine.api.paper.PaperItemBehavior;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.paper.Messages;
import net.kyori.adventure.text.Component;
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
            player.sendMessage(Messages.message("machineengine.reader.not_machine", "§7Not a machine"));
            return true;
        }
        player.sendMessage(Messages.message("machineengine.reader.machine", "§6%s:",
                Component.text(machine.inspect().type().value().replace('_', ' '))));
        machine.inspect().storages().forEach((storage, views) -> {
            long amount = views.stream().mapToLong(StorageView::amount).sum();
            long capacity = views.stream().mapToLong(StorageView::capacity).sum();
            String held = views.stream().filter(view -> !view.isEmpty()).map(view -> view.variant().type().key().value()).distinct()
                    .reduce((a, b) -> a + ", " + b).orElse("-");
            player.sendMessage(Messages.message("machineengine.reader.storage", "§7  %s: %s / %s (%s)",
                    Component.text(storage), Component.text(String.format(Locale.ROOT, "%,d", amount)),
                    Component.text(String.format(Locale.ROOT, "%,d", capacity)), Component.text(held)));
        });
        return true;
    }
}
