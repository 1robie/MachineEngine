package fr.robie.machineengine.test;

import fr.robie.machineengine.api.addon.AddonId;
import fr.robie.machineengine.api.addon.RegistrationContext;
import fr.robie.machineengine.api.diagnostic.Diagnostic;
import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.lifecycle.LifecyclePhase;
import fr.robie.machineengine.api.registry.RegistrationException;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.RegistryType;
import fr.robie.machineengine.api.resource.ResourceCategory;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.convert.ResourceConverter;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.key.Keyed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static fr.robie.machineengine.test.Fixtures.ENERGY;
import static fr.robie.machineengine.test.Fixtures.EU;
import static fr.robie.machineengine.test.Fixtures.FE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleTest {

    @Test
    void registersAndFreezes() {
        CoreMachineEngine engine = TestEngine.builder().addon("energyplugin", Fixtures::registerEnergy).start();

        assertEquals(LifecyclePhase.RUNTIME, engine.phase());
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        var types = engine.registries().registry(Registries.RESOURCE_TYPE);
        assertSame(FE, types.getOrThrow(Key.key("energyplugin", "fe")));
        assertEquals(new AddonId("energyplugin"), types.owner(FE.key()).orElseThrow());
        assertEquals(List.of(FE, EU), types.values().stream()
                .filter(type -> types.owner(type.key()).orElseThrow().equals(new AddonId("energyplugin"))).toList(),
                "registration order is kept");
        assertEquals(Set.of(new AddonId("energyplugin")), engine.enabledAddons());
    }

    @Test
    void registrationContextIsClosedAfterTheCallback() {
        AtomicReference<RegistrationContext> leaked = new AtomicReference<>();
        TestEngine.builder().addon("leaky", leaked::set).start();

        RegistrationException error = assertThrows(RegistrationException.class,
                () -> leaked.get().resourceCategories().register(ENERGY));
        assertEquals(DiagnosticCodes.REGISTRY_FROZEN, error.diagnostic().code());
    }

    @Test
    void dependentsRegisterAfterTheirDependenciesAndSeeTheirContent() {
        List<String> order = new ArrayList<>();
        CoreMachineEngine engine = TestEngine.builder()
                // Submitted first, but requires energyplugin.
                .addon("converterplugin", "1.0.0", builder -> builder.requires("energyplugin", "[1.0,2.0)").register(ctx -> {
                    order.add("converterplugin");
                    ResourceType fe = ctx.registries().registry(Registries.RESOURCE_TYPE).getOrThrow(Key.key("energyplugin", "fe"));
                    ResourceType eu = ctx.registries().registry(Registries.RESOURCE_TYPE).getOrThrow(Key.key("energyplugin", "eu"));
                    ctx.converters().register(ResourceConverter.ratio(Key.key("converterplugin", "fe_to_eu"), fe, 4, eu, 1));
                }))
                .addon("energyplugin", "1.2.0", builder -> builder.register(ctx -> {
                    order.add("energyplugin");
                    Fixtures.registerEnergy(ctx);
                }))
                .start();

        assertEquals(List.of("energyplugin", "converterplugin"), order);
        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertEquals(1, engine.converters().find(FE, EU).size());
    }

    @Test
    void missingAndIncompatibleDependenciesDisableTheAddon() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("energyplugin", "1.0.0", builder -> builder.register(Fixtures::registerEnergy))
                .addon("needsmissing", "1.0.0", builder -> builder.requires("ghost", "1.0").register(ctx -> { }))
                .addon("needsnewer", "1.0.0", builder -> builder.requires("energyplugin", "[2.0,)").register(ctx -> { }))
                .addon("optional", "1.0.0", builder -> builder.after("ghost").register(ctx -> { }))
                .start();

        assertEquals(Set.of(new AddonId("energyplugin"), new AddonId("optional")), engine.enabledAddons());
        assertHasError(engine, DiagnosticCodes.MISSING_DEPENDENCY, "needsmissing");
        assertHasError(engine, DiagnosticCodes.INCOMPATIBLE_DEPENDENCY, "needsnewer");
    }

    @Test
    void incompatibleApiVersionDisablesTheAddon() {
        CoreMachineEngine engine = TestEngine.builder()
                .apiVersion("0.1.0-SNAPSHOT")
                .addon("modern", "1.0.0", builder -> builder.requiresApi("[1.0,2.0)").register(ctx -> { }))
                .addon("current", "1.0.0", builder -> builder.requiresApi("[0.1,1.0)").register(ctx -> { }))
                .start();

        assertEquals(Set.of(new AddonId("current")), engine.enabledAddons());
        assertHasError(engine, DiagnosticCodes.INCOMPATIBLE_API, "modern");
    }

    @Test
    void dependencyCyclesAreRejectedTogetherWithTheirDependents() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("a", "1.0.0", builder -> builder.requires("b", "1.0").register(ctx -> { }))
                .addon("b", "1.0.0", builder -> builder.requires("a", "1.0").register(ctx -> { }))
                .addon("c", "1.0.0", builder -> builder.requires("a", "1.0").register(ctx -> { }))
                .addon("d", "1.0.0", builder -> builder.register(ctx -> { }))
                .start();

        assertEquals(Set.of(new AddonId("d")), engine.enabledAddons());
        assertHasError(engine, DiagnosticCodes.DEPENDENCY_CYCLE, "a");
        assertHasError(engine, DiagnosticCodes.DEPENDENCY_CYCLE, "b");
        assertTrue(engine.disabledAddons().contains(new AddonId("c")));
    }

    @Test
    void duplicateKeysNameBothOwnersAndDisableTheSecondAddon() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("energyplugin", Fixtures::registerEnergy)
                .addon("copycat", "1.0.0", builder -> builder.after("energyplugin").register(ctx ->
                        ctx.resourceCategories().register(ResourceCategory.builder(ENERGY.key()).build())))
                .start();

        Diagnostic duplicate = assertHasError(engine, DiagnosticCodes.DUPLICATE_KEY, "copycat");
        assertEquals("energyplugin", duplicate.context().get("first"));
        assertEquals("copycat", duplicate.context().get("second"));
        assertSame(ENERGY, engine.registries().registry(Registries.RESOURCE_CATEGORY).getOrThrow(ENERGY.key()));
        assertEquals(Set.of(new AddonId("copycat")), engine.disabledAddons());
    }

    @Test
    void aThrowingCallbackDisablesOnlyThatAddonAndItsDependents() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("energyplugin", Fixtures::registerEnergy)
                .addon("broken", "1.0.0", builder -> builder.register(ctx -> {
                    ctx.resourceTypes().register(ResourceType.of(Key.key("broken", "half"), ENERGY));
                    throw new IllegalStateException("boom");
                }))
                .addon("needsbroken", "1.0.0", builder -> builder.requires("broken", "1.0").register(ctx -> { }))
                .start();

        assertHasError(engine, DiagnosticCodes.REGISTRATION_FAILED, "broken");
        assertEquals(Set.of(new AddonId("energyplugin")), engine.enabledAddons());
        assertFalse(engine.registries().registry(Registries.RESOURCE_TYPE).contains(Key.key("broken", "half")),
                "partial registrations of a failed addon are removed");
    }

    @Test
    void validationDisablesAddonsReferencingUnregisteredContentAndTheirDependents() {
        ResourceCategory unregistered = ResourceCategory.builder(Key.key("sloppy", "forgotten")).build();
        CoreMachineEngine engine = TestEngine.builder()
                .addon("sloppy", "1.0.0", builder -> builder.register(ctx ->
                        ctx.resourceTypes().register(ResourceType.of(Key.key("sloppy", "thing"), unregistered))))
                .addon("downstream", "1.0.0", builder -> builder.requires("sloppy", "1.0").register(ctx -> { }))
                .addon("unrelated", Fixtures::registerEnergy)
                .start();

        Diagnostic diagnostic = assertHasError(engine, DiagnosticCodes.UNREGISTERED_REFERENCE, "sloppy");
        assertEquals(Key.key("sloppy", "thing"), diagnostic.subject());
        assertHasError(engine, DiagnosticCodes.DEPENDENCY_DISABLED, "downstream");
        assertEquals(Set.of(new AddonId("unrelated")), engine.enabledAddons());
        assertFalse(engine.registries().registry(Registries.RESOURCE_TYPE).contains(Key.key("sloppy", "thing")));
    }

    @Test
    void validationReportsEveryProblemInOnePass() {
        ResourceCategory ghost = ResourceCategory.builder(Key.key("sloppy", "ghost")).build();
        CoreMachineEngine engine = TestEngine.builder()
                .addon("sloppy", ctx -> {
                    ctx.resourceTypes().register(ResourceType.of(Key.key("sloppy", "one"), ghost));
                    ctx.resourceTypes().register(ResourceType.of(Key.key("sloppy", "two"), ghost));
                })
                .start();

        long unregistered = engine.startupReport().errors().stream()
                .filter(d -> d.code().equals(DiagnosticCodes.UNREGISTERED_REFERENCE)).count();
        assertEquals(2, unregistered);
    }

    @Test
    void reservedNamespacesAreRejected() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("squatter", ctx -> ctx.resourceCategories().register(ResourceCategory.builder(Key.key("machineengine", "squat")).build()))
                .start();

        assertHasError(engine, DiagnosticCodes.RESERVED_NAMESPACE, "squatter");
    }

    @Test
    void addonValidatorsCanRejectTheirOwnContentAndExceptionsAreContained() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("strict", ctx -> {
                    Fixtures.registerEnergy(ctx);
                    ctx.validator(validation -> validation.report(Diagnostic.error(Key.key("strict", "nope"), "rejected").build()));
                })
                .addon("crashy", ctx -> ctx.validator(validation -> {
                    throw new IllegalStateException("validator bug");
                }))
                .start();

        assertHasError(engine, Key.key("strict", "nope"), "strict");
        assertHasError(engine, DiagnosticCodes.VALIDATOR_FAILED, "crashy");
        assertTrue(engine.enabledAddons().isEmpty());
    }

    @Test
    void addonsCanCreateTheirOwnRegistries() {
        RegistryType<Machine> machines = RegistryType.createStatic(Key.key("machinesplugin", "machine"), Machine.class);
        CoreMachineEngine engine = TestEngine.builder()
                .addon("machinesplugin", ctx -> {
                    ctx.createRegistry(machines);
                    ctx.registry(machines).register(new Machine(Key.key("machinesplugin", "furnace")));
                })
                .addon("extension", "1.0.0", builder -> builder.requires("machinesplugin", "1.0").register(ctx ->
                        ctx.registry(machines).register(new Machine(Key.key("extension", "crusher")))))
                .start();

        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertEquals(2, engine.registries().registry(machines).size());
    }

    @Test
    void wrongRegistryKindIsAnError() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("confused", ctx -> ctx.registry(Registries.RESOURCE_TAG))
                .start();

        assertHasError(engine, DiagnosticCodes.WRONG_REGISTRY_KIND, "confused");
    }

    record Machine(Key key) implements Keyed {
    }

    static Diagnostic assertHasError(CoreMachineEngine engine, Key code, String addon) {
        return engine.startupReport().errors().stream()
                .filter(d -> d.code().equals(code) && new AddonId(addon).equals(d.source()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected error " + code.asString() + " for " + addon
                        + " in:" + System.lineSeparator() + engine.startupReport().format()));
    }
}
