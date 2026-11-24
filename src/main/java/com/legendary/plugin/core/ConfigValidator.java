package com.legendary.plugin.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Validates user configuration against bundled defaults without mutating it. */
public final class ConfigValidator {

    public enum Severity { ERROR, WARNING }

    public record Issue(Severity severity, String path, String message, String moduleId) {}

    public record Result(List<Issue> issues) {
        public Result {
            issues = List.copyOf(issues);
        }

        public boolean isValid() {
            return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR);
        }
    }

    private ConfigValidator() {}

    public static Result validate(FileConfiguration config, FileConfiguration defaults) {
        return validate(config, defaults, ignored -> true);
    }

    public static Result validate(FileConfiguration config, FileConfiguration defaults,
                                  Predicate<String> pluginAvailable) {
        List<Issue> issues = new ArrayList<>();
        if (config.getInt("config-version", 0) > ConfigMigrator.CURRENT_VERSION) {
            issues.add(error("config-version", "Config was created by a newer plugin version.", null));
        } else if (!config.isInt("config-version")) {
            issues.add(error("config-version", "Expected an integer config version.", null));
        }

        for (String path : config.getKeys(true)) {
            if (config.isConfigurationSection(path)) continue;
            Object expected = defaults.get(path);
            Object actual = config.get(path);
            if (expected == null && !defaults.contains(path)) {
                issues.add(warning(path, "Unknown configuration key."));
                continue;
            }
            if (expected != null && !matchesType(actual, expected)) {
                issues.add(error(path, "Expected " + expected.getClass().getSimpleName()
                    + " but found " + typeName(actual) + ".", moduleFor(path)));
            }
        }

        checkDouble(config, issues, "verification.required-distance", 0.0, 4.0);
        checkInteger(config, issues, "verification.timeout-seconds", 5, 3600);
        checkInteger(config, issues, "anti-ddos.max-global-logins-per-second", 1, 100_000);
        checkInteger(config, issues, "anti-ddos.max-per-ip-per-minute", 1, 10_000);
        checkInteger(config, issues, "anti-ddos.max-concurrent-per-ip", 1, 1000);
        checkInteger(config, issues, "anticheat.flag-cooldown-ms", 0, 60_000);

        boolean hasBanThreshold = config.getMapList("anticheat.escalation.thresholds").stream()
            .map(entry -> entry.get("action"))
            .anyMatch(action -> action instanceof String value && value.equalsIgnoreCase("ban"));
        if (hasBanThreshold
            && !config.getBoolean("anticheat.safe-mode", true)
            && !config.getBoolean("anticheat.ban.dry-run", true)
            && config.getBoolean("anticheat.ban.require-plugin", false)
            && !pluginNameConfigured(config, pluginAvailable)) {
            issues.add(error("anticheat.ban.plugin-name",
                "Automatic bans are enabled and require-plugin is true, but no ban plugin is configured.",
                "anticheat"));
        }
        return new Result(issues);
    }

    private static boolean pluginNameConfigured(FileConfiguration config, Predicate<String> pluginAvailable) {
        String name = config.getString("anticheat.ban.plugin-name", "");
        return name != null && !name.isBlank() && pluginAvailable.test(name);
    }

    private static void checkDouble(FileConfiguration config, List<Issue> issues, String path,
                                    double minimumExclusive, double maximumInclusive) {
        if (!config.isSet(path)) return;
        Object value = config.get(path);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
            || number.doubleValue() <= minimumExclusive || number.doubleValue() > maximumInclusive) {
            issues.add(error(path, "Value must be > " + minimumExclusive + " and <= " + maximumInclusive + ".",
                moduleFor(path)));
        }
    }

    private static void checkInteger(FileConfiguration config, List<Issue> issues, String path, int min, int max) {
        if (!config.isSet(path)) return;
        Object value = config.get(path);
        if (!(value instanceof Number number)
            || number.doubleValue() != Math.rint(number.doubleValue())
            || number.doubleValue() < min || number.doubleValue() > max) {
            issues.add(error(path, "Value must be an integer in [" + min + ", " + max + "].", moduleFor(path)));
        }
    }

    private static boolean matchesType(Object actual, Object expected) {
        if (actual == null) return true;
        if (expected instanceof Number) return actual instanceof Number;
        if (expected instanceof List<?>) return actual instanceof List<?>;
        if (expected instanceof Map<?, ?> || expected instanceof ConfigurationSection) {
            return actual instanceof Map<?, ?> || actual instanceof ConfigurationSection;
        }
        return expected.getClass().isInstance(actual);
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static String moduleFor(String path) {
        if (path.startsWith("modules.")) {
            String id = path.substring("modules.".length()).split("\\.", 2)[0];
            return switch (id) {
                case "anti-reverse-engineering", "anti-ddos", "anti-chunk-ban",
                     "anti-infinite-water", "anti-afk-bot", "esp", "optimization",
                     "anticheat", "verification", "replay" -> id;
                default -> null;
            };
        }
        if (path.startsWith("anti-reverse-engineering.")) return "anti-reverse-engineering";
        if (path.startsWith("anti-chunk-ban.")) return "anti-chunk-ban";
        if (path.startsWith("anti-infinite-water.")) return "anti-infinite-water";
        if (path.startsWith("anti-afk-bot.")) return "anti-afk-bot";
        if (path.startsWith("verification.")) return "verification";
        if (path.startsWith("anticheat.")) return "anticheat";
        if (path.startsWith("anti-ddos.")) return "anti-ddos";
        if (path.startsWith("esp.")) return "esp";
        if (path.startsWith("optimization.")) return "optimization";
        if (path.startsWith("replay.")) return "replay";
        return null;
    }

    private static Issue error(String path, String message, String module) {
        return new Issue(Severity.ERROR, path, message, module);
    }

    private static Issue warning(String path, String message) {
        return new Issue(Severity.WARNING, path, message, null);
    }
}
