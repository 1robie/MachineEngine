package fr.robie.machineengine.nms;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * For versions without a module of their own (1.21.x, 26.1, 26.2) and for headless tests: works on
 * every supported version through reflection, at some cost per call.
 */
public final class ReflectiveNms implements NmsAccess {
    private static final MethodHandle GET_OPEN_INVENTORY;
    private static final MethodHandle GET_TOP_INVENTORY;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // findVirtual adapts to InventoryView being a class (1.20.6) or an interface (1.21+).
            GET_OPEN_INVENTORY = lookup.findVirtual(HumanEntity.class, "getOpenInventory", MethodType.methodType(InventoryView.class));
            GET_TOP_INVENTORY = lookup.findVirtual(InventoryView.class, "getTopInventory", MethodType.methodType(Inventory.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public @NotNull String name() {
        return "reflective";
    }

    @Override
    public @NotNull Inventory topInventory(@NotNull HumanEntity player) {
        try {
            return (Inventory) GET_TOP_INVENTORY.invoke(GET_OPEN_INVENTORY.invoke(player));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not read the open inventory of " + player.getName(), e);
        }
    }

    @Override
    public @Nullable PlayerInput input(@NotNull Player player) {
        try {
            Object input = player.getClass().getMethod("getCurrentInput").invoke(player);
            Class<?> type = input.getClass();
            return new PlayerInput(is(type, input, "isForward"), is(type, input, "isBackward"), is(type, input, "isLeft"),
                    is(type, input, "isRight"), is(type, input, "isJump"), is(type, input, "isSneak"), is(type, input, "isSprint"));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static boolean is(Class<?> type, Object input, String method) throws ReflectiveOperationException {
        Method getter = type.getMethod(method);
        return (boolean) getter.invoke(input);
    }

    @Override
    public boolean setGlider(@NotNull ItemMeta meta, boolean glider) {
        try {
            meta.getClass().getMethod("setGlider", boolean.class).invoke(meta, glider);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
