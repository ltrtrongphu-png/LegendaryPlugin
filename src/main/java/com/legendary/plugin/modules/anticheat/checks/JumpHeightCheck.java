package com.legendary.plugin.modules.anticheat.checks;

import com.legendary.plugin.modules.anticheat.AnticheatModule;
import com.legendary.plugin.modules.anticheat.Check;
import com.legendary.plugin.modules.anticheat.physics.VanillaPhysics;
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

/**
 * NEW CHECK (2026-09-20, gap found by reviewing a hack client's module
 * list: movement/HighJump, movement/AirJump, movement/LongJump). Tracks
 * cumulative height gained since a player last left the ground; vanilla
 * caps a single unassisted jump at roughly 1.25 blocks. Also catches
 * "air jump" (a renewed upward velocity spike after the player has
 * already started falling, with nothing under them to explain it -
 * bed bounce, slime/honey block, elytra, and Jump Boost are all
 * accounted for so legitimate play isn't flagged).
 */
public final class JumpHeightCheck extends Check implements Listener {

    private static final double VANILLA_MAX_JUMP = 1.3; // small margin above the real ~1.25

    private final Map<UUID, Double> groundY = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> wasFalling = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();
    private double maxJumpHeight;

    public JumpHeightCheck(AnticheatModule anticheat) {
        super(anticheat, "jumpheight", "JumpHeight");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean(path("enabled"), true);
        maxJumpHeight = plugin.getConfig().getDouble(path("max-jump-height"), VANILLA_MAX_JUMP);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        groundY.put(uuid, event.getTo().getY());
        graceUntil.put(uuid, System.currentTimeMillis() + 1000);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        // Explosions/knockback legitimately launch players upward - grace, don't flag those.
        if (event.getEntity() instanceof Player player) {
            graceUntil.put(player.getUniqueId(), System.currentTimeMillis() + 1500);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        UUID uuid = player.getUniqueId();
        if (player.isFlying() || player.getAllowFlight() || player.isGliding() || player.isInsideVehicle()
            || player.isClimbing() || player.isInWater() || player.isInLava()
            || VanillaPhysics.hasVerticalOverrideEffect(player)
            || isOnBouncyBlock(player)) {
            groundY.put(uuid, event.getTo().getY());
            wasFalling.put(uuid, false);
            return;
        }
        if (System.currentTimeMillis() < graceUntil.getOrDefault(uuid, 0L)) return;

        double y = event.getTo().getY();

        if (player.isOnGround()) {
            groundY.put(uuid, y);
            wasFalling.put(uuid, false);
            return;
        }

        double base = groundY.getOrDefault(uuid, y);
        double heightGained = y - base;
        double jumpBoostBonus = extraJumpBoostHeight(player);

        // PlayerMoveEvent has no getVelocity() - that method belongs to Player/Entity
        // and reflects the last velocity the server *set*, not the actual movement
        // between packets. For detecting real client movement, derive vertical
        // speed from the from/to Y delta of this move event instead.
        double deltaY = event.getTo().getY() - event.getFrom().getY();

        boolean falling = deltaY < -0.05;
        boolean previouslyFalling = wasFalling.getOrDefault(uuid, false);
        if (falling) {
            wasFalling.put(uuid, true);
        } else if (previouslyFalling && deltaY > 0.15) {
            // Was descending, now suddenly climbing again with nothing to explain it - air jump.
            flag(player, 1.0, "renewed ascent after falling (air jump signature)");
            wasFalling.put(uuid, false);
            groundY.put(uuid, y);
            return;
        }

        if (heightGained > maxJumpHeight + jumpBoostBonus) {
            flag(player, 1.0, String.format("jumpHeight=%.2f cap=%.2f", heightGained, maxJumpHeight + jumpBoostBonus));
            groundY.put(uuid, y); // avoid repeat-flagging the same jump every subsequent tick
        }
    }

    private boolean isOnBouncyBlock(Player player) {
        var below = player.getLocation().clone().subtract(0, 0.3, 0).getBlock().getType();
        return below == org.bukkit.Material.SLIME_BLOCK || below == org.bukkit.Material.HONEY_BLOCK
            || below.name().equals("BUBBLE_COLUMN");
    }

    private double extraJumpBoostHeight(Player player) {
        var effect = player.getPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST);
        // Vanilla: each Jump Boost level adds roughly half a block of extra jump height.
        return effect == null ? 0.0 : 0.5 * (effect.getAmplifier() + 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        groundY.remove(uuid);
        wasFalling.remove(uuid);
        graceUntil.remove(uuid);
    }
}
