package com.legendary.plugin;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SafeDefaultsTest {

    @Test
    void destructiveFeaturesAreOptInByDefault() {
        InputStream resource = getClass().getResourceAsStream("/config.yml");
        assertNotNull(resource);
        try (resource; InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(reader);
            assertFalse(config.getBoolean("modules.anti-infinite-water"));
            assertFalse(config.getBoolean("modules.anti-chunk-ban"));
            assertFalse(config.getBoolean("optimization.modules.clear-entities"));
            assertFalse(config.getBoolean("optimization.auto-clear.enabled"));
            assertFalse(config.getBoolean("optimization.modules.hopper-throttle"));
            assertFalse(config.getBoolean("esp.block-obfuscation.enabled"));
        } catch (java.io.IOException exception) {
            throw new AssertionError("Unable to read default configuration", exception);
        }
    }
}
