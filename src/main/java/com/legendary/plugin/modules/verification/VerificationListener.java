package com.legendary.plugin.modules.verification;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Wires verification and limbo interaction restrictions into the manager. */
public final class VerificationListener implements Listener {

    private final LegendaryPlugin plugin;
    private final VerificationManager verificationManager;

    public VerificationListener(LegendaryPlugin plugin, VerificationManager verificationManager) {
        this.plugin = plugin;
        this.verificationManager = verificationManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        verificationManager.restoreOriginalLocation(player);
        // Bypass players retain their normal login location unless spawn routing is explicitly enabled.
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
            if (event.getTo() != null && event.getTo().getY() < 60) {
                event.setTo(event.getFrom());
                verificationManager.returnToLimbo(player);
                return;
            }
            verificationManager.tryComplete(player);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!verificationManager.isVerifying(player)) return;
        if (event.getClickedBlock() != null) {
            verificationManager.tryCompleteClick(player, event.getClickedBlock().getLocation());
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (verificationManager.isVerifying(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (verificationManager.isVerifying(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!verificationManager.isVerifying(event.getPlayer())) return;
        String command = event.getMessage().split("\\s", 2)[0].toLowerCase();
        boolean allowed = plugin.getConfig().getStringList("verification.allowed-commands").stream()
            .map(value -> value.startsWith("/") ? value.toLowerCase() : "/" + value.toLowerCase())
            .anyMatch(command::equals);
        if (!allowed) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && verificationManager.isVerifying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (verificationManager.isVerifying(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && verificationManager.isVerifying(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!verificationManager.isVerifying(player)) return;
        verificationManager.cancel(player);
        player.kick(com.legendary.plugin.util.Text.of("<red>Verification failed. Please reconnect."));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        verificationManager.cancel(event.getPlayer());
    }
}
