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
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combined horizontal-speed and vertical-flight check. Grants a short
 * grace window after teleports/damage (knockback, elytra-launch, etc.)
 * to avoid false positives, and scales its speed cap by the current TPS
 * compensation factor from {@link com.legendary.plugin.modules.anticheat.TpsMonitor}.
 * Ported from BaB.MovementCheck.
 */
public final class MovementCheck extends Check implements Listener {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;

    private final Map<UUID, Long> teleportGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> damageGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> velocityGraceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMovementAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> speedViolationStreak = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> verticalViolationStreak = new ConcurrentHashMap<>();

    private boolean speedEnabled;
    private boolean flightEnabled;
    private double maxSpeedMultiplier;
    private double verticalTolerance;
    private int maxAirborneTicks;

    public MovementCheck(AnticheatModule anticheat) {
        super(anticheat, "movement", "Movement");
    }

    @Override
    public void loadConfigValues() {
        enabled = plugin.getConfig().getBoolean("anticheat.checks.movement.speed-enabled", true)
            || plugin.getConfig().getBoolean("anticheat.checks.movement.flight-enabled", true);
        speedEnabled = plugin.getConfig().getBoolean("anticheat.checks.movement.speed-enabled", true);
        flightEnabled = plugin.getConfig().getBoolean("anticheat.checks.movement.flight-enabled", true);
        maxSpeedMultiplier = plugin.getConfig().getDouble("anticheat.checks.movement.max-speed-multiplier", 1.3);
        verticalTolerance = plugin.getConfig().getDouble("anticheat.checks.movement.vertical-tolerance", 0.08);
        maxAirborneTicks = plugin.getConfig().getInt("anticheat.checks.movement.max-airborne-ticks", 20);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        teleportGraceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 2000);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            damageGraceUntil.put(player.getUniqueId(), System.currentTimeMillis() + 1500);
        }
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent event) {
        velocityGraceUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 1500L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (shouldSkip(player)) return;
        if (player.getAllowFlight() || player.isFlying() || player.isInsideVehicle()
            || player.isGliding() || player.isRiptiding()
            || player.getGameMode() != org.bukkit.GameMode.SURVIVAL
                && player.getGameMode() != org.bukkit.GameMode.ADVENTURE) return;

        if (event.getTo() == null) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean inGrace = now < teleportGraceUntil.getOrDefault(uuid, 0L)
            || now < damageGraceUntil.getOrDefault(uuid, 0L)
            || now < velocityGraceUntil.getOrDefault(uuid, 0L);
        if (inGrace) return;
        if (hasMovementOverride(player, event.getTo())) {
            speedViolationStreak.remove(uuid);
            verticalViolationStreak.remove(uuid);
            return;
        }
        Long previousMove = lastMovementAt.put(uuid, now);
        double elapsedTicks = previousMove == null ? 1.0
            : Math.max(1.0, Math.min(4.0, (now - previousMove) / 50.0));

        double compensation = anticheat.getTpsMonitor().getCompensationFactor();

        if (speedEnabled) {
            double horizontal = Math.hypot(
                event.getTo().getX() - event.getFrom().getX(),
                event.getTo().getZ() - event.getFrom().getZ());
            double walkSpeed = player.getWalkSpeed();
            double attributeMultiplier = walkSpeed <= 0.0 ? 1.0
                : Math.max(0.25, Math.min(4.0, walkSpeed / 0.2));
            double baseSpeed = 0.29 * attributeMultiplier;
            // Speed/Slowness potions legitimately change how fast vanilla lets a player move -
            // without this, a player who just drank Speed II would get flagged for playing normally.
            double cap = baseSpeed * maxSpeedMultiplier * compensation
                * VanillaPhysics.horizontalSpeedMultiplier(player) * elapsedTicks;
            if (horizontal > cap) {
                int streak = speedViolationStreak.merge(uuid, 1, Integer::sum);
                if (streak >= 3) {
                    flag(player, 1.0, String.format("hSpeed=%.3f cap=%.3f", horizontal, cap));
                    speedViolationStreak.put(uuid, 0);
                }
            } else {
                speedViolationStreak.merge(uuid, -1, (a, b) -> Math.max(0, a + b));
            }
        }

        if (flightEnabled && !VanillaPhysics.hasVerticalOverrideEffect(player)) {
            double dy = event.getTo().getY() - event.getFrom().getY();
            double tolerance = (verticalTolerance + VanillaPhysics.extraVerticalTolerance(player)) * elapsedTicks;
            boolean grounded = event.getTo().getY() <= event.getFrom().getY()
                && isGrounded(player, event.getTo());
            if (dy > tolerance && !grounded) {
                int streak = verticalViolationStreak.merge(uuid, 1, Integer::sum);
                if (streak >= maxAirborneTicks) {
                    flag(player, 1.0, String.format("sustainedUpwardTicks=%d", streak));
                    verticalViolationStreak.put(uuid, 0);
                }
            } else {
                verticalViolationStreak.put(uuid, 0);
            }
        } else {
            verticalViolationStreak.put(uuid, 0);
        }
    }

    private boolean hasMovementOverride(Player player, org.bukkit.Location to) {
        if (player.isSwimming() || player.isClimbing()
            || VanillaPhysics.hasEffect(player, "levitation")
            || VanillaPhysics.hasEffect(player, "slow_falling")) return true;
        Material feet = to.getBlock().getType();
        Material below = to.getWorld().getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ()).getType();
        if (feet == Material.WATER || feet == Material.LAVA || feet == Material.BUBBLE_COLUMN
            || feet == Material.COBWEB || feet == Material.HONEY_BLOCK || feet == Material.SOUL_SAND
            || feet == Material.SOUL_SOIL || below == Material.ICE || below == Material.PACKED_ICE
            || below == Material.BLUE_ICE || below == Material.SLIME_BLOCK || below == Material.HONEY_BLOCK) {
            return true;
        }
        return feet == Material.LADDER || feet == Material.VINE || feet == Material.SCAFFOLDING
            || feet == Material.WEEPING_VINES || feet == Material.WEEPING_VINES_PLANT
            || feet == Material.TWISTING_VINES || feet == Material.TWISTING_VINES_PLANT
            || feet == Material.CAVE_VINES || feet == Material.CAVE_VINES_PLANT;
    }

    private boolean isGrounded(Player player, org.bukkit.Location to) {
        if (player.isOnGround()) return true;
        Material below = to.getWorld().getBlockAt(to.getBlockX(), to.getBlockY() - 1, to.getBlockZ()).getType();
        return below.isSolid();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        teleportGraceUntil.remove(uuid);
        damageGraceUntil.remove(uuid);
        velocityGraceUntil.remove(uuid);
        lastMovementAt.remove(uuid);
        speedViolationStreak.remove(uuid);
        verticalViolationStreak.remove(uuid);
    }
}
