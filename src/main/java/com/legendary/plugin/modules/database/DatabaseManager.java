package com.legendary.plugin.modules.database;

import com.legendary.plugin.LegendaryPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thin async wrapper around a JDBC connection (SQLite by default, MySQL
 * optional) used to persist ban history / long-term violation stats.
 * Every query runs on an async task - the JDBC connection itself is never
 * touched from the main thread. Ported from BaB's DatabaseManager, kept
 * dependency-free (no HikariCP) to avoid extra shaded jars.
 */
public final class DatabaseManager {

    private final LegendaryPlugin plugin;
    private volatile Connection connection;
    private volatile boolean mysql;
    private final ReentrantLock writeLock = new ReentrantLock();

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
                    String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&autoReconnect=true";
                    connection = DriverManager.getConnection(url, user, password);
                } else {
                    File dbFile = new File(plugin.getDataFolder(), cfg.getString("anticheat.database.file-name", "legendary.db"));
                    plugin.getDataFolder().mkdirs();
                    try {
                        // Paper/Spigot bundle the SQLite JDBC driver for plugin use and it
                        // normally self-registers via ServiceLoader, but explicitly loading the
                        // class first is a cheap, harmless safety net against the rare server
                        // build where that auto-registration doesn't happen.
                        Class.forName("org.sqlite.JDBC");
                    } catch (ClassNotFoundException ignored) {
                        // Fall through - DriverManager.getConnection below will fail with a
                        // clearer "no suitable driver" error if it's genuinely missing.
                    }
                    connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                }
                createSchema();
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
            // Full violation history (not just bans) - lets /legendaryac history show what
            // happened even after in-memory VL has decayed back to 0 or the server restarted.
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
            statement.execute("CREATE INDEX IF NOT EXISTS idx_violations_player ON anticheat_violations(player_uuid, created_at)");
        }
    }

    public record ViolationRecord(String playerName, String checkName, double weight, double vlAfter, String detail, long createdAt) {}

    /** Persists one violation row. Fire-and-forget, always off the main thread. */
    public void recordViolationAsync(String uuid, String name, String check, double weight, double vlAfter, String detail) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Connection c = connection;
            if (c == null) return;
            String sql = "INSERT INTO anticheat_violations (player_uuid, player_name, check_name, weight, vl_after, detail, created_at) VALUES (?,?,?,?,?,?,?)";
            writeLock.lock();
            try (var stmt = c.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                stmt.setString(2, name);
                stmt.setString(3, check);
                stmt.setDouble(4, weight);
                stmt.setDouble(5, vlAfter);
                stmt.setString(6, detail);
                stmt.setLong(7, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to record violation: " + e.getMessage());
            } finally {
                writeLock.unlock();
            }
        });
    }

    /** Reads the most recent violation rows for a player. Callback always runs back on the main thread. */
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

    public boolean isConnected() {
        return connection != null;
    }

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
        Connection c = connection;
        connection = null;
        if (c != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }
}
