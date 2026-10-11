package com.legendary.plugin.modules.anticheat;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Immutable context snapshot attached to every violation flag.
 * Carries the diagnostic metadata (position, ping, TPS, world) that
 * staff need to judge whether a flag was a real cheat or a lag artifact,
 * without each Check subclass having to gather and pass it individually.
 */
public final class ViolationContext {

    private final String checkId;
    private final String checkDisplayName;
    private final Player player;
    private final double weight;
    private final String detail;
    private final Location location;
    private final int ping;
    private final double tps;
    private final String worldName;
    private final long timestampMs;

    private ViolationContext(Builder b) {
        this.checkId = b.checkId;
        this.checkDisplayName = b.checkDisplayName;
        this.player = b.player;
        this.weight = b.weight;
        this.detail = b.detail;
        this.location = b.location;
        this.ping = b.ping;
        this.tps = b.tps;
        this.worldName = b.worldName;
        this.timestampMs = b.timestampMs;
    }

    public String getCheckId() { return checkId; }
    public String getCheckDisplayName() { return checkDisplayName; }
    public Player getPlayer() { return player; }
    public double getWeight() { return weight; }
    public String getDetail() { return detail; }
    public Location getLocation() { return location; }
    public int getPing() { return ping; }
    public double getTps() { return tps; }
    public String getWorldName() { return worldName; }
    public long getTimestampMs() { return timestampMs; }

    /** True when TPS is low enough that this flag may be a lag artifact. */
    public boolean isLagSuspect() { return tps > 0 && tps < 18.0; }

    public static Builder builder(Check check, Player player, double weight, String detail) {
        return new Builder(check, player, weight, detail);
    }

    public static final class Builder {
        private final String checkId;
        private String checkDisplayName;
        private final Player player;
        private final double weight;
        private final String detail;
        private Location location;
        private int ping;
        private double tps;
        private String worldName;
        private long timestampMs;

        Builder(Check check, Player player, double weight, String detail) {
            this.checkId = check.getId();
            this.checkDisplayName = check.getDisplayName();
            this.player = player;
            this.weight = weight;
            this.detail = detail;
            this.timestampMs = System.currentTimeMillis();
        }

        public Builder displayName(String name) { this.checkDisplayName = name; return this; }
        public Builder location(Location loc) { this.location = loc; return this; }
        public Builder ping(int ping) { this.ping = ping; return this; }
        public Builder tps(double tps) { this.tps = tps; return this; }
        public Builder worldName(String name) { this.worldName = name; return this; }
        public Builder timestamp(long ms) { this.timestampMs = ms; return this; }

        public ViolationContext build() {
            if (location == null && player != null) location = player.getLocation();
            if (worldName == null && location != null && location.getWorld() != null) worldName = location.getWorld().getName();
            return new ViolationContext(this);
        }
    }
}
