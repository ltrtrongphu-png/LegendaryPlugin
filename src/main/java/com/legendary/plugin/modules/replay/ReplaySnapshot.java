package com.legendary.plugin.modules.replay;

import org.bukkit.Location;

/** One point-in-time sample of a player's position/rotation, used to build a {@link ReplayClip}. */
public record ReplaySnapshot(long timestamp, double x, double y, double z, float yaw, float pitch, String worldName) {

    public static ReplaySnapshot of(Location loc) {
        return new ReplaySnapshot(System.currentTimeMillis(), loc.getX(), loc.getY(), loc.getZ(),
            loc.getYaw(), loc.getPitch(), loc.getWorld() == null ? "world" : loc.getWorld().getName());
    }
}
