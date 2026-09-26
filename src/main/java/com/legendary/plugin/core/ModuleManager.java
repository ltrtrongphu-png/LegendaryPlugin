package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Central registry that owns the lifecycle of every {@link Module}.
 * Modules are sorted by {@link Module#priority()} (ascending) before
 * enable; disable happens in reverse order so dependent modules shut
 * down cleanly. Dependencies declared via {@link Module#dependsOn()}
 * are validated at registration time.
 */
public final class ModuleManager {

    private final LegendaryPlugin plugin;
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final Map<String, Boolean> runtimeState = new LinkedHashMap<>();
    private final List<String> enableOrder = new ArrayList<>();

    public ModuleManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(Module module) {
        for (String dep : module.dependsOn()) {
            if (!modules.containsKey(dep)) {
                plugin.getLogger().warning("Module '" + module.id()
                    + "' depends on '" + dep + "' which is not registered yet."
                    + " It will fail to enable if the dependency is missing.");
            }
        }
        modules.put(module.id().toLowerCase(), module);
    }

    /** Enables every registered module whose config.yml switch (modules.<id>) is true, in priority order. */
    public void enableAll() {
        List<Module> sorted = new ArrayList<>(modules.values());
        sorted.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        enableOrder.clear();

        for (Module module : sorted) {
            boolean wanted = plugin.getConfig().getBoolean("modules." + module.id(), true);
            runtimeState.put(module.id().toLowerCase(), wanted);
            if (!wanted) {
                plugin.getLogger().info("Module '" + module.id() + "' is disabled in config.yml - skipping.");
                continue;
            }
            if (!checkDependencies(module)) {
                plugin.getLogger().severe("Module '" + module.id()
                    + "' has unmet dependencies - skipping enable.");
                continue;
            }
            safe(module::onEnable, module, "enable");
            enableOrder.add(module.id().toLowerCase());
        }
    }

    /** Disables every module in reverse enable order. Safe to call multiple times. */
    public void disableAll() {
        List<String> reversed = new ArrayList<>(enableOrder);
        Collections.reverse(reversed);
        for (String id : reversed) {
            Module module = modules.get(id);
            if (module != null) {
                safe(module::onDisable, module, "disable");
            }
        }
        enableOrder.clear();
    }

    public boolean enable(String id) {
        Module m = modules.get(id.toLowerCase());
        if (m == null) return false;
        if (m.isEnabled()) return true;
        if (!checkDependencies(m)) {
            plugin.getLogger().severe("Cannot enable '" + id + "': unmet dependencies.");
            return false;
        }
        safe(m::onEnable, m, "enable");
        runtimeState.put(id.toLowerCase(), true);
        enableOrder.add(id.toLowerCase());
        return true;
    }

    public boolean disable(String id) {
        Module m = modules.get(id.toLowerCase());
        if (m == null) return false;
        safe(m::onDisable, m, "disable");
        runtimeState.put(id.toLowerCase(), false);
        enableOrder.remove(id.toLowerCase());
        return true;
    }

    public boolean reload(String id) {
        Module m = modules.get(id.toLowerCase());
        if (m == null) return false;
        safe(m::reload, m, "reload");
        return true;
    }

    public Optional<Module> get(String id) {
        return Optional.ofNullable(modules.get(id.toLowerCase()));
    }

    public Map<String, Module> all() {
        return modules;
    }

    /** Returns the order modules were enabled in (for diagnostics and shutdown sequencing). */
    public List<String> getEnableOrder() {
        return Collections.unmodifiableList(enableOrder);
    }

    private boolean checkDependencies(Module module) {
        for (String dep : module.dependsOn()) {
            Module depModule = modules.get(dep.toLowerCase());
            if (depModule == null || !depModule.isEnabled()) {
                return false;
            }
        }
        return true;
    }

    private void safe(Runnable action, Module module, String verb) {
        try {
            action.run();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,
                "Failed to " + verb + " module '" + module.id() + "': " + ex.getMessage(), ex);
        }
    }
}
