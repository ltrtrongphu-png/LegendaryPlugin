package com.legendary.plugin.core;

/**
 * Contract every feature module of LegendaryPlugin must implement.
 * Modules are registered with {@link ModuleManager} and can be toggled
 * live through config.yml or the /legendary module command, without
 * requiring a full plugin reload.
 */
public interface Module {

    /** Unique, lowercase, config-path-safe identifier, e.g. "esp", "anticheat". */
    String id();

    /** Human readable name shown in /legendary status and the GUI. */
    String displayName();

    /**
     * Lifecycle phase, used by {@link ModuleManager} to sort enable order.
     * Lower numbers enable first. Override to control boot ordering
     * when a module depends on another being already active.
     */
    default int priority() { return 100; }

    /**
     * IDs of modules that must be enabled before this one. The manager
     * validates these at registration time and refuses to enable a
     * module whose dependency is missing or disabled.
     */
    default String[] dependsOn() { return new String[0]; }

    /**
     * Called once, on the main thread, when the module should start
     * (plugin enable, or live re-enable via command). Implementations
     * must register listeners/tasks here and must be safe to call again
     * after {@link #onDisable()}.
     */
    void onEnable();

    /**
     * Called on the main thread when the module should stop: unregister
     * listeners, cancel tasks, close resources. Must never throw even if
     * onEnable() was never called (idempotent).
     */
    void onDisable();

    /** Re-reads config.yml values without a full enable/disable cycle. */
    default void reload() {
        onDisable();
        onEnable();
    }

    /** Whether this module is currently active. */
    boolean isEnabled();
}
