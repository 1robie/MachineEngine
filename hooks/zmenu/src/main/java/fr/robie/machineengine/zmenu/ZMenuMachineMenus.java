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
import fr.robie.machineengine.api.MachineEngine;
import fr.robie.machineengine.api.config.EngineConfig;
import fr.robie.machineengine.api.player.MachinePlayer;
import fr.robie.machineengine.api.world.WorldPosition;
import fr.robie.machineengine.core.machine.LoadedMachine;
import fr.robie.machineengine.core.machine.MachineInstanceImpl;
import fr.robie.machineengine.core.player.CorePlayers;
import fr.robie.machineengine.api.platform.Nms;
import fr.robie.machineengine.api.platform.Scheduler;
import fr.robie.machineengine.api.platform.Task;
import fr.robie.machineengine.paper.MachineLocator;
import fr.robie.machineengine.paper.MachineMenuService;
import fr.robie.machineengine.paper.MachinePlaceholders;
import fr.robie.machineengine.paper.player.PlayerValues;
import fr.robie.messageflow.formatter.Placeholder;
import fr.robie.messageflow.logger.Logger;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Machine menus on zMenu (§23.3). Everything is configured in YAML:
 * <ul>
 *   <li>menu files in {@code plugins/MachineEngine/menus/} (standard zMenu inventories);</li>
 *   <li>a machine type opens {@code menus.by-type} from {@code config.yml}, else the menu file
 *       {@code <namespace>_<value>.yml}, else {@code menus.default};</li>
 *   <li>placeholders {@code %zmenu_machineengine_<key>%} ({@link MachinePlaceholders}) resolve
 *       against the machine the player has open, in any zMenu button;</li>
 *   <li>button types {@code machineengine_slot}, {@code machineengine_cancel_process}, {@code machineengine_upgrades},
 *       {@code machineengine_data}, {@code machineengine_side}, {@code machineengine_menu} and
 *       {@code machineengine_action} (the machine type's own actions and lists, D40) interact with the machine;</li>
 *   <li>placeholders {@code %zmenu_machineengine_player_<namespace>_<id>%} show the player's own data and
 *       settings, and {@code machineengine_player_setting} buttons change a setting, in the generated
 *       {@code player_settings.yml} menu ({@code /machinesettings}) or any other (D37).</li>
 * </ul>
 */
public final class ZMenuMachineMenus implements MachineMenuService {
    public static final String PLACEHOLDER_PREFIX = "machineengine_";
    static final String DEFAULT_MENU_RESOURCE = "menus/machine.yml";

    private final Plugin plugin;
    private final Scheduler scheduler;
    private final InventoryManager inventories;
    private final ButtonManager buttons;
    private final MachineLocator locator;
    private final Map<String, Inventory> menus = new LinkedHashMap<>();
    private final Map<UUID, WorldPosition> openMachines = new ConcurrentHashMap<>();
    private final InventoryListener listener;
    /** Players a machine menu is being opened for, until zMenu has opened it. */
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    /** Per player, the machine menu zMenu opened: only its close ends the player's machine session. */
    private final Map<UUID, BaseInventory> views = new ConcurrentHashMap<>();
    /** Per viewer of a machine menu: the timer redrawing its live buttons ({@link #isLive}). */
    private final Map<UUID, Task> tickers = new ConcurrentHashMap<>();
    private Map<Key, String> menuByType = Map.of();
    private String defaultMenu = "machine";

    private ZMenuMachineMenus(Plugin plugin, InventoryManager inventories, ButtonManager buttons, MachineLocator locator) {
        this.plugin = plugin;
        this.scheduler = MachineEngine.scheduler(plugin);
        this.inventories = inventories;
        this.buttons = buttons;
        this.locator = locator;
        this.listener = new InventoryListener() {
            @Override
            public void onInventoryPostOpen(Player player, BaseInventory baseInventory) {
                UUID id = player.getUniqueId();
                if (ZMenuMachineMenus.this.opening.remove(id)) ZMenuMachineMenus.this.views.put(id, baseInventory);
            }

            @Override
            public void onInventoryClose(Player player, BaseInventory baseInventory) {
                UUID id = player.getUniqueId();
                // Another menu's close changes nothing; so does the previous machine menu's, when it comes
                // after the next one opened (machineengine_menu, on Folia a tick later).
                if (!ZMenuMachineMenus.this.views.remove(id, baseInventory)) return;
                // Closed while the next machine menu opens: that one keeps the session.
                if (!ZMenuMachineMenus.this.opening.contains(id)) ZMenuMachineMenus.this.openMachines.remove(id);
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
        buttons.register(new ActionButton.Loader(plugin, menus));
        buttons.register(new PlayerSettingButton.Loader(plugin));
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
        this.views.clear();
        this.opening.clear();
        this.tickers.values().forEach(Task::cancel);
        this.tickers.clear();
    }

    @Override
    public void reload() {
        this.inventories.deleteInventories(this.plugin);
        this.menus.clear();
        this.defaultMenu = MachineEngine.config().get(EngineConfig.DEFAULT_MENU);
        Map<Key, String> byType = new LinkedHashMap<>();
        MachineEngine.config().get(EngineConfig.MENUS_BY_TYPE).forEach((type, menu) -> {
            if (Key.parseable(type)) byType.put(Key.key(type), menu);
            else Logger.warn("[zmenu]", "config.yml: menus.by-type has an invalid machine type '%type%'", Placeholder.of("type", type));
        });
        this.menuByType = Map.copyOf(byType);

        File folder = new File(this.plugin.getDataFolder(), "menus");
        if (!new File(this.plugin.getDataFolder(), DEFAULT_MENU_RESOURCE).exists()) {
            this.plugin.saveResource(DEFAULT_MENU_RESOURCE, false);
        }
        CorePlayers players = PlayerSettingButton.players();
        if (players != null) PlayerSettingsMenu.write(folder.toPath(), List.copyOf(players.settings()));
        File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            String name = file.getName().substring(0, file.getName().length() - 4);
            try {
                Inventory inventory = this.inventories.loadInventory(this.plugin, file);
                if (inventory != null) this.menus.put(name, inventory);
            } catch (Exception e) {
                Logger.warn("[zmenu]", "Could not load machine menu %file%: %error%", e, Placeholder.of("file", file.getName(), "error", e.getMessage()));
            }
        }
        Logger.info("[zmenu]", "Loaded %count% machine menu(s) %menus%",
                Placeholder.of("count", String.valueOf(this.menus.size()), "menus", String.valueOf(this.menus.keySet())));
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
        this.opening.add(id);
        this.inventories.openInventory(player, menu);
        // zMenu may open on the player's scheduler (Folia), or refuse to: a menu never opened must not
        // keep the next close from ending the session.
        this.scheduler.runLater(player, () -> this.opening.remove(id), () -> this.opening.remove(id), 20);
        startTicker(player, menu);
        return true;
    }

    /**
     * Redraws the live buttons of the player's menu every {@code update-interval} of {@code menu}, all in
     * the same tick, until the player leaves the machine's menus. zMenu's own {@code update} gives each
     * button its own timer, so the cells of one fill bar would show different moments.
     */
    private void startTicker(Player player, Inventory menu) {
        UUID id = player.getUniqueId();
        Task old = this.tickers.remove(id);
        if (old != null) old.cancel();
        long period = Math.max(1, menu.getUpdateInterval() / 50);
        Task task = this.scheduler.runTimer(player, () -> {
            if (this.openMachines.containsKey(id)) {
                rebuild(player, false);
            } else {
                Task self = this.tickers.remove(id);
                if (self != null) self.cancel();
            }
        }, () -> this.tickers.remove(id), period, period);
        if (task != null) this.tickers.put(id, task);
    }

    /**
     * Whether a button's item depends on the machine through its material, e.g. a fill bar cell
     * {@code craftengine:mekanized:gui/bar/energy/0/%zmenu_machineengine_storage_energy_cell_0%}. Such
     * buttons are rebuilt by MachineEngine, together, and need no zMenu {@code update}.
     */
    static boolean isLive(@NotNull Button button) {
        var item = button.getItemStack();
        String material = item == null ? null : item.getMaterial();
        return material != null && material.contains("%zmenu_" + PLACEHOLDER_PREFIX);
    }

    @Override
    public boolean openPlayerSettings(@NotNull Player player) {
        Inventory menu = this.menus.get(PlayerSettingsMenu.NAME);
        if (menu == null) return false;
        this.inventories.openInventory(player, menu);
        return true;
    }

    @NotNull Scheduler scheduler() {
        return this.scheduler;
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
            if (!viewer.equals(player)) this.scheduler.run(viewer, () -> refresh(viewer), null);
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
            this.scheduler.runLater(viewer, () -> {
                this.pendingRefresh.remove(viewer.getUniqueId());
                if (position.equals(this.openMachines.get(viewer.getUniqueId()))) refresh(viewer);
            }, () -> this.pendingRefresh.remove(viewer.getUniqueId()), 5);
        });
    }

    @Override
    public void machineGone(@NotNull WorldPosition position) {
        forViewers(position, viewer -> this.scheduler.run(viewer, () -> {
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
        rebuild(player, true);
    }

    /**
     * Rebuilds the live buttons ({@link #isLive}) of the player's open machine menu, and its slots too if
     * {@code slots}. Buttons with only placeholders in their name and lore keep zMenu's own periodic
     * update; rebuilding them would restart that timer before it fires.
     */
    private void rebuild(@NotNull Player player, boolean slots) {
        // Not a zMenu menu (or unreadable): nothing to re-render. The close listener, not this, forgets
        // the open machine; forgetting it here would blank every placeholder of a menu still open.
        if (!(topHolder(player) instanceof InventoryEngine engine)) return;
        Placeholders placeholders = new Placeholders();
        for (Button button : engine.getButtons()) {
            if (!(slots && button instanceof SlotButton) && !isLive(button)) continue;
            for (int slot : button.getSlots()) engine.cancel(slot);
            // A button that hides itself now (a list row past the end) is cleared, not drawn.
            if (!button.checkPermission(player, engine, placeholders)) {
                for (int slot : button.getSlots()) engine.removeItem(slot);
                continue;
            }
            engine.buildButton(button, placeholders);
        }
    }

    /** The holder of the player's open top inventory (through {@link Nms}: it differs between versions). */
    private @Nullable Object topHolder(Player player) {
        try {
            return Nms.get().topInventory(player).getHolder();
        } catch (IllegalStateException e) {
            if (!this.topHolderFailed) {
                this.topHolderFailed = true;
                Logger.warn("[zmenu]", "Could not read open menus; machine menus will not refresh", e);
            }
            return null;
        }
    }

    private volatile boolean topHolderFailed;

    private @Nullable String placeholder(OfflinePlayer player, String key) {
        // player_<namespace>_<id>: the player's own data and settings (D37), in any menu.
        if (key.startsWith("player_")) {
            CorePlayers players = PlayerSettingButton.players();
            MachinePlayer data = players == null ? null : players.find(player.getUniqueId()).orElse(null);
            String value = data == null ? null : PlayerValues.placeholder(players, data, key.substring("player_".length()));
            if (value != null) return value;
        }
        LoadedMachine machine = machineOf(player);
        if (machine == null) return "";
        // data_<id>: a setting the machine type declares (machineengine_data buttons change them).
        if (key.startsWith("side_") && machine.instance() != null) {
            String side = SideButton.placeholder(machine.instance(), key);
            if (side != null) return side;
        }
        // data_<id>_cell: a percent setting as the level of a one-slot bar (a factory lane's arrow).
        if (key.startsWith("data_") && key.endsWith("_cell") && machine.instance() != null) {
            var data = DataButton.key(machine.instance(), key.substring("data_".length(), key.length() - "_cell".length()));
            if (data != null) {
                String level = MachinePlaceholders.percentCell(String.valueOf(machine.instance().context().get(data)));
                if (level != null) return level;
            }
        }
        if (key.startsWith("data_") && machine.instance() != null) {
            var data = DataButton.key(machine.instance(), key.substring("data_".length()));
            if (data != null) return String.valueOf(machine.instance().context().get(data));
        }
        // list_<id>_size, list_<id>_<index>_<field>: the rows of a list the machine type declares (D40).
        if (key.startsWith("list_") && machine.instance() != null) {
            MachineInstanceImpl instance = machine.instance();
            String row = MachinePlaceholders.list(instance.type().lists().keySet(), instance::list, key);
            if (row != null) return row;
        }
        return MachinePlaceholders.resolve(machine.inspect(), key);
    }

    /** Menu file names loaded, for status output. */
    public @NotNull Set<String> menuNames() {
        return Collections.unmodifiableSet(this.menus.keySet());
    }
}
