package org.dristmine.smartRejoin;

import org.dristmine.smartRejoin.storage.SQLiteStorageBackend;
import org.dristmine.smartRejoin.storage.StorageBackend;
import org.dristmine.smartRejoin.storage.YAMLStorageBackend;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages storage backend selection and operations.
 * Handles automatic migration between storage backends.
 */
public class StorageManager {

    private final SmartRejoin plugin;
    private final Logger logger;
    private final Path dataDirectory;
    private final ConfigManager config;

    private StorageBackend currentBackend;
    private boolean migrationNeeded = false;

    public StorageManager(SmartRejoin plugin, Path dataDirectory, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.config = plugin.getConfigManager();
    }

    /**
     * Initialize the storage manager and detect migration needs.
     */
    public void initialize() throws Exception {
        String storageType = config.getString("storage.type", "yaml").toLowerCase();

        logger.info("Initializing storage manager with backend: " + storageType);

        // Check if this is a new setup or needs migration
        YAMLStorageBackend yamlBackend = new YAMLStorageBackend(logger, dataDirectory);
        boolean yamlExists = yamlBackend.getDataFile().exists();

        // Initialize YAML backend if it exists (needed for migration)
        if (yamlExists) {
            yamlBackend.initialize();
        }

        SQLiteStorageBackend sqliteBackend = new SQLiteStorageBackend(logger, dataDirectory);
        boolean sqliteExists = sqliteBackend.getDatabaseFile().exists();

        // Determine which backend to use and if migration is needed
        StorageBackend targetBackend;

        if ("sqlite".equals(storageType)) {
            targetBackend = sqliteBackend;

            // Check if we need to migrate from YAML
            if (yamlExists && !sqliteExists) {
                migrationNeeded = true;
                logger.info("Migration from YAML to SQLite will be performed.");
            } else if (yamlExists && sqliteExists) {
                logger.warn("Both YAML and SQLite databases exist. Using SQLite as configured.");
                logger.warn("YAML data file will be kept as backup: " + yamlBackend.getDataFile().getName() + ".bak");
            }
        } else {
            // Default to YAML
            targetBackend = yamlBackend;

            if (sqliteExists && !yamlExists) {
                logger.warn("SQLite database exists but YAML is configured. Consider migrating to SQLite for better performance.");
            } else if (sqliteExists && yamlExists) {
                logger.warn("Both YAML and SQLite databases exist. Using YAML as configured.");
            }
        }

        // Initialize the chosen backend
        targetBackend.initialize();
        this.currentBackend = targetBackend;

        logger.info("Storage backend initialized: " + currentBackend.getBackendName());

        // Perform migration if needed
        if (migrationNeeded) {
            performMigration(yamlBackend, (SQLiteStorageBackend) targetBackend);
        }
    }

    /**
     * Perform migration from YAML to SQLite.
     */
    private void performMigration(YAMLStorageBackend source, SQLiteStorageBackend target) {
        logger.info("Starting migration from YAML to SQLite...");

        try {
            // Perform the migration
            int migrated = target.migrateFrom(source, (current, total, message) -> {
                logger.info(message);
            });

            logger.info("Migration completed successfully: " + migrated + " players migrated to SQLite.");

            // Rename the old YAML file to indicate it's been migrated
            File yamlBackup = new File(source.getDataFile().getAbsolutePath() + ".migrated");
            if (source.getDataFile().renameTo(yamlBackup)) {
                logger.info("Original YAML file renamed to: " + yamlBackup.getName());
            } else {
                logger.warn("Could not rename YAML file after migration. It will be kept as backup.");
            }

            logger.info("You can now delete the old YAML backup file once you verify the SQLite migration is working correctly.");

        } catch (Exception e) {
            logger.error("Migration failed! Please check the error logs.", e);
            logger.error("The original YAML file has been preserved at: " + source.getDataFile().getAbsolutePath());
            logger.error("Please fix the issue and try again, or continue using YAML storage by setting storage.type to 'yaml' in config.yml.");

            // Fallback to YAML if migration fails
            try {
                source.initialize();
                this.currentBackend = source;
                logger.info("Falling back to YAML storage due to migration failure.");
            } catch (Exception fallbackException) {
                logger.error("Failed to fallback to YAML storage!", fallbackException);
            }
        }
    }

    /**
     * Shutdown the storage manager.
     */
    public void shutdown() {
        if (currentBackend != null) {
            currentBackend.shutdown();
        }
    }

    /**
     * Set the last server a player was on.
     */
    public void setLastServer(UUID playerUuid, String serverName) {
        currentBackend.setLastServer(playerUuid, serverName);
    }

    /**
     * Get the last server a player was on.
     */
    public Optional<String> getLastServer(UUID playerUuid) {
        return currentBackend.getLastServer(playerUuid);
    }

    /**
     * Get the current storage backend.
     */
    public StorageBackend getCurrentBackend() {
        return currentBackend;
    }

    /**
     * Get the name of the current storage backend.
     */
    public String getBackendName() {
        return currentBackend != null ? currentBackend.getBackendName() : "Unknown";
    }

    /**
     * Check if a migration was performed during initialization.
     */
    public boolean wasMigrationPerformed() {
        return migrationNeeded;
    }

    /**
     * Get the SQLite backend for administrative operations.
     * Only available if the current backend is SQLite.
     */
    public Optional<SQLiteStorageBackend> getSQLiteBackend() {
        if (currentBackend instanceof SQLiteStorageBackend) {
            return Optional.of((SQLiteStorageBackend) currentBackend);
        }
        return Optional.empty();
    }
}
