package org.dristmine.smartRejoin.storage;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * YAML-based storage backend for player data.
 * Reads from and writes to data.yml file.
 */
public class YAMLStorageBackend implements StorageBackend {

    private final Logger logger;
    private final File dataFile;
    private final Yaml yaml;
    private final Map<UUID, String> lastServerMap = new ConcurrentHashMap<>();
    private final ReentrantLock fileLock = new ReentrantLock();

    public YAMLStorageBackend(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataFile = new File(dataDirectory.toFile(), "data.yml");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);
    }

    @Override
    public void setLastServer(UUID playerUuid, String serverName) {
        lastServerMap.put(playerUuid, serverName);
        saveData();
    }

    @Override
    public Optional<String> getLastServer(UUID playerUuid) {
        return Optional.ofNullable(lastServerMap.get(playerUuid));
    }

    @Override
    public void initialize() throws Exception {
        loadData();
    }

    @Override
    public void shutdown() {
        saveData();
    }

    @Override
    public String getBackendName() {
        return "YAML";
    }

    @Override
    public boolean supportsMigration() {
        return false; // YAML is the source, not a destination for migration
    }

    @Override
    public int migrateFrom(StorageBackend source, MigrationCallback callback) throws Exception {
        throw new UnsupportedOperationException("YAML backend does not support migration as destination");
    }

    @SuppressWarnings("unchecked")
    private void loadData() {
        if (!dataFile.exists()) {
            logger.info("Player data file (data.yml) not found. A new one will be created.");
            return;
        }
        try (InputStream is = new FileInputStream(dataFile)) {
            Map<String, String> rawData = yaml.load(is);
            if (rawData != null) {
                rawData.forEach((key, value) -> {
                    try {
                        lastServerMap.put(UUID.fromString(key), value);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Skipping invalid UUID in data.yml: " + key);
                    }
                });
            }
            logger.info("Successfully loaded " + lastServerMap.size() + " player data entries.");
        } catch (IOException | YAMLException e) {
            logger.error("Could not load player data from data.yml.", e);
        }
    }

    private void saveData() {
        fileLock.lock();
        try {
            // Write to a temporary file first, then rename for atomic operation
            File tempFile = new File(dataFile.getParentFile(), dataFile.getName() + ".tmp");
            try (Writer writer = new FileWriter(tempFile)) {
                // Convert UUID keys to String for YAML serialization
                Map<String, String> dataToSave = new LinkedHashMap<>();
                lastServerMap.forEach((uuid, server) -> dataToSave.put(uuid.toString(), server));
                yaml.dump(dataToSave, writer);
            }
            // Atomic rename - on Windows, we need to delete target first
            if (dataFile.exists()) {
                dataFile.delete();
            }
            if (!tempFile.renameTo(dataFile)) {
                throw new IOException("Failed to rename temporary file to " + dataFile.getAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("Could not save player data to data.yml.", e);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Get the data file for this backend.
     * Useful for migration purposes.
     * @return The data file
     */
    public File getDataFile() {
        return dataFile;
    }

    /**
     * Get all player data entries.
     * Useful for migration purposes.
     * @return Map of player UUID to server name
     */
    public Map<UUID, String> getAllData() {
        return new ConcurrentHashMap<>(lastServerMap);
    }
}
