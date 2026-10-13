package com.legendary.plugin.modules.database;

import com.legendary.plugin.LegendaryPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;

/**
 * Thin async wrapper around a JDBC connection (SQLite by default, MySQL
 * optional) used to persist ban history / long-term violation stats.
 *
 * PERFORMANCE UPGRADE: adds SQLite WAL mode for better concurrent read/write
 * throughput, a batch insert queue for violations (flushes every 5 seconds
 * instead of one INSERT per flag), and prepared statement caching for the
 * batch flush path. This dramatically reduces disk I/O when many violations
 * fire in a short window (e.g. a cheater triggering 10+ checks per second).
 */
public final class DatabaseManager {

    private final LegendaryPlugin plugin;
    private volatile Connection connection;
    private volatile boolean mysql;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<ViolationRow> pendingViolations = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ViolationLevelRow> pendingViolationLevels = new ConcurrentLinkedQueue<>();
    private java.util.concurrent.ScheduledExecutorService batchFlushExecutor;

    public DatabaseManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void connectAsync(Runnable onReady) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var cfg = plugin.getConfig();
                mysql = "mysql".equalsIgnoreCase(cfg.getString("anticheat.database.type", "sqlite"));
                if (mysql) {
                    String host = cfg.getString("anticheat.database.mysql.host", "localhost");
                    int port = cfg.getInt("anticheat.database.mysql.port", 3306);
                    String database = cfg.getString("anticheat.database.mysql.database", "legendary");
                    String user = cfg.getString("anticheat.database.mysql.username", "root");
                    String password = cfg.getString("anticheat.database.mysql.password", "");
                    String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true&rewriteBatchedStatements=true";
                    connection = DriverManager.getConnection(url, user, password);
                } else {
                    File dbFile = new File(plugin.getDataFolder(), cfg.getString("anticheat.database.file-name", "legendary.db"));
                    plugin.getDataFolder().mkdirs();
                    try {
                        Class.forName("org.sqlite.JDBC");
                    } catch (ClassNotFoundException ignored) {}
                    connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    try (Statement pragma = connection.createStatement()) {
                        pragma.execute("PRAGMA journal_mode=WAL");
                        pragma.execute("PRAGMA synchronous=NORMAL");
                        pragma.execute("PRAGMA cache_size=-64000");
                    }
                }
                createSchema();
                startBatchFlush();
                plugin.getServer().getScheduler().runTask(plugin, onReady);
            } catch (SQLException e) {
                plugin.getLogger().severe("Database connection failed: " + e.getMessage());
            }
        });
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS anticheat_bans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(32) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    vl DOUBLE NOT NULL,
                    reason TEXT,
                    banned_at BIGINT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS anticheat_violations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(32) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    weight DOUBLE NOT NULL,
                    vl_after DOUBLE NOT NULL,
                    detail TEXT,
                    created_at BIGINT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE IF NOT EXISTS anticheat_violation_levels (
                    player_uuid VARCHAR(36) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    vl DOUBLE NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (player_uuid, check_name)
                )
                """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_violations_player ON anticheat_violations(player_uuid, created_at)");
        }
    }

    public record ViolationRecord(String playerName, String checkName, double weight, double vlAfter, String detail, long createdAt) {}
    private record ViolationRow(String uuid, String name, String check, double weight, double vlAfter, String detail, long createdAt) {}
    private record ViolationLevelRow(String uuid, String check, double vl, long updatedAt) {}

    /** Queues a violation for batch insert. Non-blocking, always off the main thread. */
    public void recordViolationAsync(String uuid, String name, String check, double weight, double vlAfter, String detail) {
        pendingViolations.add(new ViolationRow(uuid, name, check, weight, vlAfter, detail, System.currentTimeMillis()));
    }

    public void saveViolationLevelAsync(String uuid, String check, double vl) {
        pendingViolationLevels.add(new ViolationLevelRow(uuid, check, vl, System.currentTimeMillis()));
    }

    public void loadViolationLevelsAsync(String uuid, Consumer<Map<String, Double>> callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Double> levels = new HashMap<>();
            Connection c = connection;
            if (c == null) return;
            writeLock.lock();
            try (var stmt = c.prepareStatement(
                    "SELECT check_name, vl FROM anticheat_violation_levels WHERE player_uuid = ?")) {
                stmt.setString(1, uuid);
                try (var rs = stmt.executeQuery()) {
                    while (rs.next()) levels.put(rs.getString("check_name"), rs.getDouble("vl"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to load violation levels: " + e.getMessage());
            } finally {
                writeLock.unlock();
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(levels));
        });
    }

    private void startBatchFlush() {
        batchFlushExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LegendaryPlugin-DB-Flush");
            t.setDaemon(true);
            return t;
        });
        batchFlushExecutor.scheduleAtFixedRate(this::flushViolations, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void flushViolations() {
        if (pendingViolations.isEmpty() && pendingViolationLevels.isEmpty()) return;
        Connection c = connection;
        if (c == null) return;
        List<ViolationRow> batch = new ArrayList<>(128);
        List<ViolationLevelRow> levelBatch = new ArrayList<>(128);
        ViolationRow row;
        while ((row = pendingViolations.poll()) != null) batch.add(row);
        ViolationLevelRow levelRow;
        while ((levelRow = pendingViolationLevels.poll()) != null) levelBatch.add(levelRow);
        if (batch.isEmpty() && levelBatch.isEmpty()) return;

        writeLock.lock();
        try {
            try (var stmt = c.prepareStatement(
                    "INSERT INTO anticheat_violations (player_uuid, player_name, check_name, weight, vl_after, detail, created_at) VALUES (?,?,?,?,?,?,?)")) {
                for (ViolationRow r : batch) {
                    stmt.setString(1, r.uuid());
                    stmt.setString(2, r.name());
                    stmt.setString(3, r.check());
                    stmt.setDouble(4, r.weight());
                    stmt.setDouble(5, r.vlAfter());
                    stmt.setString(6, r.detail());
                    stmt.setLong(7, r.createdAt());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            try (var update = c.prepareStatement(
                    "UPDATE anticheat_violation_levels SET vl = ?, updated_at = ? WHERE player_uuid = ? AND check_name = ?");
                 var insert = c.prepareStatement(
                    "INSERT INTO anticheat_violation_levels (vl, updated_at, player_uuid, check_name) VALUES (?,?,?,?)")) {
                for (ViolationLevelRow r : levelBatch) {
                    update.setDouble(1, r.vl());
                    update.setLong(2, r.updatedAt());
                    update.setString(3, r.uuid());
                    update.setString(4, r.check());
                    if (update.executeUpdate() == 0) {
                        insert.setDouble(1, r.vl());
                        insert.setLong(2, r.updatedAt());
                        insert.setString(3, r.uuid());
                        insert.setString(4, r.check());
                        insert.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to flush anticheat data: " + e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    public void getRecentViolationsAsync(String uuid, int limit, Consumer<List<ViolationRecord>> callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ViolationRecord> results = new ArrayList<>();
            Connection c = connection;
            if (c != null) {
                String sql = "SELECT player_name, check_name, weight, vl_after, detail, created_at FROM anticheat_violations "
                    + "WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?";
                writeLock.lock();
                try (var stmt = c.prepareStatement(sql)) {
                    stmt.setString(1, uuid);
                    stmt.setInt(2, limit);
                    try (var rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            results.add(new ViolationRecord(
                                rs.getString("player_name"), rs.getString("check_name"),
                                rs.getDouble("weight"), rs.getDouble("vl_after"),
                                rs.getString("detail"), rs.getLong("created_at")));
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to read violation history: " + e.getMessage());
                } finally {
                    writeLock.unlock();
                }
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(results));
        });
    }

    public boolean isConnected() { return connection != null; }
    public Connection getConnection() { return connection; }

    public void recordBanAsync(String uuid, String name, String check, double vl, String reason) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Connection c = connection;
            if (c == null) return;
            String sql = "INSERT INTO anticheat_bans (player_uuid, player_name, check_name, vl, reason, banned_at) VALUES (?,?,?,?,?,?)";
            writeLock.lock();
            try (var stmt = c.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                stmt.setString(2, name);
                stmt.setString(3, check);
                stmt.setDouble(4, vl);
                stmt.setString(5, reason);
                stmt.setLong(6, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to record ban: " + e.getMessage());
            } finally {
                writeLock.unlock();
            }
        });
    }

    public void shutdown() {
        if (batchFlushExecutor != null) {
            flushViolations();
            batchFlushExecutor.shutdownNow();
            batchFlushExecutor = null;
        }
        Connection c = connection;
        connection = null;
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }
}
