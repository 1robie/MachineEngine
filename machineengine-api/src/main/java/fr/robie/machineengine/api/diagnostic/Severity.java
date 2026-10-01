package fr.robie.machineengine.api.diagnostic;

public enum Severity {
    /** Informational; never changes behavior. */
    INFO,
    /** Suspicious but usable. */
    WARNING,
    /** Invalid; the offending addon's content is disabled (startup) or the reload is rejected. */
    ERROR
}
