package fr.robie.machineengine.api.machine;

public enum PortDirection {
    INPUT,
    OUTPUT,
    INPUT_OUTPUT,
    DISABLED;

    public boolean allowsInsertion() {
        return this == INPUT || this == INPUT_OUTPUT;
    }

    public boolean allowsExtraction() {
        return this == OUTPUT || this == INPUT_OUTPUT;
    }
}
