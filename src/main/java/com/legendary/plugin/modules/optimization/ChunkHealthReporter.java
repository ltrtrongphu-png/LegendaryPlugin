package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Reports the "busiest" chunks on the server (highest entity count),
 * broken down by category, used by /legendary topchunks. Ported from
 * SmartOptimizer.ChunkHealthReporter.
 *
 * PERFORMANCE FIX (2026-09-20, per user report of commands causing lag):
 * the original version built a full {@code ChunkReport} for literally
 * every loaded chunk on the server, collected them all into one list,
 * sorted the whole thing, and only then took the top N - on a server
 * with thousands of loaded chunks (plausible at up to 100 concurrent
 * players) that is a real synchronous hitch, all inside one command
 * execution on the main thread. Fixed two ways:
 *   1. A bounded min-heap of size `limit` replaces "collect everything,
 *      sort everything" - O(chunks log limit) instead of O(chunks log chunks),
 *      and no huge intermediate list allocation.
 *   2. The actual entity scan is spread across several ticks (a small
 *      batch of chunks per tick) via {@link #topChunksIncremental} instead
 *      of doing every chunk in one synchronous pass - the command now
 *      replies once scanning finishes rather than blocking the tick it
 *      was issued on. {@link #topChunks} (still used internally) is kept
 *      as the single-tick, no-scheduling version for small chunk counts.
 */
public final class ChunkHealthReporter {

    private final LegendaryPlugin plugin;

    public ChunkHealthReporter(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public static final class ChunkReport {
        public final String world;
        public final int x, z, total, mobs, items, xpOrbs, armorStands, fallingBlocks, other;

        public ChunkReport(String world, int x, int z, int total, int mobs, int items,
                            int xpOrbs, int armorStands, int fallingBlocks, int other) {
            this.world = world; this.x = x; this.z = z; this.total = total;
            this.mobs = mobs; this.items = items; this.xpOrbs = xpOrbs;
            this.armorStands = armorStands; this.fallingBlocks = fallingBlocks; this.other = other;
        }
    }

    /** Synchronous, single-pass version - fine for a handful of chunks (e.g. one world, small map). */
    public List<ChunkReport> topChunks(Iterable<World> worlds, int limit) {
        PriorityQueue<ChunkReport> heap = new PriorityQueue<>(Math.max(1, limit), Comparator.comparingInt(r -> r.total));
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                offer(heap, analyze(world.getName(), chunk), limit);
            }
        }
        return drain(heap);
    }

    /**
     * Scans a bounded number of chunks per tick instead of every loaded chunk in one go,
     * so /legendary topchunks never causes a single-tick hitch even with thousands of
     * loaded chunks. Calls back on the main thread once the full scan completes.
     */
    public void topChunksIncremental(List<World> worlds, int limit, int chunksPerTick, Consumer<List<ChunkReport>> callback) {
        List<ChunkRef> allChunks = new ArrayList<>();
        for (World world : worlds) {
            for (Chunk chunk : world.getLoadedChunks()) {
                allChunks.add(new ChunkRef(world.getName(), chunk));
            }
        }
        if (allChunks.isEmpty()) {
            callback.accept(List.of());
            return;
        }

        PriorityQueue<ChunkReport> heap = new PriorityQueue<>(Math.max(1, limit), Comparator.comparingInt(r -> r.total));
        int[] index = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int processed = 0;
            while (index[0] < allChunks.size() && processed < chunksPerTick) {
                ChunkRef ref = allChunks.get(index[0]);
                offer(heap, analyze(ref.worldName, ref.chunk), limit);
                index[0]++;
                processed++;
            }
            if (index[0] >= allChunks.size()) {
                task.cancel();
                callback.accept(drain(heap));
            }
        }, 0L, 1L);
    }

    private record ChunkRef(String worldName, Chunk chunk) {}

    private void offer(PriorityQueue<ChunkReport> heap, ChunkReport report, int limit) {
        if (limit <= 0) return;
        if (heap.size() < limit) {
            heap.offer(report);
        } else if (heap.peek() != null && report.total > heap.peek().total) {
            heap.poll();
            heap.offer(report);
        }
    }

    private List<ChunkReport> drain(PriorityQueue<ChunkReport> heap) {
        List<ChunkReport> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingInt((ChunkReport r) -> r.total).reversed());
        return Collections.unmodifiableList(result);
    }

    private ChunkReport analyze(String worldName, Chunk chunk) {
        int mobs = 0, items = 0, xpOrbs = 0, armorStands = 0, fallingBlocks = 0, other = 0;
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Monster || e instanceof Animals) mobs++;
            else if (e instanceof Item) items++;
            else if (e instanceof ExperienceOrb) xpOrbs++;
            else if (e instanceof ArmorStand) armorStands++;
            else if (e instanceof FallingBlock) fallingBlocks++;
            else other++;
        }
        int total = mobs + items + xpOrbs + armorStands + fallingBlocks + other;
        return new ChunkReport(worldName, chunk.getX(), chunk.getZ(), total, mobs, items, xpOrbs, armorStands, fallingBlocks, other);
    }
}
