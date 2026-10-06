package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.world.BlockPosition;
import fr.robie.machineengine.api.world.WorldFluid;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What a machine can see and do in the world around it ({@link MachineContext#world()}). Every call
 * happens at once, on the owner thread; positions of blocks owned by another thread (Folia: another
 * region) or not loaded read as empty and are never changed.
 */
@ApiStatus.NonExtendable
public interface MachineWorld {

    // -- reading -------------------------------------------------------------------------------

    /**
     * The block at {@code position}: a CraftEngine block's id, or a vanilla block's (e.g.
     * {@code minecraft:air}); {@code null} if not loaded or not owned by this thread.
     */
    @Nullable Key blockAt(@NotNull BlockPosition position);

    /** The fluid in the block at {@code position}, or {@code null} if there is none, or it is not loaded or owned. */
    @Nullable WorldFluid fluidAt(@NotNull BlockPosition position);

    /** How hard the block at {@code position} is to break (vanilla hardness); -1 if unbreakable or unknown. */
    float blockHardness(@NotNull BlockPosition position);

    /** The redstone signal this machine's block receives (0 to 15). */
    int redstonePower();

    // -- changing blocks and fluids ------------------------------------------------------------

    /** Places a source block of the platform's {@code fluid} (e.g. {@code minecraft:water}) where there is air or flowing fluid. */
    boolean placeFluid(@NotNull BlockPosition position, @NotNull Key fluid);

    /** Removes the fluid source block at {@code position}; false if there is none or it is not owned. */
    boolean takeFluid(@NotNull BlockPosition position);

    /** Places the vanilla block {@code block} (e.g. {@code minecraft:stone}) where there is air or a fluid. */
    boolean placeBlock(@NotNull BlockPosition position, @NotNull Key block);

    /**
     * Breaks the block at {@code position}, its drops falling as items; false if it could not.
     * Machines and transports are never broken.
     */
    boolean breakBlock(@NotNull BlockPosition position);

    /** Takes the dropped items lying in the block at {@code position} (e.g. a tractor beam), as resources. */
    @NotNull List<ResourceStack> collectItems(@NotNull BlockPosition position);

    // -- showing -------------------------------------------------------------------------------

    /**
     * Shows a state of the machine on its block: sets the block property {@code property} (e.g. a
     * valve's {@code mode}) to {@code value}, if the block has it and allows that value.
     */
    void setBlockProperty(@NotNull String property, @NotNull String value);

    /** Plays the platform's {@code sound} at the machine. */
    void playSound(@NotNull Key sound, float volume, float pitch);

    /** Draws particles from the machine to {@code to} (a beam), e.g. {@code minecraft:dust} in {@code color} (RGB). */
    void particleLine(@NotNull BlockPosition to, int color);
}
