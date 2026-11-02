package org.dristmine.smartRejoin;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.*;
import com.velocitypowered.api.scheduler.*;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeamlessRecoveryTest {
    SmartRejoin plugin = mock(SmartRejoin.class);
    ProxyServer proxy = mock(ProxyServer.class);
    ConfigManager config = mock(ConfigManager.class);
    RegisteredServer target = mock(RegisteredServer.class);
    AtomicLong clock = new AtomicLong();
    SeamlessReconnectManager manager;
    Runnable monitor;

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(proxy);
        when(plugin.getConfigManager()).thenReturn(config);
        when(config.getInt("seamless_reconnect.recovery_grace", 40)).thenReturn(40);
        when(proxy.getEventManager()).thenReturn(mock(EventManager.class));
        CommandManager commands = mock(CommandManager.class);
        when(proxy.getCommandManager()).thenReturn(commands);
        when(commands.hasCommand("leave")).thenReturn(true);
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder builder = mock(Scheduler.TaskBuilder.class);
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            monitor = call.getArgument(1);
            return builder;
        });
        when(builder.repeat(anyLong(), any(TimeUnit.class))).thenReturn(builder);
        when(builder.schedule()).thenReturn(mock(ScheduledTask.class));
        when(target.getServerInfo()).thenReturn(new ServerInfo("survival", new InetSocketAddress("localhost", 25565)));
        when(proxy.getServer("survival")).thenReturn(Optional.of(target));
        when(target.ping()).thenAnswer(call -> CompletableFuture.completedFuture(mock(ServerPing.class)));
        manager = new SeamlessReconnectManager(plugin, clock::get);
        manager.start();
        manager.markUnavailable(target);
    }

    @Test void shutdownGraceAndConsecutivePingsPreventPrematureRecovery() {
        monitor.run();
        verify(target, never()).ping();
        clock.set(TimeUnit.SECONDS.toNanos(2));
        monitor.run();
        assertTrue(manager.isUnavailable("survival"));
        clock.set(TimeUnit.SECONDS.toNanos(3));
        monitor.run();
        assertFalse(manager.isUnavailable("survival"));
    }

    @Test void statusReplyFromBeforeAnotherFailureCannotCountAsRecovery() {
        CompletableFuture<ServerPing> stale = new CompletableFuture<>();
        when(target.ping()).thenReturn(stale);
        clock.set(TimeUnit.SECONDS.toNanos(2));
        monitor.run();
        clock.set(TimeUnit.SECONDS.toNanos(3));
        manager.markUnavailable(target);
        stale.complete(mock(ServerPing.class));
        when(target.ping()).thenAnswer(call -> CompletableFuture.completedFuture(mock(ServerPing.class)));
        clock.set(TimeUnit.SECONDS.toNanos(5));
        monitor.run();
        assertTrue(manager.isUnavailable("survival"));
        clock.set(TimeUnit.SECONDS.toNanos(6));
        monitor.run();
        assertFalse(manager.isUnavailable("survival"));
    }
}
