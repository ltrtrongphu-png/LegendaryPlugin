package com.legendary.plugin.modules.antiinfinitewater;

import com.legendary.plugin.LegendaryPlugin;
import com.legendary.plugin.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

/** Rate-limits unusually rapid water-bucket placement without altering water behavior. */
public final class AntiInfiniteWaterListener implements Listener {

    private final LegendaryPlugin plugin;
    private final AntiInfiniteWaterManager manager;

    public AntiInfiniteWaterListener(LegendaryPlugin plugin, AntiInfiniteWaterManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET || !manager.isBucketRateLimitEnabled()) return;
        Player player = event.getPlayer();
        if (player.hasPermission("legendary.antiinfinitewater.bypass")) return;
        if (manager.checkRateLimit(player.getUniqueId(), event.getBlock().getLocation())) return;

        event.setCancelled(true);
        Text.send(player, "<red>[WaterFlowLimiter] <gray>You're placing water too quickly in this chunk.");
        plugin.getMetrics().counter("water-flow-limiter.bucket.rate-limited").increment();
    }
}
