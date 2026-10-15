package fr.robie.machineengine.api.permission;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public enum Permission {
    BREAK_PLACEHOLDER_MACHINE("breahplaceholdermachine", true)

    ;
    private final String permission;
    private final boolean adminOnly;

    Permission(final @NotNull String permission) {
        this(permission, false);
    }

    Permission(final String permission, final boolean adminOnly) {
        this.permission = Objects.requireNonNull(permission);
        this.adminOnly = adminOnly;
    }

    public @NotNull String permission() {
        return "machineengine." + (this.adminOnly ? "admin." : "") + this.permission;
    }
}
