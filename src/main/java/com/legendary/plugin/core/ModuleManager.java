package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * Central registry that owns the lifecycle of every {@link Module}.
 */
public final class ModuleManager {

    public enum State { REGISTERED, ENABLED, DISABLED, FAILED, BLOCKED }

    private final LegendaryPlugin plugin;
    private final Map<String, Module> modules = new ConcurrentHashMap<>();
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, String> blockedReasons = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> enableOrder = new CopyOnWriteArrayList<>();

    public ModuleManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void register(Module module) {
        String id = normalize(module.id());
        if (modules.putIfAbsent(id, module) != null) {
            throw new IllegalArgumentException("Duplicate module id: " + module.id());
        }
        states.put(id, State.REGISTERED);
        for (String dep : module.dependsOn()) {
            if (!modules.containsKey(normalize(dep))) {
                plugin.getLogger().warning("Module '" + module.id()
                    + "' depends on '" + dep + "' which is not registered yet.");
            }
        }
    }

    /** Prevents a module with invalid or unsafe configuration from starting. */
    public synchronized void block(String id, String reason) {
        String key = normalize(id);
        if (!modules.containsKey(key)) return;
        blockedReasons.put(key, reason);
        disableDependents(key, new java.util.HashSet<>());
        Module module = modules.get(key);
        if (module.isEnabled() && !disable(key)) {
            states.put(key, State.FAILED);
        } else {
            states.put(key, State.BLOCKED);
        }
    }

    private void disableDependents(String id, java.util.Set<String> visited) {
        if (!visited.add(id)) return;
        for (Module candidate : modules.values()) {
            String candidateId = normalize(candidate.id());
            if (!candidate.isEnabled()) continue;
            boolean dependsOnTarget = java.util.Arrays.stream(candidate.dependsOn())
                .map(ModuleManager::normalize).anyMatch(id::equals);
            if (dependsOnTarget) {
                disableDependents(candidateId, visited);
                disable(candidateId);
            }
        }
    }

    public void clearBlock(String id) {
        String key = normalize(id);
        blockedReasons.remove(key);
        if (states.get(key) == State.BLOCKED) {
            Module module = modules.get(key);
            states.put(key, module != null && module.isEnabled() ? State.ENABLED : State.DISABLED);
        }
    }

    /** Enables configured modules in priority order. */
    public synchronized void enableAll() {
        List<Module> sorted = new ArrayList<>(modules.values());
        sorted.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        for (Module module : sorted) {
            String id = normalize(module.id());
            if (blockedReasons.containsKey(id)) {
                states.put(id, State.BLOCKED);
                plugin.getLogger().severe("Module '" + id + "' was blocked: " + blockedReasons.get(id));
                continue;
            }
            boolean wanted = plugin.getConfig().getBoolean("modules." + id, true);
            if (!wanted) {
                states.put(id, State.DISABLED);
                plugin.getLogger().info("Module '" + id + "' is disabled in config.yml - skipping.");
                continue;
            }
            enable(id);
        }
    }

    /** Disables modules in reverse enable order. Safe to call multiple times. */
    public synchronized void disableAll() {
        List<String> reversed = new ArrayList<>(enableOrder);
        Collections.reverse(reversed);
        for (String id : reversed) {
            Module module = modules.get(id);
            if (module != null && safe(module::onDisable, module, "disable")) {
                states.put(id, State.DISABLED);
            } else if (module != null) {
                states.put(id, State.FAILED);
            }
        }
        enableOrder.clear();
    }

    public synchronized boolean enable(String id) {
        String key = normalize(id);
        Module module = modules.get(key);
        if (module == null) return false;
        if (states.get(key) == State.ENABLED && module.isEnabled()) return true;
        if (blockedReasons.containsKey(key)) {
            states.put(key, State.BLOCKED);
            return false;
        }
        if (!checkDependencies(module)) {
            plugin.getLogger().severe("Cannot enable '" + id + "': unmet dependencies.");
            return false;
        }
        if (!safe(module::onEnable, module, "enable")) {
            states.put(key, State.FAILED);
            safe(module::onDisable, module, "cleanup after failed enable");
            return false;
        }
        states.put(key, State.ENABLED);
        enableOrder.addIfAbsent(key);
        return true;
    }

    public synchronized boolean disable(String id) {
        String key = normalize(id);
        Module module = modules.get(key);
        if (module == null) return false;
        for (Module candidate : modules.values()) {
            if (!candidate.isEnabled()) continue;
            for (String dependency : candidate.dependsOn()) {
                if (key.equals(normalize(dependency))) {
                    plugin.getLogger().warning("Cannot disable '" + key + "': enabled module '"
                        + candidate.id() + "' depends on it.");
                    return false;
                }
            }
        }
        if (!module.isEnabled()) {
            if (states.get(key) != State.BLOCKED && states.get(key) != State.FAILED) {
                states.put(key, State.DISABLED);
            }
            return true;
        }
        boolean disabled = safe(module::onDisable, module, "disable");
        states.put(key, disabled ? State.DISABLED : State.FAILED);
        enableOrder.remove(key);
        return disabled;
    }

    public synchronized boolean reload(String id) {
        String key = normalize(id);
        Module module = modules.get(key);
        if (module == null) return false;
        plugin.reloadConfig();
        ConfigValidator.Result validation = plugin.validateConfiguration();
        if (!validation.isValid() && blockedReasons.containsKey(key)) {
            states.put(key, State.BLOCKED);
            return false;
        }
        if (!checkDependencies(module)) {
            plugin.getLogger().severe("Cannot reload '" + id + "': unmet dependencies.");
            return false;
        }
        boolean reloaded = safe(module::reload, module, "reload");
        states.put(key, reloaded ? (module.isEnabled() ? State.ENABLED : State.DISABLED) : State.FAILED);
        if (reloaded && module.isEnabled()) enableOrder.addIfAbsent(key);
        if (!module.isEnabled()) enableOrder.remove(key);
        return reloaded;
    }

    public Optional<Module> get(String id) {
        return Optional.ofNullable(modules.get(normalize(id)));
    }

    /** Returns a stable, read-only snapshot of registered modules. */
    public Map<String, Module> all() {
        Map<String, Module> snapshot = new LinkedHashMap<>();
        modules.values().stream()
            .sorted(java.util.Comparator.comparingInt(Module::priority).thenComparing(Module::id))
            .forEach(module -> snapshot.put(normalize(module.id()), module));
        return Collections.unmodifiableMap(snapshot);
    }

    public State state(String id) {
        return states.getOrDefault(normalize(id), State.REGISTERED);
    }

    public String blockedReason(String id) {
        return blockedReasons.get(normalize(id));
    }

    public List<String> getEnableOrder() {
        return List.copyOf(enableOrder);
    }

    private boolean checkDependencies(Module module) {
        for (String dep : module.dependsOn()) {
            Module dependency = modules.get(normalize(dep));
            if (dependency == null || !dependency.isEnabled()
                || states.get(normalize(dep)) != State.ENABLED) return false;
        }
        return true;
    }

    private boolean safe(Runnable action, Module module, String verb) {
        try {
            action.run();
            return true;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable error) {
            plugin.getLogger().log(Level.SEVERE,
                "Failed to " + verb + " module '" + module.id() + "': " + error.getMessage(), error);
            return false;
        }
    }

    private static String normalize(String id) {
        return id.toLowerCase(Locale.ROOT);
    }
}
