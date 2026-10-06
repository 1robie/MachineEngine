package fr.robie.machineengine.test;

import fr.robie.machineengine.api.diagnostic.DiagnosticCodes;
import fr.robie.machineengine.api.diagnostic.ValidationReport;
import fr.robie.machineengine.api.registry.Registries;
import fr.robie.machineengine.api.registry.ReloadableRegistry;
import fr.robie.machineengine.api.resource.ResourceType;
import fr.robie.machineengine.api.resource.tag.ResourceTagContribution;
import fr.robie.machineengine.core.lifecycle.CoreMachineEngine;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static fr.robie.machineengine.test.Fixtures.FLUID;
import static fr.robie.machineengine.test.Fixtures.OIL;
import static fr.robie.machineengine.test.Fixtures.STEAM;
import static fr.robie.machineengine.test.Fixtures.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadTest {
    private static final Key COOLANT = Key.key("mymod", "coolant");

    @Test
    void tagsAreMergedFromSeveralContributors() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("fluids", ctx -> {
                    Fixtures.registerFluids(ctx);
                    ctx.resourceTags(reload -> reload.register(ResourceTagContribution.of(Key.key("fluids", "coolant_water"), COOLANT, WATER)));
                })
                .addon("extra", "1.0.0", builder -> builder.requires("fluids", "1.0").register(ctx ->
                        ctx.resourceTags(reload -> reload.register(ResourceTagContribution.of(Key.key("extra", "coolant_steam"), COOLANT, STEAM)))))
                .start();

        assertFalse(engine.startupReport().hasErrors(), engine.startupReport().format());
        assertEquals(Set.of(WATER, STEAM), engine.tags().members(COOLANT));
        assertFalse(engine.tags().contains(COOLANT, OIL));
    }

    @Test
    void reloadSwapsTheSnapshotAndBumpsTheGeneration() {
        AtomicReference<ResourceType> coolant = new AtomicReference<>(WATER);
        CoreMachineEngine engine = TestEngine.builder()
                .addon("fluids", ctx -> {
                    Fixtures.registerFluids(ctx);
                    ctx.resourceTags(reload -> reload.register(ResourceTagContribution.of(Key.key("fluids", "coolant"), COOLANT, coolant.get())));
                })
                .start();
        ReloadableRegistry<?> tags = (ReloadableRegistry<?>) engine.registries().registry(Registries.RESOURCE_TAG);
        long generation = tags.generation();

        coolant.set(OIL);
        ValidationReport report = engine.reload(Registries.RESOURCE_TAG);

        assertFalse(report.hasErrors(), report.format());
        assertEquals(generation + 1, tags.generation());
        assertEquals(Set.of(OIL), engine.tags().members(COOLANT));
    }

    @Test
    void anInvalidReloadIsRejectedAndThePreviousSnapshotStays() {
        ResourceType unregistered = ResourceType.of(Key.key("fluids", "ghost"), FLUID);
        AtomicReference<ResourceType> coolant = new AtomicReference<>(WATER);
        CoreMachineEngine engine = TestEngine.builder()
                .addon("fluids", ctx -> {
                    Fixtures.registerFluids(ctx);
                    ctx.resourceTags(reload -> reload.register(ResourceTagContribution.of(Key.key("fluids", "coolant"), COOLANT, coolant.get())));
                })
                .start();
        ReloadableRegistry<?> tags = (ReloadableRegistry<?>) engine.registries().registry(Registries.RESOURCE_TAG);
        long generation = tags.generation();

        coolant.set(unregistered);
        ValidationReport report = engine.reload(Registries.RESOURCE_TAG);

        assertTrue(report.hasErrors());
        assertTrue(report.errors().stream().anyMatch(d -> d.code().equals(DiagnosticCodes.UNREGISTERED_REFERENCE)));
        assertTrue(report.errors().stream().anyMatch(d -> d.code().equals(DiagnosticCodes.RELOAD_FAILED)));
        assertEquals(generation, tags.generation());
        assertEquals(Set.of(WATER), engine.tags().members(COOLANT));
    }

    @Test
    void atStartupAFailingProviderIsLeftOutAndTheOthersLoad() {
        CoreMachineEngine engine = TestEngine.builder()
                .addon("fluids", ctx -> {
                    Fixtures.registerFluids(ctx);
                    ctx.resourceTags(reload -> reload.register(ResourceTagContribution.of(Key.key("fluids", "coolant"), COOLANT, WATER)));
                })
                .addon("buggy", "1.0.0", builder -> builder.requires("fluids", "1.0").register(ctx -> ctx.resourceTags(reload -> {
                    reload.register(ResourceTagContribution.of(Key.key("buggy", "coolant"), COOLANT, OIL));
                    throw new IllegalStateException("provider bug");
                })))
                .start();

        LifecycleTest.assertHasError(engine, DiagnosticCodes.RELOAD_FAILED, "buggy");
        assertEquals(Set.of(WATER), engine.tags().members(COOLANT), "the failing provider's partial entries are discarded");
    }

    @Test
    void onlyReloadableRegistriesCanBeReloaded() {
        CoreMachineEngine engine = TestEngine.builder().start();
        assertThrows(IllegalArgumentException.class, () -> engine.reload(Registries.RESOURCE_TYPE));
    }
}
