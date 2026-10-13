package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.Trident;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically removes stray projectiles that have been stuck in the world
 * for too long (e.g. arrows embedded in blocks, tridents nobody picks up).
 * Each stray projectile keeps a chunk loaded and consumes tick time.
 *
 * Copyright (c) WhiteBlackMC709. All rights reserved.
 */
public final class ProjectileCleaner {

    private final LegendaryPlugin plugin;
    private BukkitTask task;
    private int maxAgeTicks = 600;
    private long intervalSeconds = 30;
    private boolean enabled = false;

    public ProjectileCleaner(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfigValues() {
        var cfg = plugin.getConfig();
        maxAgeTicks = cfg.getInt("optimization.projectile-cleaner.max-age-ticks", 600);
        intervalSeconds = cfg.getLong("optimization.projectile-cleaner.interval-seconds", 30);
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
                if (!(entity instanceof Projectile proj)) continue;
                if (entity instanceof Arrow arrow) {
                    if (arrow.isInBlock() && arrow.getTicksLived() > maxAgeTicks) {
                        arrow.remove();
                        removed.incrementAndGet();
                    } else if (arrow.getTicksLived() > maxAgeTicks * 2) {
                        arrow.remove();
                        removed.incrementAndGet();
                    }
                } else if (entity instanceof Trident trident) {
                    if (trident.getTicksLived() > maxAgeTicks * 4) {
                        trident.remove();
                        removed.incrementAndGet();
                    }
                } else if (entity instanceof Fireball || entity instanceof Snowball || entity instanceof ThrownPotion) {
                    if (entity.getTicksLived() > maxAgeTicks) {
                        entity.remove();
                        removed.incrementAndGet();
                    }
                }
            }
        }
        if (removed.get() > 0) {
            plugin.getMetrics().counter("optimization.projectiles.cleaned").add(removed.get());
        }
    }

    public boolean isEnabled() { return enabled; }
}
