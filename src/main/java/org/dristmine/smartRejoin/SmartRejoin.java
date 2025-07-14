package org.dristmine.smartRejoin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "smartrejoin",
        name = "SmartRejoin",
        version = "1.0.0",
        description = "Smartly reconnects players to servers based on their last location.",
        authors = {"Gemini"}
)
public class SmartRejoin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private ServerFinder serverFinder;

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
        this.playerDataManager = new PlayerDataManager(this, dataDirectory, logger);
        this.serverFinder = new ServerFinder(this);

        server.getEventManager().register(this, new PlayerEventHandler(this));

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("smartrejoinreload")
                .aliases("srr")
                .build();
        commandManager.register(meta, new ReloadCommand(this));

        logger.info("SmartRejoin has been enabled successfully.");
    }

    public void reload() {
        logger.info("Reloading SmartRejoin configuration...");
        if (configManager.loadConfig()) {
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

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public ServerFinder getServerFinder() {
        return serverFinder;
    }
}
