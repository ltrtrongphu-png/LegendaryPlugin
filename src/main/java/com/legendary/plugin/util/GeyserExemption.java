package com.legendary.plugin.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Detects Bedrock Edition players connected through Geyser+Floodgate.
 * Bedrock movement/combat physics differ enough from Java Edition (no
 * sprint-jump the same way, different hitbox/reach feel via the Bedrock
 * client) that several checks tuned for Java clients produce false
 * positives on Bedrock players unless explicitly exempted.
 *
 * Ported/adapted from the DeepSeek-generated LegendaryPlugin v6.0 dump's
 * utils.GeyserExemption (2026-09-19 merge review) - same UUID-prefix
 * technique Floodgate itself documents, kept dependency-free (no compile
 * dependency on the Floodgate API) so this works whether or not the
 * Floodgate jar happens to be present at build time.
 */
public final class GeyserExemption {

    private static volatile boolean geyserLoaded = false;

    private GeyserExemption() {}

    /** Call once on plugin enable. */
    public static void init() {
        var geyser = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        var floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
        geyserLoaded = geyser != null && floodgate != null;
    }

    public static boolean isBedrock(Player player) {
        if (!geyserLoaded || player == null) return false;
        return isBedrockUUID(player.getUniqueId());
    }

    /** Floodgate mints Bedrock players a UUID under this fixed namespace prefix. */
    public static boolean isBedrockUUID(UUID uuid) {
        return uuid.toString().startsWith("00000000-0000-0000-0009-");
    }

    public static boolean isLoaded() {
        return geyserLoaded;
    }
}
