package com.legendary.plugin.modules.database;

import com.legendary.plugin.LegendaryPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile HikariDataSource dataSource;
    private volatile boolean mysql;
    private final ReentrantLock writeLock = new ReentrantLock();
    private static final int MAX_PENDING_ROWS = 10_000;
    private final ArrayBlockingQueue<ViolationRow> pendingViolations = new ArrayBlockingQueue<>(MAX_PENDING_ROWS);
    private final ArrayBlockingQueue<ViolationLevelRow> pendingViolationLevels = new ArrayBlockingQueue<>(MAX_PENDING_ROWS);
    private final AtomicLong lastQueueWarning = new AtomicLong();
    private java.util.concurrent.ScheduledExecutorService batchFlushExecutor;

    public DatabaseManager(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public void connectAsync(Runnable onReady) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var cfg = plugin.getConfig();
                mysql = "mysql".equalsIgnoreCase(cfg.getString("anticheat.database.type", "sqlite"));
                HikariConfig poolConfig = new HikariConfig();
                poolConfig.setPoolName("LegendaryPlugin-Database");
                poolConfig.setMaximumPoolSize(cfg.getInt("anticheat.database.pool-size", 4));
                poolConfig.setMinimumIdle(1);
                poolConfig.setConnectionTimeout(5_000L);
                if (mysql) {
                    String host = cfg.getString("anticheat.database.mysql.host", "localhost");
                    int port = cfg.getInt("anticheat.database.mysql.port", 3306);
                    String database = cfg.getString("anticheat.database.mysql.database", "legendary");
                    String user = cfg.getString("anticheat.database.mysql.username", "root");
                    String password = System.getenv().getOrDefault("LEGENDARY_DB_PASSWORD",
                        cfg.getString("anticheat.database.mysql.password", ""));
                    boolean useSsl = cfg.getBoolean("anticheat.database.mysql.use-ssl", true);
                    String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=" + useSsl + "&autoReconnect=true&rewriteBatchedStatements=true";
                    poolConfig.setJdbcUrl(url);
                    poolConfig.setUsername(user);
                    poolConfig.setPassword(password);
                } else {
                    File dbFile = new File(plugin.getDataFolder(), cfg.getString("anticheat.database.file-name", "legendary.db"));
                    plugin.getDataFolder().mkdirs();
                    poolConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    poolConfig.setMaximumPoolSize(1);
                    dataSource = new HikariDataSource(poolConfig);
                    try (Connection connection = dataSource.getConnection();
                         Statement pragma = connection.createStatement()) {
                        pragma.execute("PRAGMA journal_mode=WAL");
                        pragma.execute("PRAGMA synchronous=NORMAL");
                        pragma.execute("PRAGMA cache_size=-64000");
                    }
                }
                if (mysql) dataSource = new HikariDataSource(poolConfig);
                createSchema();
                startBatchFlush();
                plugin.getServer().getScheduler().runTask(plugin, onReady);
            } catch (SQLException | RuntimeException e) {
                HikariDataSource failedPool = dataSource;
                dataSource = null;
                if (failedPool != null) failedPool.close();
                plugin.getLogger().severe("Database connection failed: " + e.getMessage());
            }
        });
    }

    private void createSchema() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) throw new SQLException("Database pool is not initialized");
        String idDefinition = mysql ? "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS anticheat_bans (
                    id %s,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(32) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    vl DOUBLE NOT NULL,
                    reason TEXT,
                    banned_at BIGINT NOT NULL,
                    staff_uuid VARCHAR(36)
                )
                """.formatted(idDefinition));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS anticheat_violations (
                    id %s,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(32) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    weight DOUBLE NOT NULL,
                    vl_after DOUBLE NOT NULL,
                    detail TEXT,
                    created_at BIGINT NOT NULL
                )
                """.formatted(idDefinition));
            statement.execute("""
                CREATE TABLE IF NOT EXISTS anticheat_violation_levels (
                    player_uuid VARCHAR(36) NOT NULL,
                    check_name VARCHAR(64) NOT NULL,
                    vl DOUBLE NOT NULL,
                    updated_at BIGINT NOT NULL,
                    PRIMARY KEY (player_uuid, check_name)
                )
                """);
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
            int currentVersion;
            try (var versions = statement.executeQuery("SELECT version FROM schema_version")) {
                currentVersion = versions.next() ? versions.getInt(1) : 0;
            }
            if (currentVersion == 0) statement.executeUpdate("INSERT INTO schema_version (version) VALUES (0)");
            if (currentVersion < 2) {
                ensureStaffUuidColumn(statement);
                statement.executeUpdate("UPDATE schema_version SET version = 2");
            }
            try {
                statement.execute(mysql
                    ? "CREATE INDEX idx_violations_player ON anticheat_violations(player_uuid, created_at)"
                    : "CREATE INDEX IF NOT EXISTS idx_violations_player ON anticheat_violations(player_uuid, created_at)");
            } catch (SQLException exception) {
                if (!mysql || exception.getErrorCode() != 1061) throw exception;
            }
        }
    }

    private void ensureStaffUuidColumn(Statement statement) throws SQLException {
        try {
            statement.execute("ALTER TABLE anticheat_bans ADD COLUMN staff_uuid VARCHAR(36)");
        } catch (SQLException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
            boolean alreadyPresent = mysql
                ? exception.getErrorCode() == 1060
                : message.contains("duplicate column name");
            if (!alreadyPresent) throw exception;
        }
    }

    public record ViolationRecord(String playerName, String checkName, double weight, double vlAfter, String detail, long createdAt) {}
    private record ViolationRow(String uuid, String name, String check, double weight, double vlAfter, String detail, long createdAt) {}
    private record ViolationLevelRow(String uuid, String check, double vl, long updatedAt) {}

    /** Queues a violation for batch insert. Non-blocking, always off the main thread. */
    public void recordViolationAsync(String uuid, String name, String check, double weight, double vlAfter, String detail) {
        if (!pendingViolations.offer(new ViolationRow(uuid, name, check, weight, vlAfter, detail, System.currentTimeMillis()))) {
            warnQueueFull("violation");
        }
    }

    public void saveViolationLevelAsync(String uuid, String check, double vl) {
        if (!pendingViolationLevels.offer(new ViolationLevelRow(uuid, check, vl, System.currentTimeMillis()))) {
            warnQueueFull("violation-level");
        }
    }

    private void warnQueueFull(String queueName) {
        long now = System.currentTimeMillis();
        long previous = lastQueueWarning.get();
        if (now - previous >= 10_000L && lastQueueWarning.compareAndSet(previous, now)) {
            plugin.getLogger().severe("Anticheat " + queueName
                + " persistence queue reached its limit; new rows are being rejected.");
        }
    }

    public void loadViolationLevelsAsync(String uuid, Consumer<Map<String, Double>> callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Double> levels = new HashMap<>();
            HikariDataSource source = dataSource;
            if (source == null || source.isClosed()) {
                plugin.getLogger().warning("Cannot load violation levels: database is not connected.");
                plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(Map.of()));
                return;
            }
            writeLock.lock();
            try (Connection connection = source.getConnection();
                 var stmt = connection.prepareStatement(
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
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) return;
        List<ViolationRow> batch = new ArrayList<>(128);
        List<ViolationLevelRow> levelBatch = new ArrayList<>(128);
        ViolationRow row;
        while ((row = pendingViolations.poll()) != null) batch.add(row);
        ViolationLevelRow levelRow;
        while ((levelRow = pendingViolationLevels.poll()) != null) levelBatch.add(levelRow);
        if (batch.isEmpty() && levelBatch.isEmpty()) return;

        writeLock.lock();
        try (Connection connection = source.getConnection()) {
            try (var stmt = connection.prepareStatement(
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
            try (var update = connection.prepareStatement(
                    "UPDATE anticheat_violation_levels SET vl = ?, updated_at = ? WHERE player_uuid = ? AND check_name = ?");
                 var insert = connection.prepareStatement(
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
            batch.forEach(violation -> {
                if (!pendingViolations.offer(violation)) warnQueueFull("violation");
            });
            levelBatch.forEach(level -> {
                if (!pendingViolationLevels.offer(level)) warnQueueFull("violation-level");
            });
        } finally {
            writeLock.unlock();
        }
    }

    public void getRecentViolationsAsync(String uuid, int limit, Consumer<List<ViolationRecord>> callback) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ViolationRecord> results = new ArrayList<>();
            HikariDataSource source = dataSource;
            if (source != null && !source.isClosed()) {
                String sql = "SELECT player_name, check_name, weight, vl_after, detail, created_at FROM anticheat_violations "
                    + "WHERE player_uuid = ? ORDER BY created_at DESC LIMIT ?";
                writeLock.lock();
                try (Connection connection = source.getConnection(); var stmt = connection.prepareStatement(sql)) {
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

    public boolean isConnected() { return dataSource != null && !dataSource.isClosed(); }
    public Connection getConnection() throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) throw new SQLException("Database is not connected");
        return source.getConnection();
    }

    public void recordBanAsync(String uuid, String name, String check, double vl, String reason) {
        recordBanAuditAsync(null, uuid, name, check, vl, reason);
    }

    public void recordBanAuditAsync(String staffUuid, String uuid, String name, String check,
                                    double vl, String reason) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            HikariDataSource source = dataSource;
            if (source == null || source.isClosed()) {
                plugin.getLogger().warning("Cannot record ban audit: database is not connected.");
                return;
            }
            String sql = "INSERT INTO anticheat_bans "
                + "(player_uuid, player_name, check_name, vl, reason, banned_at, staff_uuid) VALUES (?,?,?,?,?,?,?)";
            writeLock.lock();
            try (Connection connection = source.getConnection(); var stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                stmt.setString(2, name);
                stmt.setString(3, check);
                stmt.setDouble(4, vl);
                stmt.setString(5, reason);
                stmt.setLong(6, System.currentTimeMillis());
                stmt.setString(7, staffUuid);
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
        HikariDataSource source = dataSource;
        dataSource = null;
        if (source != null) {
            source.close();
        }
    }
}
