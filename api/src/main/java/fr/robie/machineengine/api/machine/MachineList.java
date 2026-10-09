package fr.robie.machineengine.api.machine;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Rows a menu can show one per button (D40): the upgrades a machine takes, the modules of the gear in a
 * station. A machine type declares its lists by id ({@link MachineType.Builder#list}). Menus read them
 * with the placeholders {@code %zmenu_machineengine_list_<id>_size%} and
 * {@code %zmenu_machineengine_list_<id>_<index>_<field>%} (index from 0); a {@code machineengine_action}
 * button with {@code list: <id>} is hidden when its {@code index} is past the end, and its click gets the
 * same index.
 *
 * <p>Read from the viewer's thread for display, like the other placeholders: compute rows from the
 * machine's state without changing it.</p>
 */
@FunctionalInterface
public interface MachineList {

    /** The rows, each a map of field to text (e.g. {@code id}, {@code count}, {@code max}). */
    @NotNull List<Map<String, String>> rows(@NotNull MachineContext context);
}
