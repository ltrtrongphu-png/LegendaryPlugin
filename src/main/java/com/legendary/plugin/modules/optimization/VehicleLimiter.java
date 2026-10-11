package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Vehicle;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Removes empty boats and minecarts that have been sitting unattended
 * for a configurable period. Stacked vehicles at mob farms and transport
 * hubs are a common source of unnecessary chunk-load and tick overhead.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class VehicleLimiter {

    private final LegendaryPlugin plugin;
    private BukkitTask task;
    private int maxEmptyAgeTicks = 6000;
    private long intervalSeconds = 60;
    private int maxVehiclesPerChunk = 12;
    private boolean enabled = false;

    public VehicleLimiter(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        maxEmptyAgeTicks = cfg.getInt("optimization.vehicle-limiter.max-empty-age-ticks", 6000);
        intervalSeconds = cfg.getLong("optimization.vehicle-limiter.interval-seconds", 60);
        maxVehiclesPerChunk = cfg.getInt("optimization.vehicle-limiter.max-per-chunk", 12);
    }

    public void start() {
        if (task != null) task.cancel();
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::clean, intervalTicks, intervalTicks);
        enabled = true;
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        enabled = false;
    }

    private void clean() {
        AtomicInteger removed = new AtomicInteger();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof Vehicle vehicle)) continue;

                if (vehicle instanceof Boat boat) {
                    if (boat.isEmpty() && boat.getTicksLived() > maxEmptyAgeTicks) {
                        boat.remove();
                        removed.incrementAndGet();
                    }
                } else if (vehicle instanceof Minecart minecart) {
                    if (minecart.isEmpty() && minecart.getTicksLived() > maxEmptyAgeTicks) {
                        minecart.remove();
                        removed.incrementAndGet();
                    }
                }
            }

            for (var chunk : world.getLoadedChunks()) {
                int vehicleCount = 0;
                for (Entity e : chunk.getEntities()) {
                    if (e instanceof Vehicle) vehicleCount++;
                }
                if (vehicleCount > maxVehiclesPerChunk) {
                    int toRemove = vehicleCount - maxVehiclesPerChunk;
                    for (Entity e : chunk.getEntities()) {
                        if (toRemove <= 0) break;
                        if (e instanceof Boat boat && boat.isEmpty()) {
                            boat.remove();
                            removed.incrementAndGet();
                            toRemove--;
                        } else if (e instanceof Minecart mc && mc.isEmpty()) {
                            mc.remove();
                            removed.incrementAndGet();
                            toRemove--;
                        }
                    }
                }
            }
        }
        if (removed.get() > 0) {
            plugin.getLogger().info("[Optimization] Removed " + removed.get() + " empty vehicles.");
            plugin.getMetrics().counter("optimization.vehicles.removed").add(removed.get());
        }
    }

    public boolean isEnabled() { return enabled; }
}
