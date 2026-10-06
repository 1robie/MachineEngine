package fr.robie.machineengine.zmenu;

import fr.maxlego08.menu.api.ButtonManager;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryListener;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.button.Button;
import fr.maxlego08.menu.api.engine.BaseInventory;
import fr.maxlego08.menu.api.engine.InventoryEngine;
import fr.maxlego08.menu.api.placeholder.LocalPlaceholder;
import fr.maxlego08.menu.api.utils.Placeholders;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.paper.InventoryViews;
import fr.robie.machineengine.paper.MachineLocator;
import fr.robie.machineengine.paper.MachineMenuService;
import fr.robie.machineengine.paper.MachinePlaceholders;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Machine menus on zMenu (§23.3). Everything is configured in YAML:
 * <ul>
 *   <li>menu files in {@code plugins/MachineEngine/menus/} (standard zMenu inventories);</li>
 *   <li>a machine type opens {@code menus.by-type} from {@code config.yml}, else the menu file
 *       {@code <namespace>_<value>.yml}, else {@code menus.default};</li>
 *   <li>placeholders {@code %zmenu_machineengine_<key>%} ({@link MachinePlaceholders}) resolve
 *       against the machine the player has open, in any zMenu button;</li>
 *   <li>button types {@code machineengine_slot}, {@code machineengine_cancel_process}, {@code machineengine_upgrades},
 *       {@code machineengine_data}, {@code machineengine_side} and {@code machineengine_menu} interact
 *       with the machine.</li>
 * </ul>
 */
public final class ZMenuMachineMenus implements MachineMenuService {
    public static final String PLACEHOLDER_PREFIX = "machineengine_";
    static final String DEFAULT_MENU_RESOURCE = "menus/machine.yml";

    private final Plugin plugin;
    private final InventoryManager inventories;
    private final ButtonManager buttons;
    private final MachineLocator locator;
    private final Map<String, Inventory> menus = new LinkedHashMap<>();
    private final Map<UUID, WorldPosition> openMachines = new ConcurrentHashMap<>();
    private final InventoryListener listener;
    /** Players being moved to another menu of the same machine: their close is not the end. */
    private final Set<UUID> switching = ConcurrentHashMap.newKeySet();
    private Map<Key, String> menuByType = Map.of();
    private String defaultMenu = "machine";

    private ZMenuMachineMenus(Plugin plugin, InventoryManager inventories, ButtonManager buttons, MachineLocator locator) {
        this.plugin = plugin;
        this.inventories = inventories;
        this.buttons = buttons;
        this.locator = locator;
        this.listener = new InventoryListener() {
            @Override
            public void onInventoryClose(Player player, BaseInventory baseInventory) {
                // Going from one machine menu to another (machineengine_menu) closes the first one.
                if (ZMenuMachineMenus.this.switching.contains(player.getUniqueId())) return;
                ZMenuMachineMenus.this.openMachines.remove(player.getUniqueId());
            }
        };
    }

    /**
     * Must run in {@code onEnable}, after zMenu enabled: button types must exist before menu files load.
     *
     * @return {@code null} if zMenu's services are not available
     */
    public static @Nullable ZMenuMachineMenus enable(@NotNull Plugin plugin, @NotNull MachineLocator locator) {
        InventoryManager inventories = plugin.getServer().getServicesManager().load(InventoryManager.class);
        ButtonManager buttons = plugin.getServer().getServicesManager().load(ButtonManager.class);
        if (inventories == null || buttons == null) return null;
        ZMenuMachineMenus menus = new ZMenuMachineMenus(plugin, inventories, buttons, locator);
        buttons.register(new SlotButton.Loader(plugin, menus));
        buttons.register(new CancelProcessButton.Loader(plugin, menus));
        buttons.register(new UpgradeButton.Loader(plugin, menus));
        buttons.register(new DataButton.Loader(plugin, menus));
        buttons.register(new SideButton.Loader(plugin, menus));
        buttons.register(new OpenMenuButton.Loader(plugin, menus));
        LocalPlaceholder.getInstance().register(PLACEHOLDER_PREFIX, menus::placeholder);
        inventories.registerInventoryListener(menus.listener);
        menus.reload();
        return menus;
    }

