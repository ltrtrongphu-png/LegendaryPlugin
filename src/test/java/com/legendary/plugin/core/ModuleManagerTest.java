package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModuleManagerTest {

    @Test
    void failedEnableIsNotRecordedAsEnabledAndIsMarkedFailed() {
        ModuleManager manager = manager();
        TestModule module = new TestModule("broken");
        module.failEnable = true;
        manager.register(module);

        assertFalse(manager.enable("broken"));
        assertEquals(ModuleManager.State.FAILED, manager.state("broken"));
        assertTrue(manager.getEnableOrder().isEmpty());
    }

    @Test
    void enableIsIdempotentAndDependencyCannotBeDisabledFirst() {
        ModuleManager manager = manager();
        TestModule replay = new TestModule("replay");
        TestModule anticheat = new TestModule("anticheat", "replay");
        manager.register(replay);
        manager.register(anticheat);

        assertTrue(manager.enable("replay"));
        assertTrue(manager.enable("anticheat"));
        assertTrue(manager.enable("anticheat"));
        assertEquals(1, anticheat.enableCalls);
        assertFalse(manager.disable("replay"));
        assertTrue(manager.disable("anticheat"));
        assertTrue(manager.disable("replay"));
        assertEquals(List.of(), manager.getEnableOrder());
    }

    @Test
    void moduleRegistryIsReadOnlySnapshotAndDuplicateIdsAreRejected() {
        ModuleManager manager = manager();
        manager.register(new TestModule("replay"));

        assertThrows(UnsupportedOperationException.class,
            () -> manager.all().put("other", new TestModule("other")));
        assertThrows(IllegalArgumentException.class, () -> manager.register(new TestModule("REPLAY")));
    }

    @Test
    void reloadRefreshesConfigurationAndReinitializesModule() {
        LegendaryPlugin plugin = mock(LegendaryPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ModuleManagerTest"));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        when(plugin.validateConfiguration()).thenReturn(new ConfigValidator.Result(List.of()));
        ModuleManager manager = new ModuleManager(plugin);
        TestModule module = new TestModule("replay");
        manager.register(module);
        assertTrue(manager.enable("replay"));

        assertTrue(manager.reload("replay"));
        verify(plugin).reloadConfig();
        assertEquals(2, module.enableCalls);
        assertEquals(ModuleManager.State.ENABLED, manager.state("replay"));
    }

    @Test
    void blockingDependencyDisablesItsEnabledDependents() {
        ModuleManager manager = manager();
        TestModule replay = new TestModule("replay");
        TestModule anticheat = new TestModule("anticheat", "replay");
        manager.register(replay);
        manager.register(anticheat);
        assertTrue(manager.enable("replay"));
        assertTrue(manager.enable("anticheat"));

        manager.block("replay", "bad config");

        assertFalse(replay.isEnabled());
        assertFalse(anticheat.isEnabled());
        assertEquals(ModuleManager.State.BLOCKED, manager.state("replay"));
        assertEquals(ModuleManager.State.DISABLED, manager.state("anticheat"));
    }

    private static ModuleManager manager() {
        LegendaryPlugin plugin = mock(LegendaryPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("ModuleManagerTest"));
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        return new ModuleManager(plugin);
    }

    private static final class TestModule implements Module {
        private final String id;
        private final String[] dependencies;
        private boolean enabled;
        private boolean failEnable;
        private int enableCalls;

        private TestModule(String id, String... dependencies) {
            this.id = id;
            this.dependencies = dependencies;
        }

        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public String[] dependsOn() { return dependencies; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void onEnable() {
            enableCalls++;
            if (failEnable) throw new AssertionError("test failure");
            enabled = true;
        }
        @Override public void onDisable() { enabled = false; }
    }
}
