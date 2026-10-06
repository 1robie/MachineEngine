package fr.robie.machineengine.api.diagnostic;

import fr.robie.machineengine.api.MachineEngineKeys;
import net.kyori.adventure.key.Key;

/** Codes of the diagnostics MachineEngine itself reports. */
public final class DiagnosticCodes {
    public static final Key DUPLICATE_KEY = MachineEngineKeys.key("duplicate_key");
    public static final Key REGISTRY_FROZEN = MachineEngineKeys.key("registry_frozen");
    public static final Key UNKNOWN_REGISTRY = MachineEngineKeys.key("unknown_registry");
    public static final Key WRONG_REGISTRY_KIND = MachineEngineKeys.key("wrong_registry_kind");
    public static final Key WRONG_VALUE_TYPE = MachineEngineKeys.key("wrong_value_type");
    public static final Key RESERVED_NAMESPACE = MachineEngineKeys.key("reserved_namespace");
    public static final Key INVALID_ALIAS = MachineEngineKeys.key("invalid_alias");
    public static final Key REGISTRATION_FAILED = MachineEngineKeys.key("registration_failed");
    public static final Key DUPLICATE_ADDON = MachineEngineKeys.key("duplicate_addon");
    public static final Key MISSING_DEPENDENCY = MachineEngineKeys.key("missing_dependency");
    public static final Key INCOMPATIBLE_DEPENDENCY = MachineEngineKeys.key("incompatible_dependency");
    public static final Key INCOMPATIBLE_API = MachineEngineKeys.key("incompatible_api");
    public static final Key DEPENDENCY_CYCLE = MachineEngineKeys.key("dependency_cycle");
    public static final Key DEPENDENCY_DISABLED = MachineEngineKeys.key("dependency_disabled");
    public static final Key UNREGISTERED_REFERENCE = MachineEngineKeys.key("unregistered_reference");
    public static final Key INVALID_DEFINITION = MachineEngineKeys.key("invalid_definition");
    public static final Key INVALID_CODEC = MachineEngineKeys.key("invalid_codec");
    public static final Key VALIDATOR_FAILED = MachineEngineKeys.key("validator_failed");
    public static final Key RELOAD_FAILED = MachineEngineKeys.key("reload_failed");
    /** A data-driven definition entry was invalid and skipped; the rest loaded. */
    public static final Key DEFINITION_SKIPPED = MachineEngineKeys.key("definition_skipped");

    private DiagnosticCodes() {
    }
}
