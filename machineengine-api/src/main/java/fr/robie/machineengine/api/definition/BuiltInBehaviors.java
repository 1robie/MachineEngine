package fr.robie.machineengine.api.definition;

import fr.robie.machineengine.api.MachineEngineKeys;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.data.DataValue.DataList;
import fr.robie.machineengine.api.error.MachineError.InvalidConfiguration;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.machine.MachineContext;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.ProcessingBehavior;
import fr.robie.machineengine.api.process.ProcessBinding;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.world.RelativeSide;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Behavior types MachineEngine provides for data-driven machines. */
public final class BuiltInBehaviors {

    /**
     * {@link ProcessingBehavior}:
     * <pre>{@code
     * behavior:
     *   type: machineengine:processing
     *   recipes: mypack:smelting      # recipe type
     *   inputs: [input]               # storages inputs come from (may be empty for generators)
     *   output: output                # storage outputs go to
     *   costs: energy                 # optional storage per-tick costs are paid from
     *   push: front                   # optional: push the output to this side
     *   retry-ticks: 20               # optional
     * }</pre>
     */
    public static final BehaviorType PROCESSING = BehaviorType.of(MachineEngineKeys.key("processing"), BuiltInBehaviors::processing);

    /** A machine that only stores (chests, tanks, batteries): {@code type: machineengine:storage}. */
    public static final BehaviorType STORAGE = BehaviorType.of(MachineEngineKeys.key("storage"),
            (config, context) -> Result.ok(type -> MachineContext::sleep));

    private BuiltInBehaviors() {
    }

    private static Result<MachineBehavior.Factory> processing(DataMap config, DefinitionContext context) {
        Result<DataValue> recipesSpec = config.require("recipes");
        if (recipesSpec.isErr()) return Result.err(recipesSpec.error());
        Result<RecipeType<ProcessingRecipe>> recipes = context.processingRecipeType(recipesSpec.value());
        if (recipes.isErr()) return Result.err(recipes.error());
        List<String> inputs = new ArrayList<>();
        DataValue inputSpec = config.get("inputs").orElse(DataList.EMPTY);
        if (inputSpec instanceof DataValue.DataString single) {
            inputs.add(single.value());
        } else {
            Result<List<String>> list = Codecs.list(Codecs.STRING).decode(inputSpec);
            if (list.isErr()) return Result.err(list.error());
            inputs.addAll(list.value());
        }
        Result<String> output = config.getString("output");
        if (output.isErr()) return Result.err(output.error());
        String costs = config.getString("costs").toOptional().orElse(null);
        ProcessBinding binding = new ProcessBinding(inputs, output.value(), costs);
        long retry = config.getLong("retry-ticks").toOptional().orElse(20L);
        if (!config.has("push")) return Result.ok(ProcessingBehavior.of(recipes.value(), binding));
        Result<String> push = config.getString("push");
        if (push.isErr()) return Result.err(push.error());
        try {
            RelativeSide side = RelativeSide.valueOf(push.value().toUpperCase(Locale.ROOT));
            return Result.ok(ProcessingBehavior.pushing(recipes.value(), binding, side, retry));
        } catch (IllegalArgumentException e) {
            return Result.err(new InvalidConfiguration("push must be one of front, back, left, right, top, bottom",
                    Map.of("value", push.value())));
        }
    }
}
