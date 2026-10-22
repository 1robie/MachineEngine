package fr.robie.machineengine.core.definition;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.AddonRegistration;
import fr.robie.machineengine.api.addon.RegistrationContext;
import fr.robie.machineengine.api.data.Codecs;
import fr.robie.machineengine.api.data.DataMap;
import fr.robie.machineengine.api.data.DataValue.DataList;
import fr.robie.machineengine.api.data.DataValue.DataString;
import fr.robie.machineengine.api.data.DataValue;
import fr.robie.machineengine.api.definition.BehaviorType;
import fr.robie.machineengine.api.definition.DefinitionContext;
import fr.robie.machineengine.api.definition.DefinitionFile;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.error.MachineError;
import fr.robie.machineengine.api.error.Result;
import fr.robie.machineengine.api.item.ItemTagContribution;
import fr.robie.machineengine.api.item.Items;
import fr.robie.machineengine.api.machine.CatchUpPolicy;
import fr.robie.machineengine.api.machine.MachineBehavior;
import fr.robie.machineengine.api.machine.MachineType;
import fr.robie.machineengine.api.machine.PortDefinition;
import fr.robie.machineengine.api.machine.PortDirection;
import fr.robie.machineengine.api.machine.StorageDefinition;
import fr.robie.machineengine.api.multiblock.MultiblockDefinitions;
import fr.robie.machineengine.api.multiblock.MultiblockType;
import fr.robie.machineengine.api.recipe.ProcessingRecipe;
import fr.robie.machineengine.api.recipe.Recipe;
import fr.robie.machineengine.api.recipe.RecipeType;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryAccess;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.registry.ReloadContext;
import fr.robie.machineengine.api.resource.Fluids;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceStack;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.ResourceVariant;
import fr.robie.machineengine.api.resource.fluid.FluidType;
import fr.robie.machineengine.api.resource.match.ResourceMatcher;
import fr.robie.machineengine.api.resource.match.ResourcePredicate;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;
import fr.robie.machineengine.api.version.Version;
import fr.robie.machineengine.api.world.RelativeSide;
import fr.robie.machineengine.core.resource.fluid.FluidSpecParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Compiles data-driven definitions into the same objects Java addons register (ARCHITECTURE.md §29):
 * YAML is a declarative layer over the public API, not a separate runtime. It only references
 * registered ids and registered {@link BehaviorType}s: no class loading, scripts or file access.
 *
 * <pre>{@code
 * resource-categories:  { <key>: { unit: FE, scale: 0 } }
 * resource-types:       { <key>: { category: <key>, color: "#RRGGBB" } }   # color optional
 *                       { <key>: { color: "#RRGGBB", fluid: { world: placeable, look: …, physics: … } } }
 *                       # a fluid (D47): category machineengine:fluid by default; fluid: see FluidSpecParser
 * recipe-types:         [ <key>, … ]
 * machines:
 *   <key>:
 *     storages: { <id>: { slots: 1, capacity: 64, filter: <filter> } }
 *     ports:    { <id>: { storage: <id>, direction: input, sides: [top, back], filter: <filter>, rate: 64 } }
 *     upgrade-slots: 2
 *     catch-up: 1200            # ticks caught up after unloading; 0 = frozen
 *     behavior: { type: machineengine:processing, … }
 * recipes:                       # reloadable: /machineengine reload recipes
 *   <key>: { type: <recipe type>, duration: 100, inputs: [<matcher>], outputs: [<stack>], cost-per-tick: [<stack>] }
 * }</pre>
 *
 * An invalid entry is skipped with a {@link DiagnosticCodes#DEFINITION_SKIPPED} warning naming the
 * file and path; everything else still loads. Machine types need a restart to change; recipes
 * reload live.
 */
public final class DefinitionCompiler {
    /** The addon that owns everything defined in data files. */
    public static final AddonId ADDON = new AddonId("definitions");

    /** Turns an item id into an item resource; provided by the platform (Paper). */
    @FunctionalInterface
    public interface ItemResolver {
        @NotNull Result<ResourceVariant> item(@NotNull Key id);

        /**
         * Whether item ids can be resolved yet. While not, recipes are left out silently; the platform
         * reloads recipes once its items are loaded (e.g. after CraftEngine's first load).
         */
        default boolean ready() {
            return true;
        }

        ItemResolver NONE = id -> Result.err(MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED,
                "item resources need a platform (items are not available headless)"));
    }

    private DefinitionCompiler() {
    }

    /**
     * The registration of data-driven content. It runs after every addon in {@code after}, so its
     * files can use their resources, recipe types and behavior types.
     *
     * @param files read again on every recipe reload
     */
    public static @NotNull AddonRegistration registration(@NotNull Supplier<List<DefinitionFile>> files,
                                                          @NotNull ItemResolver items, @NotNull Collection<AddonId> after,
                                                          @NotNull Version version) {
        AddonRegistration.Builder builder = AddonRegistration.builder(ADDON, version);
        after.forEach(addon -> builder.after(addon.name()));
        return builder.register(context -> {
            List<DefinitionFile> loaded = enabled(files.get());
            compileStatic(context, loaded, items);
            context.recipes(reload -> {
                if (items.ready()) compileRecipes(reload, enabled(files.get()), items);
            });
            context.itemTags(reload -> compileItemTags(reload, enabled(files.get())));
            context.reloadable(Registries.RESOURCE_TAG, reload -> compileResourceTags(reload, enabled(files.get())));
        }).build();
    }

    private static List<DefinitionFile> enabled(List<DefinitionFile> files) {
        return files.stream().filter(file -> file.content().getBool("enabled").toOptional().orElse(true)).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Static content (registration phase)
    // ------------------------------------------------------------------------------------------

    static void compileStatic(RegistrationContext context, List<DefinitionFile> files, ItemResolver items) {
        Consumer<Diagnostic> report = context::report;
        for (DefinitionFile file : files) {
            entries(file, "resource-categories", report, (key, spec, where) -> {
                DataMap map = asMap(spec, where, report);
                if (map == null) return;
                ResourceCategory.Builder builder = ResourceCategory.builder(key)
                        .unit(map.getString("unit").toOptional().orElse(""));
                long scale = map.getLong("scale").toOptional().orElse(0L);
                if (scale < 0 || scale > 18) {
                    skip(report, where, "scale must be within 0..18");
                    return;
                }
                context.resourceCategories().register(builder.scale((int) scale).build());
            });
        }
        for (DefinitionFile file : files) {
            entries(file, "resource-types", report, (key, spec, where) -> {
                DataMap map = asMap(spec, where, report);
                if (map == null) return;
                boolean fluidSection = map.has("fluid");
                Result<ResourceCategory> category = !map.has("category") && fluidSection ? Result.ok(Fluids.category())
                        : map.getString("category").flatMap(Codecs::parseKey)
                        .flatMap(id -> lookup(context.registries(), Registries.RESOURCE_CATEGORY, id));
                if (category.isErr()) {
                    skip(report, where, category.error().message());
                    return;
                }
                String text = map.getString("color").toOptional().orElse(null);
                Integer color = text == null ? null : ResourceType.parseColor(text);
                if (text != null && color == null) {
                    skip(report, where, "color must be \"#RRGGBB\" but is " + text);
                    return;
                }
                if (fluidSection || category.value() == Fluids.category()) {
                    // A fluid (D47): every resource type in the fluid category is a FluidType.
                    if (category.value() != Fluids.category()) {
                        skip(report, where, "a fluid section needs the fluid category " + Fluids.CATEGORY_KEY.asString());
                        return;
                    }
                    FluidType.Builder builder = FluidType.builder(key);
                    if (color != null) builder.color(color);
                    if (fluidSection) {
                        DataMap fluid = asMap(map.get("fluid").orElseThrow(), where + ".fluid", report);
                        if (fluid == null) return;
                        Result<FluidType.Builder> applied = FluidSpecParser.apply(builder, fluid, context.registries(), null);
                        if (applied.isErr()) {
                            skip(report, where + ".fluid", applied.error().message());
                            return;
                        }
                    }
                    context.resourceTypes().register(builder.build());
                    return;
                }
                context.resourceTypes().register(color == null
                        ? ResourceType.of(key, category.value())
                        : ResourceType.of(key, category.value(), color));
            });
            for (Key key : keys(file, "recipe-types", report)) {
                context.recipeTypes().register(RecipeType.of(key, ProcessingRecipe.class));
            }
        }
        // Before the machines: their multiblock members name these (D33).
        for (DefinitionFile file : files) {
            entries(file, "multiblocks", report, (key, spec, where) -> {
                DataMap map = asMap(spec, where, report);
                if (map == null) return;
                Result<MultiblockType> type = MultiblockDefinitions.type(key, map);
                if (type.isErr()) {
                    skip(report, where, type.error().message());
                    return;
                }
                context.multiblockTypes().register(type.value());
            });
        }
        for (DefinitionFile file : files) {
            entries(file, "machines", report, (key, spec, where) -> {
                DataMap map = asMap(spec, where, report);
                if (map == null) return;
                Result<MachineType> machine = machine(key, map, new Context(context.registries(), items));
                if (machine.isErr()) {
                    skip(report, where, machine.error().message());
                    return;
                }
                context.machineTypes().register(machine.value());
            });
        }
    }

    /** Slots of a storage, or upgrade slots: more would be a typo (and past int, wrap around). */
    static final int MAX_SLOTS = 4096;

    private static Result<MachineType> machine(Key key, DataMap map, Context context) {
        MachineType.Builder builder = MachineType.builder(key);
        DataMap storages = map.getMap("storages").toOptional().orElse(DataMap.EMPTY);
        for (Map.Entry<String, DataValue> entry : storages.entries().entrySet()) {
            if (!(entry.getValue() instanceof DataMap storage)) return invalid("storages." + entry.getKey() + " must be a map");
            long slots = storage.getLong("slots").toOptional().orElse(1L);
            if (slots < 0 || slots > MAX_SLOTS) return invalid("storages." + entry.getKey() + ".slots must be within 0.." + MAX_SLOTS);
            Result<Long> capacity = storage.getLong("capacity");
            if (capacity.isErr()) return invalid("storages." + entry.getKey() + ".capacity is required");
            if (capacity.value() < 0) return invalid("storages." + entry.getKey() + ".capacity must be 0 or more");
            ResourcePredicate filter = ResourcePredicate.any();
            if (storage.has("filter")) {
                Result<ResourcePredicate> parsed = context.predicate(storage.get("filter").orElseThrow());
                if (parsed.isErr()) return invalid("storages." + entry.getKey() + ".filter: " + parsed.error().message());
                filter = parsed.value();
            }
            builder.storage(new StorageDefinition(entry.getKey(), (int) slots, capacity.value(), filter));
        }
        DataMap ports = map.getMap("ports").toOptional().orElse(DataMap.EMPTY);
        for (Map.Entry<String, DataValue> entry : ports.entries().entrySet()) {
            if (!(entry.getValue() instanceof DataMap port)) return invalid("ports." + entry.getKey() + " must be a map");
            Result<PortDefinition> parsed = port(entry.getKey(), port, context);
            if (parsed.isErr()) return invalid("ports." + entry.getKey() + ": " + parsed.error().message());
            builder.port(parsed.value());
        }
        long upgradeSlots = map.getLong("upgrade-slots").toOptional().orElse(0L);
        if (upgradeSlots < 0 || upgradeSlots > MAX_SLOTS) return invalid("upgrade-slots must be within 0.." + MAX_SLOTS);
        builder.upgradeSlots((int) upgradeSlots, upgrade -> true);
        long catchUp = map.getLong("catch-up").toOptional().orElse(0L);
        if (catchUp > 0) builder.catchUp(CatchUpPolicy.bounded(catchUp));

        Result<DataMap> behavior = map.getMap("behavior");
        if (behavior.isErr()) return invalid("behavior is required");
        Result<BehaviorType> type = behavior.value().getString("type").flatMap(Codecs::parseKey)
                .flatMap(id -> lookup(context.registries(), Registries.BEHAVIOR_TYPE, id));
        if (type.isErr()) return invalid("behavior.type: " + type.error().message());
        Result<MachineBehavior.Factory> factory = type.value().parse(behavior.value(), context);
        if (factory.isErr()) return invalid("behavior: " + factory.error().message());
        builder.behavior(factory.value());
        return Result.ok(builder.build());
    }

    private static Result<PortDefinition> port(String id, DataMap port, Context context) {
        Result<String> storage = port.getString("storage");
        if (storage.isErr()) return invalid("storage is required");
        PortDefinition.Builder builder = PortDefinition.builder(id, storage.value());
        if (port.has("direction")) {
            Result<String> direction = port.getString("direction");
            try {
                builder.direction(PortDirection.valueOf(direction.orElse("").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return invalid("direction must be input, output, input_output or disabled");
            }
        }
        if (port.has("sides")) {
            Result<List<String>> sides = port.require("sides").flatMap(Codecs.list(Codecs.STRING)::decode);
            if (sides.isErr() || sides.value().isEmpty()) return invalid("sides must be a non-empty list");
            Set<RelativeSide> parsed = EnumSet.noneOf(RelativeSide.class);
            for (String side : sides.value()) {
                try {
                    parsed.add(RelativeSide.valueOf(side.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    return invalid("unknown side '" + side + "' (front, back, left, right, top, bottom)");
                }
            }
            List<RelativeSide> list = List.copyOf(parsed);
            builder.sides(list.getFirst(), list.subList(1, list.size()).toArray(RelativeSide[]::new));
        }
        if (port.has("filter")) {
            Result<ResourcePredicate> filter = context.predicate(port.get("filter").orElseThrow());
            if (filter.isErr()) return Result.err(filter.error());
            builder.filter(filter.value());
        }
        if (port.has("rate")) builder.ratePerTick(port.getLong("rate").orElse(Long.MAX_VALUE));
        return Result.ok(builder.build());
    }

    // ------------------------------------------------------------------------------------------
    // Recipes (reloadable)
    // ------------------------------------------------------------------------------------------

    /**
     * {@code item-tags: { c:ores/osmium: [mekanized:osmium_ore, "#minecraft:iron_ores"] }}: item ids,
     * or {@code #tag} to include another item tag.
     */
    static void compileItemTags(ReloadContext<ItemTagContribution> reload, List<DefinitionFile> files) {
        Consumer<Diagnostic> report = reload::report;
        for (DefinitionFile file : files) {
            entries(file, "item-tags", report, (tag, spec, where) -> {
                Result<List<String>> values = Codecs.list(Codecs.STRING).decode(spec);
                if (values.isErr()) {
                    skip(report, where, "must be a list of item ids or #tags");
                    return;
                }
                Set<Key> items = new LinkedHashSet<>();
                Set<Key> includes = new LinkedHashSet<>();
                for (String value : values.value()) {
                    boolean include = value.startsWith("#");
                    Result<Key> id = Codecs.parseKey(include ? value.substring(1) : value);
                    if (id.isErr()) {
                        skip(report, where, "'" + value + "' is not a namespaced key");
                        return;
                    }
                    (include ? includes : items).add(id.value());
                }
                reload.register(new ItemTagContribution(contributionKey(file, tag), tag, items, includes));
            });
        }
    }

    /**
     * {@code resource-tags: { mekanized:water_vapor: [mekanized:steam, "#other:tag"] }}: resource type
     * keys; {@code #tag} includes a tag defined in the same files.
     */
    static void compileResourceTags(ReloadContext<ResourceTagContribution> reload, List<DefinitionFile> files) {
        Consumer<Diagnostic> report = reload::report;
        Map<Key, List<String>> declared = new LinkedHashMap<>();
        Map<Key, String> locations = new LinkedHashMap<>();
        Map<Key, DefinitionFile> owners = new LinkedHashMap<>();
        for (DefinitionFile file : files) {
            entries(file, "resource-tags", report, (tag, spec, where) -> {
                Result<List<String>> values = Codecs.list(Codecs.STRING).decode(spec);
                if (values.isErr()) {
                    skip(report, where, "must be a list of resource type keys or #tags");
                    return;
                }
                declared.computeIfAbsent(tag, key -> new ArrayList<>()).addAll(values.value());
                locations.putIfAbsent(tag, where);
                owners.putIfAbsent(tag, file);
            });
        }
        declared.forEach((tag, values) -> {
            Set<ResourceType> members = new LinkedHashSet<>();
            List<String> missing = new ArrayList<>();
            expandResourceTag(tag, declared, reload.registries(), members, missing, new HashSet<>());
            if (!missing.isEmpty()) {
                skip(report, locations.get(tag), "unknown resource types or tags left out: " + missing);
            }
            if (!members.isEmpty()) reload.register(new ResourceTagContribution(contributionKey(owners.get(tag), tag), tag, members));
        });
    }

    private static void expandResourceTag(Key tag, Map<Key, List<String>> declared, RegistryAccess registries,
                                          Set<ResourceType> members, List<String> missing, Set<Key> visited) {
        if (!visited.add(tag)) return;
        for (String value : declared.getOrDefault(tag, List.of())) {
            boolean include = value.startsWith("#");
            Result<Key> id = Codecs.parseKey(include ? value.substring(1) : value);
            if (id.isErr()) {
                missing.add(value);
            } else if (include) {
                if (declared.containsKey(id.value())) expandResourceTag(id.value(), declared, registries, members, missing, visited);
                else missing.add(value);
            } else {
                registries.registry(Registries.RESOURCE_TYPE).get(id.value()).ifPresentOrElse(members::add, () -> missing.add(value));
            }
        }
    }

    /** One contribution per file and tag: {@code definitions:<file>/<tag namespace>/<tag value>}. */
    private static Key contributionKey(DefinitionFile file, Key tag) {
        String fileId = file.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
        return Key.key(ADDON.name(), fileId + "/" + tag.namespace() + "/" + tag.value());
    }

    static void compileRecipes(ReloadContext<Recipe> reload, List<DefinitionFile> files, ItemResolver items) {
        Context context = new Context(reload.registries(), items);
        Consumer<Diagnostic> report = reload::report;
        for (DefinitionFile file : files) {
            entries(file, "recipes", report, (key, spec, where) -> {
                DataMap map = asMap(spec, where, report);
                if (map == null) return;
                Result<ProcessingRecipe> recipe = recipe(key, map, context);
                if (recipe.isErr()) {
                    skip(report, where, recipe.error().message());
                    return;
                }
                reload.register(recipe.value());
            });
        }
    }

    private static Result<ProcessingRecipe> recipe(Key key, DataMap map, Context context) {
        Result<DataValue> typeSpec = map.require("type");
        if (typeSpec.isErr()) return invalid("type is required");
        Result<RecipeType<ProcessingRecipe>> type = context.processingRecipeType(typeSpec.value());
        if (type.isErr()) return Result.err(type.error());
        ProcessingRecipe.Builder builder = ProcessingRecipe.builder(key, type.value())
                .duration(map.getLong("duration").toOptional().orElse(1L));
        for (DataValue input : list(map, "inputs")) {
            Result<ResourceMatcher> matcher = context.matcher(input);
            if (matcher.isErr()) return invalid("inputs: " + matcher.error().message());
            builder.input(matcher.value());
        }
        for (DataValue output : list(map, "chance-outputs")) {
            if (!(output instanceof DataMap entry)) return invalid("chance-outputs: each entry is a map with a chance");
            Result<ResourceStack> stack = context.stack(entry);
            if (stack.isErr()) return invalid("chance-outputs: " + stack.error().message());
            double chance = entry.get("chance").map(value -> value instanceof DataValue.DataDouble d ? d.value()
                    : value instanceof DataValue.DataLong l ? (double) l.value() : -1.0).orElse(-1.0);
            if (chance < 0 || chance > 1) return invalid("chance-outputs: chance must be between 0 and 1");
            builder.chanceOutput(stack.value(), chance);
        }
        for (DataValue output : list(map, "outputs")) {
            Result<ResourceStack> stack = context.stack(output);
            if (stack.isErr()) return invalid("outputs: " + stack.error().message());
            builder.output(stack.value());
        }
        for (DataValue cost : list(map, "cost-per-tick")) {
            Result<ResourceStack> stack = context.stack(cost);
            if (stack.isErr()) return invalid("cost-per-tick: " + stack.error().message());
            builder.costPerTick(stack.value());
        }
        return Result.ok(builder.build());
    }

    // ------------------------------------------------------------------------------------------
    // Shared vocabulary
    // ------------------------------------------------------------------------------------------

    /** The {@link DefinitionContext} given to behavior parsers. */
    private record Context(RegistryAccess registries, ItemResolver items) implements DefinitionContext {

        @Override
        public @NotNull Result<ResourcePredicate> predicate(@NotNull DataValue spec) {
            if (!(spec instanceof DataMap map) || map.size() != 1) {
                return invalid("a filter is a map with one key: type, types, category, tag, item, any, all, any-of or not");
            }
            String kind = map.keys().iterator().next();
            DataValue value = map.get(kind).orElseThrow();
            return switch (kind) {
                case "any" -> Result.ok(ResourcePredicate.any());
                case "type" -> key(value).flatMap(id -> lookup(this.registries, Registries.RESOURCE_TYPE, id)).map(ResourcePredicate::type);
                case "types" -> keyList(value).flatMap(ids -> {
                    List<ResourceType> types = new ArrayList<>();
                    for (Key id : ids) {
                        Result<ResourceType> type = lookup(this.registries, Registries.RESOURCE_TYPE, id);
                        if (type.isErr()) return Result.err(type.error());
                        types.add(type.value());
                    }
                    return Result.ok(ResourcePredicate.types(types.toArray(ResourceType[]::new)));
                });
                case "category" -> key(value).flatMap(id -> lookup(this.registries, Registries.RESOURCE_CATEGORY, id))
                        .map(ResourcePredicate::category);
                case "tag" -> key(value).map(ResourcePredicate::tag);
                case "item" -> key(value).map(Items::ofItem);
                case "item-tag" -> key(value).map(Items::inTag);
                case "all", "any-of" -> {
                    if (!(value instanceof DataList list) || list.values().isEmpty()) yield invalid(kind + " needs a list of filters");
                    ResourcePredicate combined = null;
                    for (DataValue element : list.values()) {
                        Result<ResourcePredicate> part = predicate(element);
                        if (part.isErr()) yield part;
                        combined = combined == null ? part.value()
                                : kind.equals("all") ? combined.and(part.value()) : combined.or(part.value());
                    }
                    yield Result.ok(combined);
                }
                case "not" -> predicate(value).map(ResourcePredicate::negate);
                default -> invalid("unknown filter '" + kind + "'");
            };
        }

        @Override
        public @NotNull Result<ResourceMatcher> matcher(@NotNull DataValue spec) {
            if (!(spec instanceof DataMap map)) return invalid("expected {<filter>, amount: n}");
            long amount = map.getLong("amount").toOptional().orElse(1L);
            return predicate(map.without("amount")).map(predicate -> ResourceMatcher.of(predicate, amount));
        }

        @Override
        public @NotNull Result<ResourceStack> stack(@NotNull DataValue spec) {
            if (!(spec instanceof DataMap map)) return invalid("expected {type: <key>, amount: n} or {item: <id>, amount: n}");
            long amount = map.getLong("amount").toOptional().orElse(1L);
            if (amount < 1) return invalid("amount must be positive");
            if (map.has("item")) {
                return map.getString("item").flatMap(Codecs::parseKey).flatMap(this.items::item).map(variant -> variant.stack(amount));
            }
            return map.getString("type").flatMap(Codecs::parseKey)
                    .flatMap(id -> lookup(this.registries, Registries.RESOURCE_TYPE, id))
                    .map(type -> type.stack(amount));
        }

        @Override
        @SuppressWarnings("unchecked")
        public @NotNull Result<RecipeType<ProcessingRecipe>> processingRecipeType(@NotNull DataValue spec) {
            return key(spec).flatMap(id -> lookup(this.registries, Registries.RECIPE_TYPE, id)).flatMap(type ->
                    type.recipeClass() == ProcessingRecipe.class
                            ? Result.ok((RecipeType<ProcessingRecipe>) type)
                            : invalid(type.key().asString() + " is not a processing recipe type"));
        }
    }

    private interface Entry {
        void accept(Key key, DataValue spec, String where);
    }

    private static void entries(DefinitionFile file, String section, Consumer<Diagnostic> report, Entry entry) {
        DataValue raw = file.content().get(section).orElse(null);
        if (raw == null) return;
        if (!(raw instanceof DataMap map)) {
            skip(report, file.name() + " > " + section, "must be a map of <key>: <definition>");
            return;
        }
        map.entries().forEach((name, spec) -> {
            String where = file.name() + " > " + section + "." + name;
            Result<Key> key = Codecs.parseKey(name);
            if (key.isErr() || !name.contains(":")) {
                skip(report, where, "'" + name + "' is not a namespaced key (namespace:value)");
                return;
            }
            try {
                entry.accept(key.value(), spec, where);
            } catch (RuntimeException e) {
                // e.g. a duplicate key: skip this entry only.
                skip(report, where, e.getMessage());
            }
        });
    }

    private static List<Key> keys(DefinitionFile file, String section, Consumer<Diagnostic> report) {
        DataValue raw = file.content().get(section).orElse(null);
        if (raw == null) return List.of();
        Result<List<Key>> keys = keyList(raw);
        if (keys.isErr()) {
            skip(report, file.name() + " > " + section, keys.error().message());
            return List.of();
        }
        return keys.value();
    }

    private static List<DataValue> list(DataMap map, String key) {
        return map.get(key).map(value -> value instanceof DataList list ? list.values() : List.of(value)).orElse(List.of());
    }

    private static Result<Key> key(DataValue value) {
        return value instanceof DataString string ? Codecs.parseKey(string.value()) : invalid("expected a namespaced key");
    }

    private static Result<List<Key>> keyList(DataValue value) {
        return Codecs.list(Codecs.KEY).decode(value);
    }

    private static <T extends Keyed> Result<T> lookup(RegistryAccess registries,
                                                                            RegistryType<T> type, Key id) {
        return registries.registry(type).get(id).map(Result::ok)
                .orElseGet(() -> Result.err(new MachineError.MissingResource(type.key(), id)));
    }

    private static DataMap asMap(DataValue spec, String where, Consumer<Diagnostic> report) {
        if (spec instanceof DataMap map) return map;
        skip(report, where, "must be a map");
        return null;
    }

    private static <T> Result<T> invalid(String message) {
        return Result.err(MachineError.of(DiagnosticCodes.DEFINITION_SKIPPED, message));
    }

    private static void skip(Consumer<Diagnostic> report, String where, String message) {
        report.accept(Diagnostic.warning(DiagnosticCodes.DEFINITION_SKIPPED, "Skipped: " + message)
                .source(ADDON).location(where).build());
    }
}
