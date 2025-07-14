package org.dristmine.smartRejoin;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages storing and retrieving the last server a player was on.
 * This implementation persists data to data.yml.
 */
public class PlayerDataManager {

    private final Logger logger;
    private final File dataFile;
    private final Yaml yaml;
    private final Map<UUID, String> lastServerMap = new ConcurrentHashMap<>();

    public PlayerDataManager(SmartRejoin plugin, Path dataDirectory, Logger logger) {
        this.logger = logger;
        this.dataFile = new File(dataDirectory.toFile(), "data.yml");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        this.yaml = new Yaml(options);

        loadData();
    }

    public void setLastServer(UUID playerUuid, String serverName) {
        lastServerMap.put(playerUuid, serverName);
        // Save data immediately to prevent data loss on crash/restart.
        // For very high traffic servers, you might want to batch saves,
        // but this is the safest approach.
        saveData();
    }

    public Optional<String> getLastServer(UUID playerUuid) {
        return Optional.ofNullable(lastServerMap.get(playerUuid));
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
        } catch (IOException e) {
            logger.error("Could not load player data from data.yml.", e);
        }
    }

    private void saveData() {
        try (Writer writer = new FileWriter(dataFile)) {
            // Convert UUID keys to String for YAML serialization
            Map<String, String> dataToSave = new LinkedHashMap<>();
            lastServerMap.forEach((uuid, server) -> dataToSave.put(uuid.toString(), server));
            yaml.dump(dataToSave, writer);
        } catch (IOException e) {
            logger.error("Could not save player data to data.yml.", e);
        }
    }
}
