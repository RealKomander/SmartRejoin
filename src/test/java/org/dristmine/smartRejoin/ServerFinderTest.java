package org.dristmine.smartRejoin;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServerFinderTest {
    SmartRejoin plugin = mock(SmartRejoin.class);
    ProxyServer proxy = mock(ProxyServer.class);
    ConfigManager config = mock(ConfigManager.class);
    Player player = mock(Player.class);
    ServerFinder finder;

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(proxy);
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getRules()).thenReturn(Map.of());
        when(config.getString("default.rule", "SAME")).thenReturn("SAME");
        finder = new ServerFinder(plugin);
    }

    RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new ServerInfo(name, new InetSocketAddress("localhost", 25565)));
        return server;
    }

    @Test void sameAttemptsOfflineDestinationWithoutPing() {
        RegisteredServer target = server("survival");
        when(proxy.getAllServers()).thenReturn(List.of(target));
        assertEquals(Optional.of(target), finder.findServerFor(player, "survival").join());
        verify(target, never()).ping();
    }

    @Test void serverFallbackAliasUsesExactName() {
        RegisteredServer target = server("lobby");
        RegisteredServer other = server("lobby-2");
        when(proxy.getAllServers()).thenReturn(List.of(other, target));
        when(config.getBoolean("fallback.enabled", true)).thenReturn(true);
        when(config.getString("fallback.type", "RANDOM")).thenReturn("SERVER");
        when(config.getString("fallback.name", "lobby")).thenReturn("lobby");
        assertEquals(Optional.of(target), finder.findFallbackServer().join());
        verify(target, never()).ping();
    }

    @Test void smartSkipsOfflineAndFullServersButAcceptsHiddenPlayerCounts() {
        when(config.getString("default.rule", "SAME")).thenReturn("SMART");
        when(config.getList("default.arguments")).thenReturn(List.of("LEAST", "%last_seen%_lobby"));
        RegisteredServer offline = server("bedwars_lobby1");
        RegisteredServer full = server("bedwars_lobby2");
        RegisteredServer hidden = server("bedwars_lobby3");
        when(proxy.getAllServers()).thenReturn(List.of(offline, full, hidden));
        when(offline.ping()).thenReturn(CompletableFuture.failedFuture(new RuntimeException("offline")));
        ServerPing fullPing = mock(ServerPing.class);
        when(fullPing.getPlayers()).thenReturn(Optional.of(new ServerPing.Players(10, 10, List.of())));
        when(full.ping()).thenReturn(CompletableFuture.completedFuture(fullPing));
        ServerPing hiddenPing = mock(ServerPing.class);
        when(hiddenPing.getPlayers()).thenReturn(Optional.empty());
        when(hidden.ping()).thenReturn(CompletableFuture.completedFuture(hiddenPing));
        assertEquals(Optional.of(hidden), finder.findServerFor(player, "bedwars_123").join());
    }
}
