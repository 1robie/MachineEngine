package fr.robie.machineengine.paper;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.machine.WakeReason;
import fr.robie.machineengine.api.paper.PaperItems;
import fr.robie.machineengine.api.paper.PositionArguments;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.transfer.ResourceHandler;
import fr.robie.machineengine.api.transfer.StorageView;
import fr.robie.machineengine.api.transfer.Transaction;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.paper.message.EngineMessage;
import fr.robie.machineengine.paper.message.EngineMessages;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.paperdispatch.command.CommandDispatch;
import fr.robie.paperdispatch.command.CommandResultType;
import fr.robie.paperdispatch.command.SubCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Admin commands that change a machine, each at {@code [<x y z>] [world]} (the block looked at by default):
 * <ul>
 *   <li>{@code fill <storage> <resource> <amount>}: puts a resource into a storage (energy, a fluid, a
 *       chemical, or an item by its id: {@code minecraft:iron_ingot}, a CraftEngine item);</li>
 *   <li>{@code drain <storage>}: empties a storage;</li>
 *   <li>{@code wake}: wakes a sleeping machine up.</li>
 * </ul>
 * Each runs on the thread owning the machine (its region on Folia).
 */
@SuppressWarnings("UnstableApiUsage")
final class MachineStorageCommands {
    private MachineStorageCommands() {
    }

    static List<SubCommand<Plugin>> commands(Plugin plugin, Supplier<CoreMachineEngine> engine, Supplier<MachineLocator> locator) {
        return List.of(
                new AtMachine(plugin, "fill", locator, MachineStorageCommands::fill,
                        storageArgument(), resourceArgument(engine), Commands.argument("amount", LongArgumentType.longArg(1))),
                new AtMachine(plugin, "drain", locator, MachineStorageCommands::drain, storageArgument()),
                new AtMachine(plugin, "wake", locator, (dispatch, machine) -> {
                    machine.wake(WakeReason.ADMIN);
                    EngineMessages.send(dispatch.getSender(), EngineMessage.ADMIN_STORAGE_WOKE, Placeholder.builder()
                            .register("type", machine.type().key().asString())
                            .register("position", String.valueOf(machine.position()))
                            .register("state", String.valueOf(machine.schedulerState()))
                            .build());
                }));
    }

    private static ArgumentBuilder<CommandSourceStack, ?> storageArgument() {
        return Commands.argument("storage", StringArgumentType.word());
    }

    private static ArgumentBuilder<CommandSourceStack, ?> resourceArgument(Supplier<CoreMachineEngine> engine) {
        return Commands.argument("resource", ArgumentTypes.key()).suggests((context, builder) -> {
            CoreMachineEngine running = engine.get();
            String typed = builder.getRemaining();
            if (running != null) {
                for (ResourceType type : running.registries().registry(Registries.RESOURCE_TYPE).values()) {
                    if (type.category().equals(Items.CATEGORY)) continue;
                    String key = type.key().asString();
                    if (key.startsWith(typed) || type.key().value().startsWith(typed)) builder.suggest(key);
                }
            }
            return builder.buildFuture();
        });
    }

    private static void fill(CommandDispatch<Plugin> dispatch, MachineInstanceImpl machine) {
        CommandSender sender = dispatch.getSender();
        ResourceHandler storage = storage(dispatch, machine);
        if (storage == null) return;
        Key key = dispatch.getArgument("resource", Key.class);
        ResourceVariant variant = variant(key);
        if (variant == null) {
            EngineMessages.send(sender, EngineMessage.ADMIN_STORAGE_NO_RESOURCE, Placeholder.of("resource", key.asString()));
            return;
        }
        long amount = dispatch.getArgument("amount", Long.class);
        long inserted;
        try (Transaction transaction = machine.services().transactions().openOuter()) {
            inserted = storage.insert(variant, amount, transaction);
            transaction.commit();
        }
        changed(machine);
        String id = dispatch.getArgument("storage", String.class);
        EngineMessages.send(sender, inserted < amount ? EngineMessage.ADMIN_STORAGE_PUT_PARTIAL : EngineMessage.ADMIN_STORAGE_PUT,
                Placeholder.builder()
                        .register("inserted", String.valueOf(inserted))
                        .register("amount", String.valueOf(amount))
                        .register("resource", key.asString())
                        .register("storage", id)
                        .build());
    }

