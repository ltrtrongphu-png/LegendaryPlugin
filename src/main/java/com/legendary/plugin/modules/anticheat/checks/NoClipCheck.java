package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.core.ConfigUtil;
import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Flags a player whose hitbox is intersecting a genuinely solid block for several ticks (phase/noclip hacks). */
public final class NoClipCheck extends Check implements Listener {

    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> insideSolidTicks = new ConcurrentHashMap<>();
    private int requiredTicks;

    public NoClipCheck(AnticheatModule anticheat) {
        super(anticheat, "noclip", "NoClip");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        requiredTicks = ConfigUtil.getBoundedInt(plugin, path("required-ticks"), 4, 1, 40);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        graceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 1500);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            graceUntil.put(player.getUniqueId(), System.currentTimeMillis() + 500);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        Long grace = graceUntil.get(uuid);
        if (grace != null && System.currentTimeMillis() < grace) return;

        Block feet = player.getLocation().getBlock();
        Block head = player.getEyeLocation().getBlock();
        if (isTrulySolid(feet) || isTrulySolid(head)) {
            int ticks = insideSolidTicks.merge(uuid, 1, Integer::sum);
            if (ticks >= requiredTicks) {
                flag(player, 1.0, "insideSolidTicks=" + ticks);
                insideSolidTicks.put(uuid, 0);
            }
        } else {
            insideSolidTicks.remove(uuid);
        }
    }

    private boolean isTrulySolid(Block block) {
        Material type = block.getType();
        return type.isSolid() && type.isOccluding() && block.getBoundingBox().getVolume() > 0.1;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        graceUntil.remove(uuid);
        insideSolidTicks.remove(uuid);
    }
}
