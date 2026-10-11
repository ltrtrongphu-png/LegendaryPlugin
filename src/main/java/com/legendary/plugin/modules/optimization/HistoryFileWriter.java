package com.legendary.plugin.modules.optimization;

import com.legendary.plugin.LegendaryPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Appends TPS-history rows to a CSV file, rotating it once it grows past
 * maxLines. Requirement: NEVER touch disk on the main thread. Every public
 * method here queues work onto an async Bukkit task; callers never block.
 * Ported from SmartOptimizer.HistoryFileWriter.
 */
public final class HistoryFileWriter {

    private static final DateTimeFormatter TS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String HEADER = "timestamp,epoch_millis,tps,level";

    private final LegendaryPlugin plugin;
    private final File file;
    private final int maxLines;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean warnedOnce = false;
    private volatile int currentLines = -1;

    public HistoryFileWriter(LegendaryPlugin plugin, File dataFolder, String fileName, int maxLines) {
        this.plugin = plugin;
        this.file = new File(dataFolder, fileName);
        this.maxLines = Math.max(100, maxLines);
    }

    /** Fire-and-forget append; always runs off the main thread. */
    public void appendAsync(long epochMillis, double tps, String level) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> append(epochMillis, tps, level));
    }

    private void append(long epochMillis, double tps, String level) {
        lock.lock();
        try {
            if (currentLines < 0) currentLines = countLines();
            rotateIfNeeded();
            boolean writeHeader = !file.exists() || file.length() == 0;
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
                if (writeHeader) {
                    writer.write(HEADER);
                    writer.newLine();
                    currentLines = 1;
                }
                String ts = TS_FORMAT.format(Instant.ofEpochMilli(epochMillis));
                writer.write(ts + "," + epochMillis + "," + String.format("%.2f", tps) + "," + level);
                writer.newLine();
                currentLines++;
            } catch (IOException e) {
                warnOnce("Failed to append TPS history: " + e.getMessage());
            }
        } finally {
            lock.unlock();
        }
    }

    private int countLines() {
        if (!file.exists()) return 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            int count = 0;
            while (reader.readLine() != null) count++;
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private void rotateIfNeeded() {
        if (currentLines < maxLines || !file.exists()) return;
        // Keep only the most recent half to avoid unbounded growth.
        Deque<String> keep = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int keepCount = maxLines / 2;
            Deque<String> buffer = new ArrayDeque<>();
            while ((line = reader.readLine()) != null) {
                buffer.addLast(line);
                if (buffer.size() > keepCount) buffer.removeFirst();
            }
            keep = buffer;
        } catch (IOException e) {
            warnOnce("Failed to rotate TPS history: " + e.getMessage());
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
            writer.write(HEADER);
            writer.newLine();
            for (String line : keep) {
                if (!line.equals(HEADER)) {
                    writer.write(line);
                    writer.newLine();
                }
            }
            currentLines = keep.size() + 1;
        } catch (IOException e) {
            warnOnce("Failed to rewrite rotated TPS history: " + e.getMessage());
        }
    }

    private void warnOnce(String message) {
        if (!warnedOnce) {
            plugin.getLogger().warning(message);
            warnedOnce = true;
        }
    }

    public File getFile() {
        return file;
    }
}
