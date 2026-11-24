package com.legendary.plugin.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Helpers for crossing from asynchronous or Netty callbacks to Bukkit's main thread. */
public final class Sync {

    private Sync() {}

    public static void run(JavaPlugin plugin, Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, action);
    }

    public static void assertMain() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Bukkit API access must run on the primary server thread.");
        }
    }
}
