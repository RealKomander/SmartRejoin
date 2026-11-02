package org.dristmine.smartRejoin;

import com.velocitypowered.api.event.player.*;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerEventHandlerTest {
    SmartRejoin plugin = mock(SmartRejoin.class);
    ProxyServer proxy = mock(ProxyServer.class);
    Player player = mock(Player.class);
    StorageManager storage = mock(StorageManager.class);
    ServerFinder finder = mock(ServerFinder.class);
    PluginManager plugins = mock(PluginManager.class);
    RejoinQueueManager queue = mock(RejoinQueueManager.class);
    RegisteredServer survival = server("survival"), lobby = server("lobby");
    UUID id = UUID.randomUUID();
    PlayerEventHandler handler;

    static RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", 25565)));
        return server;
    }

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(proxy);
        when(proxy.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin("navauth")).thenReturn(Optional.empty());
        when(plugin.getStorageManager()).thenReturn(storage);
        when(plugin.getServerFinder()).thenReturn(finder);
        when(plugin.getRejoinQueueManager()).thenReturn(queue);
        when(player.getUniqueId()).thenReturn(id);
        when(storage.getLastServer(id)).thenReturn(Optional.of("survival"));
        when(finder.findServerFor(player, "survival")).thenReturn(CompletableFuture.completedFuture(Optional.of(survival)));
        handler = new PlayerEventHandler(plugin);
    }

    @Test void fallbackAndDisconnectPreserveSavedTarget() {
        handler.onPlayerChooseInitialServer(new PlayerChooseInitialServerEvent(player, lobby));
        handler.onServerConnected(new ServerConnectedEvent(player, lobby, null));
        ServerConnection connection = mock(ServerConnection.class);
        doReturn(lobby.getServerInfo()).when(connection).getServerInfo();
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        handler.onDisconnect(new DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN));
        verify(storage, never()).setLastServer(any(), any());
    }

    @Test void reachingTargetAllowsFutureServerSwitchesToBeSaved() {
        handler.onPlayerChooseInitialServer(new PlayerChooseInitialServerEvent(player, lobby));
        handler.onServerConnected(new ServerConnectedEvent(player, survival, null));
        handler.onServerConnected(new ServerConnectedEvent(player, lobby, null));
        verify(storage).setLastServer(id, "survival");
        verify(storage).setLastServer(id, "lobby");
    }

    @Test void navAuthWithoutReadyApiKeepsControlAndDoesNotOverwriteStorage() {
        var container = mock(com.velocitypowered.api.plugin.PluginContainer.class);
        when(plugins.getPlugin("navauth")).thenReturn(Optional.of(container));
        doReturn(Optional.empty()).when(container).getInstance();
        assertNull(handler.onPlayerChooseInitialServer(new PlayerChooseInitialServerEvent(player, lobby)));
        handler.onServerConnected(new ServerConnectedEvent(player, lobby, null));
        verifyNoInteractions(finder);
        verify(storage, never()).setLastServer(any(), any());
    }

    @Test void navAuthRoutesOnlyAfterAuthenticatedBackendConnection() {
        var container = mock(com.velocitypowered.api.plugin.PluginContainer.class);
        when(plugins.getPlugin("navauth")).thenReturn(Optional.of(container));
        doReturn(Optional.of(new pl.spcode.navauth.api.NavAuthAPI())).when(container).getInstance();
        pl.spcode.navauth.api.NavAuthAPI.authenticated = false;
        assertNull(handler.onPlayerChooseInitialServer(new PlayerChooseInitialServerEvent(player, lobby)));
        handler.onServerConnected(new ServerConnectedEvent(player, lobby, null));
        handler.onServerPostConnect(new ServerPostConnectEvent(player, null));
        verifyNoInteractions(finder);
        verify(storage, never()).setLastServer(any(), any());
        pl.spcode.navauth.api.NavAuthAPI.authenticated = true;
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result success = mock(ConnectionRequestBuilder.Result.class);
        when(success.isSuccessful()).thenReturn(true);
        when(player.createConnectionRequest(survival)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(success));
        handler.onServerConnected(new ServerConnectedEvent(player, lobby, null));
        handler.onServerPostConnect(new ServerPostConnectEvent(player, null));
        verify(request).connect();
        verify(storage, never()).setLastServer(id, "lobby");
    }

    @Test void fallbackGetsRealAttemptThenWaitsForStartupWithoutReplacingOriginalQueue() {
        ConfigManager config = mock(ConfigManager.class);
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getInt("rejoin_queue.fallback_wait_timeout", 200)).thenReturn(200);
        when(proxy.getServer("survival")).thenReturn(Optional.of(survival));
        when(finder.findFallbackServer()).thenReturn(CompletableFuture.completedFuture(Optional.of(lobby)));
        handler.onPlayerChooseInitialServer(new PlayerChooseInitialServerEvent(player, lobby));
        var failure = new KickedFromServerEvent(player, survival, null, true,
                KickedFromServerEvent.DisconnectPlayer.create(net.kyori.adventure.text.Component.empty()));
        handler.onKickedFromServer(failure);
        assertEquals(lobby, ((KickedFromServerEvent.RedirectPlayer) failure.getResult()).getServer());
        verify(lobby, never()).ping();
        when(lobby.ping()).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        var fallbackFailure = new KickedFromServerEvent(player, lobby, null, true,
                KickedFromServerEvent.DisconnectPlayer.create(net.kyori.adventure.text.Component.empty()));
        handler.onKickedFromServer(fallbackFailure);
        assertEquals(lobby, ((KickedFromServerEvent.RedirectPlayer) fallbackFailure.getResult()).getServer());
        verify(queue, times(2)).queuePlayer(player, survival);
        verify(queue, never()).queuePlayer(player, lobby);
    }
}
