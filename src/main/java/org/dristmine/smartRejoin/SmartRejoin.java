package org.dristmine.smartRejoin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Plugin(
        id = "smartrejoin",
        name = "SmartRejoin",
        version = "1.2",
        description = "Smartly reconnects players to servers based on their last location.",
        authors = {"Gemini", "Z.ai"}
)
public class SmartRejoin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ConfigManager configManager;
    private StorageManager storageManager;
    private ServerFinder serverFinder;
    private RejoinQueueManager rejoinQueueManager;
    private String leaveQueueCommandName = null;

    // Track players who were routed via modded routing
    // This prevents their last server from being overwritten in data.yml
    private final Set<UUID> playersRoutedViaModdedRouting = new HashSet<>();

    @Inject
    public SmartRejoin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        this.configManager = new ConfigManager(this, dataDirectory, logger);
        if (!configManager.loadConfig()) {
            logger.error("Failed to load configuration. The plugin will not function correctly.");
            return;
        }

        // Initialize storage manager
        try {
            this.storageManager = new StorageManager(this, dataDirectory, logger);
            storageManager.initialize();
        } catch (Exception e) {
            logger.error("Failed to initialize storage manager. The plugin will not function correctly.", e);
            return;
        }

        this.serverFinder = new ServerFinder(this);

        server.getEventManager().register(this, new PlayerEventHandler(this));

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("smartrejoinreload")
                .aliases("srr")
                .build();
        commandManager.register(meta, new ReloadCommand(this));

        // Initialize RejoinQueueManager if enabled
        if (configManager.getRejoinQueueEnabled()) {
            this.rejoinQueueManager = new RejoinQueueManager(this);
            this.rejoinQueueManager.start();

            // Register leave-queue command if enabled
            if (configManager.getRejoinQueueLeaveCommandEnabled()) {
                this.leaveQueueCommandName = configManager.getRejoinQueueLeaveCommandName();
                CommandMeta leaveMeta = commandManager.metaBuilder(leaveQueueCommandName)
                        .build();
                commandManager.register(leaveMeta, new LeaveQueueCommand(this));
            }
        }

        logger.info("SmartRejoin has been enabled successfully.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        // Stop RejoinQueueManager if running
        if (rejoinQueueManager != null) {
            rejoinQueueManager.stop();
        }

        // Unregister leave-queue command if registered
        if (leaveQueueCommandName != null) {
            server.getCommandManager().unregister(leaveQueueCommandName);
        }

        // Shutdown storage manager
        if (storageManager != null) {
            storageManager.shutdown();
        }

        logger.info("SmartRejoin has been disabled.");
    }

    public void reload() {
        logger.info("Reloading SmartRejoin configuration...");

        // Stop existing RejoinQueueManager if running
        if (rejoinQueueManager != null) {
            rejoinQueueManager.stop();
            rejoinQueueManager = null;
        }

        // Unregister existing leave-queue command if registered
        if (leaveQueueCommandName != null) {
            server.getCommandManager().unregister(leaveQueueCommandName);
            leaveQueueCommandName = null;
        }

        if (configManager.loadConfig()) {
            // Note: Storage backend cannot be changed without server restart
            // This is intentional to prevent data loss during runtime
            logger.info("Storage backend remains: " + storageManager.getBackendName());

            // Start RejoinQueueManager if enabled
            if (configManager.getRejoinQueueEnabled()) {
                this.rejoinQueueManager = new RejoinQueueManager(this);
                this.rejoinQueueManager.start();

                // Register leave-queue command if enabled
                if (configManager.getRejoinQueueLeaveCommandEnabled()) {
                    this.leaveQueueCommandName = configManager.getRejoinQueueLeaveCommandName();
                    CommandManager commandManager = server.getCommandManager();
                    CommandMeta leaveMeta = commandManager.metaBuilder(leaveQueueCommandName)
                            .build();
                    commandManager.register(leaveMeta, new LeaveQueueCommand(this));
                }
            }

            logger.info("Configuration reloaded successfully.");
        } else {
            logger.error("Failed to reload configuration. Please check the console for errors.");
        }
    }

    // --- Logging Wrappers ---
    public void logInfo(String message) {
        if (configManager.getBoolean("settings.logging_enabled", true)) {
            logger.info(message);
        }
    }

    public void logWarn(String message) {
        if (configManager.getBoolean("settings.logging_enabled", true)) {
            logger.warn(message);
        }
    }

    // --- Getters ---
    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    /**
     * @deprecated Use {@link #getStorageManager()} instead.
     */
    @Deprecated
    public PlayerDataManager getPlayerDataManager() {
        logger.warn("getPlayerDataManager() is deprecated. Use getStorageManager() instead.");
        return null; // Return null to force code updates
    }

    public ServerFinder getServerFinder() {
        return serverFinder;
    }

    public RejoinQueueManager getRejoinQueueManager() {
        return rejoinQueueManager;
    }

    // --- Modded Routing Tracking ---

    /**
     * Mark a player as being routed via modded routing.
     * This will prevent their last server from being updated in data.yml when they disconnect.
     */
    public void markPlayerRoutedViaModdedRouting(UUID playerUuid) {
        playersRoutedViaModdedRouting.add(playerUuid);
    }

    /**
     * Check if a player was routed via modded routing.
     */
    public boolean isPlayerRoutedViaModdedRouting(UUID playerUuid) {
        return playersRoutedViaModdedRouting.contains(playerUuid);
    }

    /**
     * Remove a player from the modded routing tracking set.
     * This should be called when the player connects to a different server.
     */
    public void removePlayerFromModdedRoutingTracking(UUID playerUuid) {
        playersRoutedViaModdedRouting.remove(playerUuid);
    }
}
