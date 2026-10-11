package com.legendary.plugin.modules.verification;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Wires join/move/quit events into the {@link VerificationManager} and the IP rate limiter. */
public final class VerificationListener implements Listener {

    private final LegendaryPlugin plugin;
    private final VerificationManager verificationManager;
    private final IpRateLimiter ipRateLimiter;
    private boolean rateLimitEnabled;
    private int maxJoinsPerMinute;
    private String kickMessage;

    public VerificationListener(LegendaryPlugin plugin, VerificationManager verificationManager) {
        this.plugin = plugin;
        this.verificationManager = verificationManager;
        this.ipRateLimiter = new IpRateLimiter();
    }

    public void loadConfigValues() {
        rateLimitEnabled = plugin.getConfig().getBoolean("verification.ip-rate-limit.enabled", true);
        maxJoinsPerMinute = plugin.getConfig().getInt("verification.ip-rate-limit.max-joins-per-minute", 6);
        kickMessage = plugin.getConfig().getString("verification.ip-rate-limit.kick-message",
            "<red>Too many join attempts from your network. Try again later.");
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        if (!rateLimitEnabled) return;
        String ip = event.getAddress() == null ? "unknown" : event.getAddress().getHostAddress();
        if (ipRateLimiter.isOverLimit(ip, maxJoinsPerMinute)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                com.legendary.plugin.util.Text.of(kickMessage));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // If the player is exempt from verification (bypass permission, or the whole
        // verification module is off), they'd otherwise just stay wherever the vanilla
        // server put them (their last logout spot / bed). When
        // verification.always-spawn-on-join is on, send them to the same fixed release
        // location everyone else lands at after passing verification, for consistency.
        if (player.hasPermission("legendary.verification.bypass")
            && plugin.getConfig().getBoolean("verification.always-spawn-on-join", true)) {
            player.teleport(verificationManager.resolveReleaseLocation());
            return;
        }
        verificationManager.beginVerification(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (verificationManager.isVerifying(player)) {
            verificationManager.tryComplete(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getClickedBlock() == null || !verificationManager.isVerifying(player)) return;
        verificationManager.tryCompleteClick(player, event.getClickedBlock().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        verificationManager.cancel(event.getPlayer());
    }
}
