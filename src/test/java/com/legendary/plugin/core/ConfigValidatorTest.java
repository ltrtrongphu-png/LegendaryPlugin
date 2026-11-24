package com.legendary.plugin.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValidatorTest {

    @Test
    void migrationAddsVersionWithoutRemovingExistingSettings() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("debug", true);

        assertEquals(0, ConfigMigrator.migrate(config));
        assertEquals(ConfigMigrator.CURRENT_VERSION, config.getInt("config-version"));
        assertTrue(config.getBoolean("debug"));
    }

    @Test
    void validationReportsUnknownKeysTypesAndUnsafeValues() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("config-version", ConfigMigrator.CURRENT_VERSION);
        defaults.set("verification.required-distance", 3.0);
        defaults.set("debug", false);

        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", ConfigMigrator.CURRENT_VERSION);
        config.set("verification.required-distance", 4.5);
        config.set("debug", "yes");
        config.set("custom.typo", true);

        var result = ConfigValidator.validate(config, defaults);
        assertFalse(result.isValid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.path().equals("verification.required-distance")
            && issue.severity() == ConfigValidator.Severity.ERROR));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.path().equals("debug")
            && issue.severity() == ConfigValidator.Severity.ERROR));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.path().equals("custom.typo")
            && issue.severity() == ConfigValidator.Severity.WARNING));
    }

    @Test
    void requiredExternalBanPluginBlocksUnsafeAutomatedBanConfiguration() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("config-version", ConfigMigrator.CURRENT_VERSION);
        defaults.set("anticheat.safe-mode", true);
        defaults.set("anticheat.ban.dry-run", true);
        defaults.set("anticheat.ban.require-plugin", false);
        defaults.set("anticheat.ban.plugin-name", "Punishments");
        defaults.set("anticheat.escalation.thresholds", List.of(java.util.Map.of("action", "ban")));

        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", ConfigMigrator.CURRENT_VERSION);
        config.set("anticheat.safe-mode", false);
        config.set("anticheat.ban.dry-run", false);
        config.set("anticheat.ban.require-plugin", true);
        config.set("anticheat.ban.plugin-name", "Punishments");
        config.set("anticheat.escalation.thresholds", List.of(java.util.Map.of("action", "ban")));

        var absentPlugin = ConfigValidator.validate(config, defaults, ignored -> false);
        assertFalse(absentPlugin.isValid());
        assertTrue(absentPlugin.issues().stream().anyMatch(issue ->
            "anticheat".equals(issue.moduleId()) && issue.path().equals("anticheat.ban.plugin-name")));
        assertTrue(ConfigValidator.validate(config, defaults, ignored -> true).isValid());
    }

    @Test
    void bundledConfigurationValidatesAgainstItsDefaults() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream("config.yml");
        assertTrue(stream != null);
        YamlConfiguration bundled;
        try (stream) {
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        var result = ConfigValidator.validate(bundled, bundled);
        assertTrue(result.isValid(), result.issues().toString());
        assertTrue(result.issues().isEmpty(), result.issues().toString());
    }
}
