package org.dristmine.smartRejoin.storage;

import java.util.Optional;
import java.util.UUID;

/**
 * Interface for storage backends that handle player data persistence.
 * Implementations can use YAML, SQLite, or other storage mechanisms.
 */
public interface StorageBackend {

    /**
     * Set the last server a player was on.
     * @param playerUuid The player's UUID
     * @param serverName The server name
     */
    void setLastServer(UUID playerUuid, String serverName);

    /**
     * Get the last server a player was on.
     * @param playerUuid The player's UUID
     * @return Optional containing the server name, or empty if not found
     */
    Optional<String> getLastServer(UUID playerUuid);

    /**
     * Initialize the storage backend.
     * This is called once when the plugin starts up.
     * @throws Exception if initialization fails
     */
    void initialize() throws Exception;

    /**
     * Shutdown the storage backend.
     * This is called when the plugin is disabled.
     * Perform any cleanup operations here.
     */
    void shutdown();

    /**
     * Get the name of this storage backend.
     * @return The backend name (e.g., "YAML", "SQLite")
     */
    String getBackendName();

    /**
     * Check if this backend supports data migration from another backend.
     * @return true if migration is supported
     */
    boolean supportsMigration();

    /**
     * Migrate data from another storage backend.
     * @param source The source storage backend
     * @param callback Callback to report migration progress
     * @return The number of records migrated
     * @throws Exception if migration fails
     */
    int migrateFrom(StorageBackend source, MigrationCallback callback) throws Exception;

    /**
     * Callback interface for migration progress reporting.
     */
    @FunctionalInterface
    interface MigrationCallback {
        void onProgress(int current, int total, String message);
    }
}
