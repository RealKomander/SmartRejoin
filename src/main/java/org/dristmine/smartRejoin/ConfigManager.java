package org.dristmine.smartRejoin;

import org.slf4j.Logger;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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

            // Load default config from JAR
            Map<String, Object> defaultConfig;
            try (InputStream in = SmartRejoin.class.getResourceAsStream("/config.yml")) {
                if (in == null) {
                    throw new IOException("Default config.yml not found in plugin JAR.");
                }
                defaultConfig = yaml.load(in);
            }

            // Load current user config
            Map<String, Object> userConfig;
            try (FileInputStream fis = new FileInputStream(configFile)) {
                userConfig = yaml.load(fis);
            }

            // If user config is null, use default config
            if (userConfig == null) {
                logger.info("User config is empty, using default configuration.");
                this.config = defaultConfig != null ? defaultConfig : new LinkedHashMap<>();
                saveConfig(configFile);
                logger.info("Configuration loaded successfully.");
                return true;
            }

            // Merge missing entries from default config
            Map<String, Object> mergedConfig = mergeConfigs(defaultConfig != null ? defaultConfig : new LinkedHashMap<>(), userConfig);

            // Check if any entries were added
            if (mergedConfig.size() != userConfig.size() || !configsEqual(defaultConfig != null ? defaultConfig : new LinkedHashMap<>(), extractStructure(mergedConfig))) {
                logger.info("Updating configuration with missing entries from default config...");
                this.config = mergedConfig;
                saveConfig(configFile);
                logger.info("Configuration updated and saved.");
            } else {
                this.config = userConfig;
            }

            logger.info("Configuration loaded successfully.");
            return true;
        } catch (IOException e) {
            logger.error("Could not load or create config.yml!", e);
            return false;
        }
    }

    /**
     * Recursively merge two config maps, preserving user values and adding missing defaults.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeConfigs(Map<String, Object> defaults, Map<String, Object> userConfig) {
        Map<String, Object> merged = new LinkedHashMap<>(userConfig);

        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object defaultValue = entry.getValue();
            Object userValue = merged.get(key);

            if (userValue == null) {
                // Key doesn't exist in user config, add it with default value
                merged.put(key, deepCopy(defaultValue));
                logger.info("Added missing config entry: " + key);
            } else if (defaultValue instanceof Map && userValue instanceof Map) {
                // Both are maps, recursively merge them
                Map<String, Object> mergedSection = mergeConfigs((Map<String, Object>) defaultValue, (Map<String, Object>) userValue);
                merged.put(key, mergedSection);
            }
            // If userValue exists and is not a map or the types don't match, keep user's value
        }

        return merged;
    }

    /**
     * Check if two config structures are equivalent (ignoring leaf values).
     */
    @SuppressWarnings("unchecked")
    private boolean configsEqual(Map<String, Object> config1, Map<String, Object> config2) {
        if (config1.size() != config2.size()) {
            return false;
        }

        for (Map.Entry<String, Object> entry : config1.entrySet()) {
            String key = entry.getKey();
            Object value1 = entry.getValue();
            Object value2 = config2.get(key);

            if (value2 == null) {
                return false;
            }

            if (value1 instanceof Map && value2 instanceof Map) {
                if (!configsEqual((Map<String, Object>) value1, (Map<String, Object>) value2)) {
                    return false;
                }
            } else if (!(value1 instanceof Map) && !(value2 instanceof Map)) {
                // Both are leaf values, check key existence (not value equality)
                continue;
            } else {
                return false;
            }
        }

        return true;
    }

    /**
     * Extract just the structure (keys) from a config, ignoring leaf values.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractStructure(Map<String, Object> config) {
        Map<String, Object> structure = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                structure.put(key, extractStructure((Map<String, Object>) value));
            } else {
                structure.put(key, null);
            }
        }
        return structure;
    }

    /**
     * Deep copy an object to avoid reference issues.
     */
    @SuppressWarnings("unchecked")
    private Object deepCopy(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) obj).entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return copy;
        } else if (obj instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) obj) {
                copy.add(deepCopy(item));
            }
            return copy;
        } else {
            return obj;
        }
    }

    /**
     * Save the current config to file.
     */
    private void saveConfig(File configFile) throws IOException {
        // Write to a temporary file first, then rename for atomic operation
        File tempFile = new File(configFile.getParentFile(), configFile.getName() + ".tmp");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);

        Yaml yaml = new Yaml(options);

        try (Writer writer = new FileWriter(tempFile)) {
            yaml.dump(config, writer);
        }

        // Atomic rename - on Windows, we need to delete target first
        if (configFile.exists()) {
            configFile.delete();
        }
        if (!tempFile.renameTo(configFile)) {
            throw new IOException("Failed to rename temporary file to " + configFile.getAbsolutePath());
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

    public int getInt(String path, int def) {
        Object val = get(path, def);
        if (val instanceof Number) {
            return ((Number) val).intValue();
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

    // --- Rejoin Queue Config Methods ---

    /**
     * Check if the rejoin queue feature is enabled.
     * Returns false if the section is missing or enabled is not set (backwards compatible with v1.1).
     */
    public boolean getRejoinQueueEnabled() {
        return getBoolean("rejoin_queue.enabled", false);
    }

    /**
     * Get the monitor interval in ticks.
     * Returns 100 (5 seconds) as default if not configured.
     */
    public int getRejoinQueueMonitorInterval() {
        return getInt("rejoin_queue.monitor_interval", 100);
    }

    /**
     * Get the initial delay before sending queued players after server comes back up.
     * Returns 60 (3 seconds) as default if not configured.
     */
    public int getRejoinQueueInitialDelay() {
        return getInt("rejoin_queue.rejoin_initial_delay", 60);
    }

    /**
     * Get the cooldown between sending each queued player.
     * Returns 10 (0.5 seconds) as default if not configured.
     */
    public int getRejoinQueueCooldown() {
        return getInt("rejoin_queue.rejoin_cooldown", 10);
    }

    /**
     * Get the whitelist type for server filtering.
     * Returns "EQUALS" as default if not configured.
     */
    public String getRejoinQueueWhitelistType() {
        return getString("rejoin_queue.whitelist.type", "EQUALS").toUpperCase();
    }

    /**
     * Get the whitelist list of server names.
     * Returns empty list if not configured.
     */
    public List<String> getRejoinQueueWhitelistList() {
        return getList("rejoin_queue.whitelist.list");
    }

    /**
     * Get the wait timeout in ticks.
     * Returns 0 if not configured (disabled).
     */
    public int getRejoinQueueWaitTimeout() {
        return getInt("rejoin_queue.wait_timeout", 0);
    }

    /**
     * Check if actionbar messages are enabled.
     */
    public boolean getRejoinQueueActionbarEnabled() {
        return getBoolean("rejoin_queue.actionbar.enabled", true);
    }

    /**
     * Get the actionbar message format.
     * Returns default format if not configured.
     */
    public String getRejoinQueueActionbarFormat() {
        return getString("rejoin_queue.actionbar.format", "<yellow>Waiting to reconnect to <bold>{server}</bold>... <gray>({position}/{total})</gray>");
    }

    /**
     * Check if leave-queue command is enabled.
     */
    public boolean getRejoinQueueLeaveCommandEnabled() {
        return getBoolean("rejoin_queue.leave_command.enabled", true);
    }

    /**
     * Get the leave-queue command name.
     */
    public String getRejoinQueueLeaveCommandName() {
        return getString("rejoin_queue.leave_command.command", "leavequeue");
    }

    /**
     * Get the message sent when a player leaves the queue.
     */
    public String getRejoinQueueLeaveMessageLeft() {
        return getString("rejoin_queue.leave_command.message_left", "<green>You have left the queue for <bold>{server}</bold>.</green>");
    }

    /**
     * Get the message sent when a player is not in any queue.
     */
    public String getRejoinQueueLeaveMessageNotInQueue() {
        return getString("rejoin_queue.leave_command.message_not_in_queue", "<red>You are not currently in any queue.</red>");
    }

    /**
     * Check if rejoin queue debug mode is enabled.
     */
    public boolean getRejoinQueueDebug() {
        return getBoolean("rejoin_queue.debug", false);
    }

    // --- Modded Routing Config Methods ---

    /**
     * Check if the modded routing feature is enabled.
     * Returns false if not configured.
     */
    public boolean getModdedRoutingEnabled() {
        return getBoolean("modded_routing.enabled", false);
    }

    /**
     * Get the target server name for modded routing.
     * Returns "modded_server" as default if not configured.
     */
    public String getModdedRoutingTargetServer() {
        return getString("modded_routing.target_server", "modded_server");
    }

    /**
     * Get the loader type to match for modded routing.
     * Returns "forge" as default if not configured.
     */
    public String getModdedRoutingLoaderType() {
        return getString("modded_routing.loader_type", "forge");
    }

    /**
     * Get the Minecraft version to match for modded routing.
     * Returns "1.20.1" as default if not configured.
     */
    public String getModdedRoutingMinecraftVersion() {
        return getString("modded_routing.minecraft_version", "1.20.1");
    }
}
