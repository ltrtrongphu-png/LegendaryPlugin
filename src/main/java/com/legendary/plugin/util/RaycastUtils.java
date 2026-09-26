package com.legendary.plugin.util;

import org.bukkit.Location;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Line-of-sight helpers shared by the ESP module (to decide whether a
 * hidden block/player should be revealed) and several anti-cheat checks
 * (reach, impossible-hit). Uses Paper's world ray trace so it respects
 * actual block collision boxes rather than a naive distance check.
 */
public final class RaycastUtils {

    private RaycastUtils() {}

    public static boolean hasLineOfSight(Location from, Location to, double blockingThreshold) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return false;
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        if (distance < 1e-4) return true;
        direction.normalize();
        RayTraceResult result = from.getWorld().rayTraceBlocks(from, direction, distance, null, true);
        if (result == null || result.getHitBlock() == null) return true;
        double hitDistance = result.getHitPosition().distance(from.toVector());
        // Allow near-miss (e.g. grass/foliage) up to blockingThreshold ratio of full distance.
        return hitDistance >= distance * (1.0 - blockingThreshold);
    }

    public static boolean canSeeBlock(Location eye, Location blockCenter, double maxDistance) {
        if (eye.getWorld() == null || !eye.getWorld().equals(blockCenter.getWorld())) return false;
        if (eye.distanceSquared(blockCenter) > maxDistance * maxDistance) return false;
        return hasLineOfSight(eye, blockCenter, 0.15);
    }
}
