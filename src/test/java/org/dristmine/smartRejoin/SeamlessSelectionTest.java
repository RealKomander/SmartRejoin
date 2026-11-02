package org.dristmine.smartRejoin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeamlessSelectionTest {
    SmartRejoin plugin = mock(SmartRejoin.class);
    ConfigManager config = mock(ConfigManager.class);
    SeamlessReconnectManager manager;

    @BeforeEach void setup() {
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getBoolean("seamless_reconnect.enabled", false)).thenReturn(true);
        when(config.getString("seamless_reconnect.whitelist.type", "ALL")).thenReturn("ALL");
        when(config.getList("seamless_reconnect.whitelist.list")).thenReturn(List.of("survival"));
        manager = new SeamlessReconnectManager(plugin);
    }

    @Test void allPreservesExistingBehavior() {
        assertTrue(manager.isEnabledFor("survival"));
        assertTrue(manager.isEnabledFor("lobby"));
    }

    @Test void exactSelectionIgnoresCaseAndExcludesOtherBackends() {
        when(config.getString("seamless_reconnect.whitelist.type", "ALL")).thenReturn("equals");
        assertTrue(manager.isEnabledFor("SURVIVAL"));
        assertFalse(manager.isEnabledFor("survival-2"));
        assertFalse(manager.isEnabledFor("lobby"));
    }

    @Test void substringSelectionIsCaseSensitive() {
        when(config.getString("seamless_reconnect.whitelist.type", "ALL")).thenReturn("CONTAINS");
        assertTrue(manager.isEnabledFor("survival-2"));
        assertFalse(manager.isEnabledFor("SURVIVAL-2"));
        assertFalse(manager.isEnabledFor("lobby"));
    }

    @Test void emptySelectionAndUnknownTypesSelectNothing() {
        when(config.getString("seamless_reconnect.whitelist.type", "ALL")).thenReturn("EQUALS");
        when(config.getList("seamless_reconnect.whitelist.list")).thenReturn(List.of());
        assertFalse(manager.isEnabledFor("survival"));
        when(config.getString("seamless_reconnect.whitelist.type", "ALL")).thenReturn("invalid");
        assertFalse(manager.isEnabledFor("survival"));
    }

    @Test void globalSwitchStillDisablesAllBackends() {
        when(config.getBoolean("seamless_reconnect.enabled", false)).thenReturn(false);
        assertFalse(manager.isEnabledFor("survival"));
    }

    @Test void selectionUpdatesApplyWithoutRecreatingManager() {
        assertTrue(manager.isEnabledFor("lobby"));
        when(config.getString("seamless_reconnect.whitelist.type", "ALL")).thenReturn("EQUALS");
        assertFalse(manager.isEnabledFor("lobby"));
        assertTrue(manager.isEnabledFor("survival"));
    }
    @Test void missingFutureAdapterWarnsOnceInsteadOfSilentlySkipping() {
        var logger = mock(org.slf4j.Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        var player = mock(com.velocitypowered.api.proxy.Player.class);
        var protocol = mock(com.velocitypowered.api.network.ProtocolVersion.class);
        when(player.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(player.getProtocolVersion()).thenReturn(protocol);
        when(protocol.getProtocol()).thenReturn(778);
        var server = mock(com.velocitypowered.api.proxy.server.RegisteredServer.class);
        when(server.getServerInfo()).thenReturn(new com.velocitypowered.api.proxy.server.ServerInfo(
                "survival", new java.net.InetSocketAddress("localhost", 25565)));
        var event = new com.velocitypowered.api.event.player.ServerConnectedEvent(player, server, null);
        manager.onServerConnected(event);
        manager.onServerConnected(event);
        verify(logger, times(1)).warn(contains("no verified packet adapter"), eq(778), same(protocol));
    }
}
