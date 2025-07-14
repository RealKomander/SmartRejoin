package org.dristmine.smartRejoin;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
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
     * Fired when a player disconnects from the proxy.
     * We use this to record the server they were on.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(serverConnection -> {
            String serverName = serverConnection.getServerInfo().getName();
            plugin.getPlayerDataManager().setLastServer(player.getUniqueId(), serverName);
        });
    }

    /**
     * Fired when a player is logging in and Velocity needs to decide which server to send them to.
     * This is the perfect place to implement our custom logic.
     */
    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Optional<String> lastServerNameOpt = plugin.getPlayerDataManager().getLastServer(player.getUniqueId());

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
