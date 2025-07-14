package org.dristmine.smartRejoin;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ConfigManager {

    private final SmartRejoin plugin;
    private final Path dataDirectory;
    private final Logger logger;
    private Map<String, Object> config;

    public ConfigManager(SmartRejoin plugin, Path dataDirectory, Logger logger) {
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public boolean loadConfig() {
        try {
            File configFile = new File(dataDirectory.toFile(), "config.yml");
            if (!configFile.exists()) {
                plugin.logInfo("Configuration file not found, creating a default one...");
                try (InputStream in = SmartRejoin.class.getResourceAsStream("/config.yml")) {
                    if (in == null) {
                        throw new IOException("Default config.yml not found in plugin JAR.");
                    }
                    Files.createDirectories(dataDirectory);
                    Files.copy(in, configFile.toPath());
                }
            }

            Yaml yaml = new Yaml();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                this.config = yaml.load(fis);
                logger.info("Configuration loaded successfully.");
                return true;
            }
        } catch (IOException e) {
            logger.error("Could not load or create config.yml!", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSection(String path) {
        try {
            return (Map<String, Object>) get(path, Collections.emptyMap());
        } catch (ClassCastException e) {
            logger.warn("Configuration section '{}' is not a valid map.", path);
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> getRules() {
        try {
            // Get the 'rules' section from the config.
            Object rawRules = get("rules", Collections.emptyMap());

            // CORRECTED: If the 'rules' section is null (e.g., 'rules:' with no content in YAML)
            // or not a map, return an empty map to prevent NullPointerException.
            if (!(rawRules instanceof Map)) {
                return Collections.emptyMap();
            }

            return (Map<String, Map<String, Object>>) rawRules;
        } catch (ClassCastException e) {
            logger.warn("Configuration section 'rules' is not a valid map of rules.");
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    public List<String> getList(String path) {
        try {
            return (List<String>) get(path, Collections.emptyList());
        } catch (ClassCastException e) {
            logger.warn("Configuration value at '{}' is not a valid list.", path);
            return Collections.emptyList();
        }
    }

    public String getString(String path, String def) {
        return get(path, def).toString();
    }

    public boolean getBoolean(String path, boolean def) {
        Object val = get(path, def);
        if (val instanceof Boolean) {
            return (Boolean) val;
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private Object get(String path, Object def) {
        if (config == null) return def;
        Map<String, Object> current = config;
        String[] keys = path.split("\\.");
        for (int i = 0; i < keys.length; i++) {
            if (i == keys.length - 1) {
                // Use get() instead of getOrDefault() to correctly handle null values for existing keys.
                if (!current.containsKey(keys[i])) {
                    return def;
                }
                return current.get(keys[i]);
            }
            Object next = current.get(keys[i]);
            if (!(next instanceof Map)) {
                return def;
            }
            current = (Map<String, Object>) next;
        }
        return def;
    }
}