    public void disable() {
        this.inventories.unregisterInventoryListener(this.listener);
        this.inventories.deleteInventories(this.plugin);
        this.buttons.unregisters(this.plugin);
        this.openMachines.clear();
    }

    @Override
    public void reload() {
        this.inventories.deleteInventories(this.plugin);
        this.menus.clear();
        var config = this.plugin.getConfig();
        this.defaultMenu = config.getString("menus.default", "machine");
        Map<Key, String> byType = new LinkedHashMap<>();
        var section = config.getConfigurationSection("menus.by-type");
        if (section != null) {
            for (String type : section.getKeys(false)) byType.put(Key.key(type), section.getString(type));
        }
        this.menuByType = Map.copyOf(byType);

        File folder = new File(this.plugin.getDataFolder(), "menus");
        if (!new File(this.plugin.getDataFolder(), DEFAULT_MENU_RESOURCE).exists()) {
            this.plugin.saveResource(DEFAULT_MENU_RESOURCE, false);
        }
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            try {
                Inventory inventory = this.inventories.loadInventory(this.plugin, file);
                if (inventory != null) this.menus.put(name, inventory);
            } catch (Exception e) {
                this.plugin.getLogger().log(Level.WARNING, "Could not load machine menu " + file.getName() + ": " + e.getMessage(), e);
            }
        }
        this.plugin.getLogger().info("Loaded " + this.menus.size() + " machine menu(s) " + this.menus.keySet());
    }

    @Override
    public boolean open(@NotNull Player player, @NotNull LoadedMachine machine) {
        return open(player, machine, null);
    }

    /** Opens {@code menuName} (a menu file name), or the machine's own menu if {@code null}, for {@code machine}. */
    @Override
    public boolean open(@NotNull Player player, @NotNull LoadedMachine machine, @Nullable String menuName) {
        Inventory menu = this.menus.get(menuName != null ? menuName : menuName(machine.inspect().type()));
        if (menu == null) return false;
        this.openMachines.put(player.getUniqueId(), machine.position());
        UUID id = player.getUniqueId();
        this.switching.add(id);
        this.inventories.openInventory(player, menu);
        // zMenu may open on the player's scheduler (Folia): the old menu's close can come a tick later.
        player.getScheduler().runDelayed(this.plugin, task -> this.switching.remove(id), () -> this.switching.remove(id), 2);
        return true;
    }

    @NotNull Plugin plugin() {
        return this.plugin;
    }

    /**
     * Which menu a machine type opens: {@code menus.by-type} in config.yml, else a menu file named
     * {@code <namespace>_<value>.yml} (how addons ship menus), else {@code menus.default}.
     */
    @NotNull String menuName(@NotNull Key type) {
        String configured = this.menuByType.get(type);
        if (configured != null) return configured;
        String conventional = type.namespace() + "_" + type.value();
        return this.menus.containsKey(conventional) ? conventional : this.defaultMenu;
    }

    /** The machine whose menu {@code player} has open, if it is still loaded. */
    @Nullable LoadedMachine machineOf(@NotNull OfflinePlayer player) {
        WorldPosition position = this.openMachines.get(player.getUniqueId());
        return position == null ? null : this.locator.machine(position);
    }

    /**
     * The live machine whose menu {@code player} has open, if the current thread owns it. On Folia a
     * player at a region border may click a machine owned by another region; that click is ignored
     * rather than touching the machine from the wrong thread.
     */
    @Nullable MachineInstanceImpl ownedMachineOf(@NotNull OfflinePlayer player) {
        LoadedMachine machine = machineOf(player);
        if (machine == null || machine.instance() == null || !machine.instance().isValid()) return null;
        return this.locator.isOwnedByCurrentThread(machine.position()) ? machine.instance() : null;
    }

    /**
     * After {@code player} changed the machine: re-renders their menu now and every other open menu of
     * the same machine on its viewer's thread, so nobody looks at stale contents. (Stale views cannot
     * duplicate anything: clicks act on the machine's real contents, in a transaction.)
     */
    void changed(@NotNull Player player, @NotNull WorldPosition position) {
        refresh(player);
        forViewers(position, viewer -> {
            if (!viewer.equals(player)) viewer.getScheduler().run(this.plugin, task -> refresh(viewer), null);
        });
    }

    /** Players with a refresh already queued: at most one refresh per player every 5 ticks. */
    private final Set<UUID> pendingRefresh = ConcurrentHashMap.newKeySet();

    @Override
    public void machineChanged(@NotNull WorldPosition position) {
        if (this.openMachines.isEmpty()) return;
        forViewers(position, viewer -> {
            if (!this.pendingRefresh.add(viewer.getUniqueId())) return;
            // At most every 5 ticks: a machine on a network changes almost every tick.
            viewer.getScheduler().runDelayed(this.plugin, task -> {
                this.pendingRefresh.remove(viewer.getUniqueId());
                if (position.equals(this.openMachines.get(viewer.getUniqueId()))) refresh(viewer);
            }, () -> this.pendingRefresh.remove(viewer.getUniqueId()), 5);
        });
    }

    @Override
    public void machineGone(@NotNull WorldPosition position) {
        forViewers(position, viewer -> viewer.getScheduler().run(this.plugin, task -> {
            if (position.equals(this.openMachines.get(viewer.getUniqueId()))) viewer.closeInventory();
        }, null));
    }

    private void forViewers(WorldPosition position, Consumer<Player> action) {
        this.openMachines.forEach((id, at) -> {
            if (!at.equals(position)) return;
            Player viewer = Bukkit.getPlayer(id);
            if (viewer != null) action.accept(viewer);
        });
    }

    /** Re-renders the player's open menu after a change. */
    void refresh(@NotNull Player player) {
        // Rebuild the machine buttons in place. zMenu's updateInventory reopens the menu instead, which
        // fires a close (losing the open machine) and reopens a closed menu if it runs too late.
        if (!this.openMachines.containsKey(player.getUniqueId())) return;
        // Not a zMenu menu (or unreadable): nothing to re-render. The close listener, not this, forgets
        // the open machine; forgetting it here would blank every placeholder of a menu still open.
        if (!(topHolder(player) instanceof InventoryEngine engine)) return;
        // Only slots: their item changes. Gauges (energy, progress) keep their own periodic update,
        // which re-reads the placeholders; rebuilding them would restart that timer before it fires.
        for (Button button : engine.getButtons()) {
            if (!(button instanceof SlotButton)) continue;
            for (int slot : button.getSlots()) engine.cancel(slot);
            engine.buildButton(button, new Placeholders());
        }
    }

    /** The holder of the player's open top inventory ({@link InventoryViews}: it differs between versions). */
    private @Nullable Object topHolder(Player player) {
        try {
            return InventoryViews.topInventory(player).getHolder();
        } catch (IllegalStateException e) {
            if (!this.topHolderFailed) {
                this.topHolderFailed = true;
                this.plugin.getLogger().log(Level.WARNING, "Could not read open menus; machine menus will not refresh", e);
            }
            return null;
        }
    }

    private volatile boolean topHolderFailed;

    private @Nullable String placeholder(OfflinePlayer player, String key) {
        LoadedMachine machine = machineOf(player);
        if (machine == null) return "";
        // data_<id>: a setting the machine type declares (machineengine_data buttons change them).
        if (key.startsWith("side_") && machine.instance() != null) {
            String side = SideButton.placeholder(machine.instance(), key);
            if (side != null) return side;
        }
        if (key.startsWith("data_") && machine.instance() != null) {
            var data = DataButton.key(machine.instance(), key.substring("data_".length()));
            if (data != null) return String.valueOf(machine.instance().context().get(data));
        }
        return MachinePlaceholders.resolve(machine.inspect(), key);
    }

    /** Menu file names loaded, for status output. */
    public @NotNull Set<String> menuNames() {
        return Collections.unmodifiableSet(this.menus.keySet());
    }
}
