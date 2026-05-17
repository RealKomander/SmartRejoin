package org.dristmine.smartRejoin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class PlayerEventHandler {

    private final SmartRejoin plugin;

    public PlayerEventHandler(SmartRejoin plugin) {
        this.plugin = plugin;
    }

    /**
     * Fired when a player successfully connects to a server.
     * We use this to track player connections for the rejoin queue.
     * Also clears the modded routing flag if the player connects to a different server.
     * And updates the last server when a player intentionally switches servers.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();

        // Track connections for rejoin queue
        if (plugin.getRejoinQueueManager() != null) {
            plugin.getRejoinQueueManager().onPlayerConnectedToServer(player.getUniqueId(), serverName);
        }

        // Clear modded routing flag if player connects to a different server
        if (plugin.isPlayerRoutedViaModdedRouting(player.getUniqueId())) {
            String moddedRoutingTarget = plugin.getConfigManager().getModdedRoutingTargetServer();
            if (!serverName.equalsIgnoreCase(moddedRoutingTarget)) {
                // Player connected to a different server, clear the modded routing flag
                plugin.removePlayerFromModdedRoutingTracking(player.getUniqueId());
            }
        }

        // Update last server when player intentionally switches servers
        // This fixes the bug where players are sent back to old servers after restarts
        if (!plugin.isPlayerRoutedViaModdedRouting(player.getUniqueId())) {
            // Only update last server if the player wasn't routed via modded routing
            plugin.getStorageManager().setLastServer(player.getUniqueId(), serverName);
            plugin.logInfo("Player " + player.getUsername() + " connected to '" + serverName + "'. Updated last server.");
        }
    }

    /**
     * Fired when a player disconnects from the proxy.
     * We use this to record the server they were on.
     * However, if the player was routed via modded routing, we skip updating the storage
     * to preserve their original last server.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(serverConnection -> {
            String serverName = serverConnection.getServerInfo().getName();

            // Check if player was routed via modded routing and is disconnecting from the modded routing target
            if (plugin.isPlayerRoutedViaModdedRouting(player.getUniqueId())) {
                String moddedRoutingTarget = plugin.getConfigManager().getModdedRoutingTargetServer();
                if (serverName.equalsIgnoreCase(moddedRoutingTarget)) {
                    // Skip updating storage to preserve the player's original last server
                    plugin.logInfo("Player " + player.getUsername() + " is disconnecting from modded routing target. Skipping storage update.");
                } else {
                    // Player was marked for modded routing but connected to a different server, record it
                    plugin.getStorageManager().setLastServer(player.getUniqueId(), serverName);
                }
            } else {
                // Normal behavior: record the last server
                plugin.getStorageManager().setLastServer(player.getUniqueId(), serverName);
            }

            // Notify rejoin queue manager of the disconnect
            if (plugin.getRejoinQueueManager() != null) {
                plugin.getRejoinQueueManager().onPlayerDisconnectedFromServer(player.getUniqueId(), serverName);
            }
        });

        // Remove player from rejoin queues if enabled
        if (plugin.getRejoinQueueManager() != null) {
            plugin.getRejoinQueueManager().removePlayerFromQueues(player.getUniqueId());
        }

        // Always clear the modded routing flag on disconnect
        plugin.removePlayerFromModdedRoutingTracking(player.getUniqueId());
    }

    /**
     * Fired when a player is logging in and Velocity needs to decide which server to send them to.
     * This is the perfect place to implement our custom logic.
     */
    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<String> lastServerNameOpt = plugin.getStorageManager().getLastServer(player.getUniqueId());

        CompletableFuture<Optional<RegisteredServer>> futureServer;

        if (lastServerNameOpt.isPresent()) {
            String lastServerName = lastServerNameOpt.get();
            plugin.logInfo("Player " + player.getUsername() + " is rejoining. Last seen on: " + lastServerName);
            futureServer = plugin.getServerFinder().findServerFor(player, lastServerName);
        } else {
            plugin.logInfo("Player " + player.getUsername() + " has no previous server data. Using fallback logic.");
            futureServer = plugin.getServerFinder().findFallbackServer();
        }

        try {
            futureServer.join().ifPresent(event::setInitialServer);
        } catch (Exception e) {
            plugin.getLogger().error("An exception occurred while finding an initial server for " + player.getUsername(), e);
        }
    }
}
