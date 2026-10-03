package fr.robie.machineengine.api.machine;

import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.process.ProcessStatus;
import fr.robie.machineengine.api.process.ProcessView;
import fr.robie.machineengine.api.upgrade.StandardModifiers;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.world.RelativeSide;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A ready-made behavior for recipe machines: start the first recipe (registration order) whose
 * inputs are available, run it, deliver outputs, and optionally push outputs to a neighbor.
 * Sleeps whenever there is nothing to do.
 *
 * <ul>
 *   <li>no startable recipe → {@link SchedulerState#WAITING}</li>
 *   <li>running without per-tick costs → {@link SchedulerState#SCHEDULED} until done</li>
 *   <li>running with per-tick costs → {@link SchedulerState#ACTIVE}</li>
 *   <li>outputs do not fit (here or at the push target) → {@link SchedulerState#BLOCKED},
 *       retried every {@code retryTicks}</li>
 * </ul>
 */
public final class ProcessingBehavior implements MachineBehavior {
    private final RecipeType<ProcessingRecipe> recipeType;
    private final ProcessBinding binding;
    private final @Nullable RelativeSide pushSide;
    private final long retryTicks;

    private ProcessingBehavior(RecipeType<ProcessingRecipe> recipeType, ProcessBinding binding,
                               @Nullable RelativeSide pushSide, long retryTicks) {
        this.recipeType = recipeType;
        this.binding = binding;
        this.pushSide = pushSide;
        this.retryTicks = retryTicks;
    }

    public static @NotNull MachineBehavior.Factory of(@NotNull RecipeType<ProcessingRecipe> recipeType,
                                                      @NotNull ProcessBinding binding) {
        Objects.requireNonNull(recipeType);
        Objects.requireNonNull(binding);
        return type -> new ProcessingBehavior(recipeType, binding, null, 20);
    }

    /** Also pushes the output storage into the neighbor on {@code side} every tick it runs. */
    public static @NotNull MachineBehavior.Factory pushing(@NotNull RecipeType<ProcessingRecipe> recipeType,
                                                           @NotNull ProcessBinding binding, @NotNull RelativeSide side,
                                                           long retryTicks) {
        Objects.requireNonNull(recipeType);
        Objects.requireNonNull(binding);
        Objects.requireNonNull(side);
        return type -> new ProcessingBehavior(recipeType, binding, side, retryTicks);
    }

    @Override
    public void tick(@NotNull MachineContext context) {
        boolean outputLeft = pushOutputs(context);
        ProcessView process = context.process().current();
        if (process == null) {
            if (!startNext(context)) {
                context.setActive(false);
                if (outputLeft) {
                    context.block(new MachineError.TransferRejected("the output could not be pushed", null), this.retryTicks);
                } else {
                    context.waitForInput();
                }
                return;
            }
        }
        boolean oneTick = Objects.requireNonNull(context.process().current()).duration() <= 1;
        ProcessStatus status = context.process().advance();
        // Working while a process runs, and between two processes (no flicker); not while it waits.
        context.setActive(status == ProcessStatus.RUNNING || status == ProcessStatus.COMPLETED);
        switch (status) {
            case RUNNING -> {
                ProcessView running = Objects.requireNonNull(context.process().current());
                if (running.costPerTick().isEmpty()) context.wakeIn(running.remaining());
            }
            case WAITING_FOR_COST -> context.waitForInput();
            case OUTPUT_BLOCKED, RETURNING -> context.block(
                    new MachineError.TransferRejected("the outputs do not fit", null), this.retryTicks);
            case COMPLETED, IDLE -> {
                if (oneTick && status == ProcessStatus.COMPLETED) runMore(context);
                pushOutputs(context);
            }
        }
    }

    /**
     * One-tick processes: up to {@code processes_per_tick - 1} more in the same tick (speed upgrades),
     * each paying its own costs. Stops at the first that does not complete at once.
     */
    private void runMore(MachineContext context) {
        long extra = context.modified(StandardModifiers.PROCESSES_PER_TICK, 1) - 1;
        for (long i = 0; i < extra; i++) {
            if (context.process().current() != null || !startNext(context)) return;
            if (context.process().advance(1) != ProcessStatus.COMPLETED) return;
        }
    }

    /** Runs processes for the caught-up ticks, without pushing (own storages only, §18). */
    @Override
    public void catchUp(@NotNull MachineContext context, long ticks) {
        long budget = ticks;
        while (budget > 0) {
            ProcessView process = context.process().current();
            if (process == null) {
                if (!startNext(context)) return;
                process = Objects.requireNonNull(context.process().current());
            }
            long step = Math.min(budget, process.remaining());
            ProcessStatus status = context.process().advance(step);
            budget -= Math.max(step, 1);
            if (status != ProcessStatus.COMPLETED) return;
        }
    }

    private boolean startNext(MachineContext context) {
        for (ProcessingRecipe recipe : context.recipes().all(this.recipeType)) {
            Result<ProcessView> started = context.process().start(recipe, this.binding);
            if (started.isOk()) return true;
        }
        return false;
    }

    /** @return whether output remains that could not be pushed */
    private boolean pushOutputs(MachineContext context) {
        if (this.pushSide == null) return false;
        context.push(this.pushSide, this.binding.output(), variant -> true, Long.MAX_VALUE);
        return !isEmpty(context);
    }

    private boolean isEmpty(MachineContext context) {
        return context.storage(this.binding.output()).views().stream().allMatch(view -> view.isEmpty());
    }
}