    private static void drain(CommandDispatch<Plugin> dispatch, MachineInstanceImpl machine) {
        ResourceHandler storage = storage(dispatch, machine);
        if (storage == null) return;
        long removed = 0;
        try (Transaction transaction = machine.services().transactions().openOuter()) {
            for (StorageView view : List.copyOf(storage.views())) {
                if (!view.isEmpty()) removed += storage.extract(view.variant(), view.amount(), transaction);
            }
            transaction.commit();
        }
        changed(machine);
        EngineMessages.send(dispatch.getSender(), EngineMessage.ADMIN_STORAGE_REMOVED,
                Placeholder.of("removed", String.valueOf(removed), "storage", dispatch.getArgument("storage", String.class)));
    }

    private static @Nullable ResourceHandler storage(CommandDispatch<Plugin> dispatch, MachineInstanceImpl machine) {
        String id = dispatch.getArgument("storage", String.class);
        try {
            return machine.context().storage(id);
        } catch (IllegalArgumentException e) {
            EngineMessages.send(dispatch.getSender(), EngineMessage.ADMIN_STORAGE_UNKNOWN, Placeholder.builder()
                    .register("type", machine.type().key().asString())
                    .register("storage", id)
                    .register("storages", String.valueOf(machine.inspect().storages().keySet()))
                    .build());
            return null;
        }
    }

    /** A registered resource type (not the item one), else an item by its id. */
    private static @Nullable ResourceVariant variant(Key key) {
        ResourceType type = MachineEngine.get().registries().registry(Registries.RESOURCE_TYPE).get(key).orElse(null);
        if (type != null && !type.category().equals(Items.CATEGORY)) return ResourceVariant.of(type);
        ItemStack item = PaperItems.itemOf(key);
        return item == null || item.getType().isAir() ? null : PaperItems.toResource(item, key).variant();
    }

    private static void changed(MachineInstanceImpl machine) {
        machine.wake(WakeReason.ADMIN);
        MachineMenuService menus = MachineMenuService.current();
        if (menus != null) menus.machineChanged(machine.position());
    }

    /** A sub-command with {@code before} arguments, then {@code [<x y z>] [world]}; runs on the machine's thread. */
    private static final class AtMachine extends SubCommand<Plugin> {
        private final Supplier<MachineLocator> locator;
        private final BiConsumer<CommandDispatch<Plugin>, MachineInstanceImpl> action;

        @SafeVarargs
        AtMachine(Plugin plugin, String name, Supplier<MachineLocator> locator, BiConsumer<CommandDispatch<Plugin>, MachineInstanceImpl> action,
                  ArgumentBuilder<CommandSourceStack, ?>... before) {
            super(plugin, name);
            this.locator = locator;
            this.action = action;
            for (ArgumentBuilder<CommandSourceStack, ?> argument : before) addRequiredArgument(argument);
            addOptionalArgument(PositionArguments.positionArgument());
            addOptionalArgument(PositionArguments.worldArgument());
        }

        @Override
        protected @NotNull CommandResultType perform(@NotNull CommandDispatch<Plugin> dispatch) {
            PositionArguments.Position at = PositionArguments.resolve(dispatch.getContext(), PositionArguments.Default.TARGET_BLOCK);
            if (at == null) return CommandResultType.FAILURE;
            WorldPosition position = WorldPosition.of(at.world().getKey(), at.x(), at.y(), at.z());
            MachineEngine.scheduler(this.plugin).run(at.world(), at.x() >> 4, at.z() >> 4, () -> {
                LoadedMachine loaded = this.locator.get().machine(position);
                MachineInstanceImpl machine = loaded == null ? null : loaded.instance();
                if (machine == null || !machine.isValid()) {
                    EngineMessages.send(dispatch.getSender(), loaded != null ? EngineMessage.ADMIN_PLACEHOLDER_MACHINE : EngineMessage.ADMIN_NO_MACHINE,
                            Placeholder.of("position", String.valueOf(position)));
                    return;
                }
                this.action.accept(dispatch, machine);
            });
            return CommandResultType.SUCCESS;
        }
    }
}
