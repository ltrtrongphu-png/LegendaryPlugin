package com.legendary.plugin.core;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Standalone "send everyone to spawn on join" fallback, independent of
 * the verification module. The verification module (when enabled)
 * already resolves this the same way once a player passes the anti-bot
 * check - this listener only matters on a server that has
 * {@code modules.verification: false} but still wants every join to land
 * at a fixed spawn point rather than each player's last logout location
 * (simpler, common request on small survival/RPG servers that don't need
 * anti-bot verification at all). Always registered so it works
 * regardless of which other modules are on; entirely opt-in via
 * {@code general.spawn-on-join.enabled} (default false, since the
 * verification module's own release-to-spawn behavior is already the
 * default path most servers will use).
 */
public final class JoinSpawnFallback implements Listener {

    private final LegendaryPlugin plugin;

    public JoinSpawnFallback(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfig().getBoolean("general.spawn-on-join.enabled", false)) return;
        // Let the verification module handle placement itself when it's actually enabled -
        // this fallback only fires when there is no verification module doing that job.
        boolean verificationHandlesIt = plugin.getConfig().getBoolean("modules.verification", true)
            && plugin.getConfig().getBoolean("verification.enabled", true);
        if (verificationHandlesIt) return;

        Player player = event.getPlayer();
        if (player.hasPermission("legendary.verification.bypass")) return; // reuse the same exemption
        player.teleport(resolveLocation());
    }

    private Location resolveLocation() {
        String worldName = plugin.getConfig().getString("general.spawn-on-join.world", "");
        World world = worldName.isBlank() ? plugin.getServer().getWorlds().get(0) : Bukkit.getWorld(worldName);
        if (world == null) world = plugin.getServer().getWorlds().get(0);
        if (!plugin.getConfig().isSet("general.spawn-on-join.x")) {
            return world.getSpawnLocation();
        }
        double x = plugin.getConfig().getDouble("general.spawn-on-join.x");
        double y = plugin.getConfig().getDouble("general.spawn-on-join.y");
        double z = plugin.getConfig().getDouble("general.spawn-on-join.z");
        float yaw = (float) plugin.getConfig().getDouble("general.spawn-on-join.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("general.spawn-on-join.pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }
}
