package org.dristmine.smartRejoin;

import com.velocitypowered.api.proxy.*;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.scheduler.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RejoinQueueManagerTest {
    SmartRejoin plugin = mock(SmartRejoin.class);
    ProxyServer proxy = mock(ProxyServer.class);
    ConfigManager config = mock(ConfigManager.class);
    Scheduler scheduler = mock(Scheduler.class);
    Scheduler.TaskBuilder task = mock(Scheduler.TaskBuilder.class);
    ScheduledTask scheduled = mock(ScheduledTask.class);
    Player player = mock(Player.class);
    RegisteredServer target = mock(RegisteredServer.class);
    UUID id = UUID.randomUUID();
    RejoinQueueManager manager;
    List<Runnable> tasks = new ArrayList<>();

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(proxy);
        when(plugin.getConfigManager()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger(getClass()));
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            tasks.add(call.getArgument(1));
            return task;
        });
        when(task.repeat(anyLong(), any(TimeUnit.class))).thenReturn(task);
        when(task.delay(anyLong(), any(TimeUnit.class))).thenReturn(task);
        when(task.schedule()).thenReturn(scheduled);
        when(config.getRejoinQueueWhitelistType()).thenReturn("ALL");
        when(config.getRejoinQueueWhitelistList()).thenReturn(List.of());
        when(config.getRejoinQueueMonitorInterval()).thenReturn(100);
        when(config.getRejoinQueueInitialDelay()).thenReturn(60);
        when(config.getRejoinQueueCooldown()).thenReturn(10);
        when(config.getRejoinQueueActionbarEnabled()).thenReturn(true);
        when(target.getServerInfo()).thenReturn(new ServerInfo("survival", new InetSocketAddress("localhost", 25565)));
        when(proxy.getAllServers()).thenReturn(List.of(target));
        when(player.getUniqueId()).thenReturn(id);
        when(proxy.getPlayer(id)).thenReturn(Optional.of(player));
        manager = new RejoinQueueManager(plugin);
    }

    @Test void queuesAlreadyDownServerAndPreservesEntryOnFallbackAndRecovery() {
        manager.queuePlayer(player, target);
        manager.queuePlayer(player, target);
        assertTrue(manager.isQueued(id));
        manager.onPlayerConnectedToServer(id, "lobby");
        assertTrue(manager.isQueued(id));
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        manager.start();
        tasks.get(0).run();
        verify(task).repeat(5000L, TimeUnit.MILLISECONDS);
        verify(task).repeat(1000L, TimeUnit.MILLISECONDS);
        verify(task).delay(3000L, TimeUnit.MILLISECONDS);
        manager.onPlayerConnectedToServer(id, "lobby");
        assertTrue(manager.isQueued(id));
        manager.onPlayerConnectedToServer(id, "survival");
        assertFalse(manager.isQueued(id));
    }

    @Test void failedReturnStaysQueued() {
        manager.queuePlayer(player, target);
        when(config.getBoolean("fallback.enabled", false)).thenReturn(false);
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        when(target.ping()).thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        when(player.createConnectionRequest(target)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.failedFuture(new RuntimeException("starting")));
        manager.start();
        tasks.get(0).run(); // monitor: DOWN -> UP
        tasks.get(2).run(); // first drain, fails and queues again
        assertTrue(manager.isQueued(id));
        manager.removePlayerFromQueues(id);
        assertFalse(manager.isQueued(id));
    }

    @Test void excludedServersAreNotQueued() {
        when(config.getRejoinQueueWhitelistType()).thenReturn("EQUALS");
        when(config.getRejoinQueueWhitelistList()).thenReturn(List.of("creative"));
        manager.queuePlayer(player, target);
        assertFalse(manager.isQueued(id));
    }
}
