package fr.robie.machineengine.api.lifecycle;

/**
 * Engine lifecycle (ARCHITECTURE.md §8.2). World and machine loading are platform phases that
 * happen while the engine is {@link #RUNTIME}.
 */
public enum LifecyclePhase {
    BOOTSTRAP,
    REGISTRY_CREATION,
    DEPENDENCY_RESOLUTION,
    REGISTRATION,
    VALIDATION,
    FROZEN,
    RUNTIME,
    SHUTDOWN
}
