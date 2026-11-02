package org.dristmine.smartRejoin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigManagerTest {
    @TempDir Path directory;

    ConfigManager load() {
        ConfigManager config = new ConfigManager(mock(SmartRejoin.class), directory, LoggerFactory.getLogger(getClass()));
        assertTrue(config.loadConfig());
        return config;
    }

    @Test void freshInstallEnablesSeamless() {
        ConfigManager config = load();
        assertTrue(config.getBoolean("seamless_reconnect.enabled", false));
        assertEquals("The server is restarting, /leave to return to lobby",
                config.getString("seamless_reconnect.actionbar", ""));
    }

    @Test void existingConfigDoesNotOptInOnUpgradeOrLaterReload() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "default:\n  rule: SAME\n");
        assertFalse(load().getBoolean("seamless_reconnect.enabled", true));
        assertFalse(load().getBoolean("seamless_reconnect.enabled", true));
        assertTrue(Files.readString(directory.resolve("config.yml")).contains("enabled: false"));
    }

    @Test void explicitUserPreferenceSurvivesReload() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "seamless_reconnect:\n  enabled: true\n");
        assertTrue(load().getBoolean("seamless_reconnect.enabled", false));
        assertTrue(load().getBoolean("seamless_reconnect.enabled", false));
    }
}
