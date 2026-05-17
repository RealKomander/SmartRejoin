package org.dristmine.smartRejoin.storage;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-based storage backend for player data.
 * Provides better performance for large player counts compared to YAML.
 */
public class SQLiteStorageBackend implements StorageBackend {

    private final Logger logger;
    private final Path dataDirectory;
    private final File databaseFile;
    private Connection connection;
    private boolean initialized = false;

    public SQLiteStorageBackend(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.databaseFile = new File(dataDirectory.toFile(), "player_data.db");
    }

    @Override
    public void setLastServer(UUID playerUuid, String serverName) {
        ensureInitialized();
        String sql = "INSERT OR REPLACE INTO player_data (player_uuid, last_server, last_updated) VALUES (?, ?, ?)";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, serverName);
            stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            stmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to set last server for player " + playerUuid, e);
        }
    }

    @Override
    public Optional<String> getLastServer(UUID playerUuid) {
        ensureInitialized();
        String sql = "SELECT last_server FROM player_data WHERE player_uuid = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return Optional.of(rs.getString("last_server"));
            }
        } catch (SQLException e) {
            logger.error("Failed to get last server for player " + playerUuid, e);
        }

        return Optional.empty();
    }

    @Override
    public void initialize() throws Exception {
        if (initialized) {
            return;
        }

        try {
            // Create the data directory if it doesn't exist
            Files.createDirectories(dataDirectory);

            // Create database connection
            String url = "jdbc:sqlite:" + databaseFile.getAbsolutePath();

            // Explicitly load the SQLite driver (required when using shadow plugin)
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                throw new SQLException("SQLite JDBC driver not found. Please ensure the plugin is properly built with the shadow plugin.", e);
            }

            connection = DriverManager.getConnection(url);

            // Create table if it doesn't exist
            String createTableSQL = """
                CREATE TABLE IF NOT EXISTS player_data (
                    player_uuid TEXT PRIMARY KEY,
                    last_server TEXT NOT NULL,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """;

            try (Statement stmt = connection.createStatement()) {
                stmt.execute(createTableSQL);
            }

            // Create index on last_updated for potential cleanup queries
            String createIndexSQL = "CREATE INDEX IF NOT EXISTS idx_last_updated ON player_data(last_updated)";
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(createIndexSQL);
            }

            // Enable WAL mode for better concurrent access
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }

            initialized = true;
            logger.info("SQLite storage backend initialized successfully at: " + databaseFile.getAbsolutePath());
        } catch (SQLException e) {
            logger.error("Failed to initialize SQLite storage backend", e);
            throw e;
        }
    }

    @Override
    public void shutdown() {
        if (connection != null) {
            try {
                // Close WAL file to ensure data integrity
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }

                connection.close();
                logger.info("SQLite storage backend shut down successfully.");
            } catch (SQLException e) {
                logger.error("Failed to close SQLite connection", e);
            }
        }
    }

    @Override
    public String getBackendName() {
        return "SQLite";
    }

    @Override
    public boolean supportsMigration() {
        return true;
    }

    @Override
    public int migrateFrom(StorageBackend source, MigrationCallback callback) throws Exception {
        if (source == this) {
            throw new IllegalArgumentException("Cannot migrate from the same backend");
        }

        logger.info("Starting migration from " + source.getBackendName() + " to SQLite...");
        callback.onProgress(0, 0, "Starting migration...");

        // Get all data from source
        if (source instanceof YAMLStorageBackend yamlBackend) {
            var allData = yamlBackend.getAllData();
            int total = allData.size();
            int count = 0;

            String sql = "INSERT OR REPLACE INTO player_data (player_uuid, last_server, last_updated) VALUES (?, ?, ?)";

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                for (Map.Entry<UUID, String> entry : allData.entrySet()) {
                    stmt.setString(1, entry.getKey().toString());
                    stmt.setString(2, entry.getValue());
                    stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                    stmt.executeUpdate();

                    count++;
                    if (count % 100 == 0 || count == total) {
                        callback.onProgress(count, total, "Migrated " + count + " of " + total + " players...");
                    }
                }
            }

            logger.info("Migration completed successfully: " + count + " players migrated.");
            callback.onProgress(count, count, "Migration completed: " + count + " players.");

            return count;
        }

        throw new UnsupportedOperationException("Migration from " + source.getBackendName() + " is not supported");
    }

    /**
     * Get the total number of player records in the database.
     * @return The number of records
     */
    public int getRecordCount() {
        ensureInitialized();
        String sql = "SELECT COUNT(*) FROM player_data";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.error("Failed to get record count", e);
        }

        return 0;
    }

    /**
     * Optimize the database. Useful for maintenance.
     */
    public void optimize() {
        ensureInitialized();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("VACUUM");
            stmt.execute("ANALYZE");
            logger.info("SQLite database optimized.");
        } catch (SQLException e) {
            logger.error("Failed to optimize database", e);
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("Storage backend has not been initialized");
        }
    }

    /**
     * Get the database file.
     * Useful for migration purposes.
     * @return The database file
     */
    public File getDatabaseFile() {
        return databaseFile;
    }
}
