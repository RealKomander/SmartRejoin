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
}
