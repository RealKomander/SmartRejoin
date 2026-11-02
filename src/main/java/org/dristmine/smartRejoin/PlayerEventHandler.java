package org.dristmine.smartRejoin;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class PlayerEventHandler {
    private final SmartRejoin plugin;
    private final AuthenticationGuard authentication;
    private final Set<UUID> deferredAuthentication = ConcurrentHashMap.newKeySet();
    // Preserve the original destination while the player is on temporary fallback.
    private final java.util.Map<UUID, Long> fallbackDeadlines = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, String> pendingTargets = new ConcurrentHashMap<>();

    public PlayerEventHandler(SmartRejoin plugin) {
        this.plugin = plugin;
        this.authentication = new AuthenticationGuard(plugin);
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        if (!authentication.canRoute(player) || deferredAuthentication.contains(player.getUniqueId())) return;
        fallbackDeadlines.remove(player.getUniqueId());
        String name = event.getServer().getServerInfo().getName();
        String target = pendingTargets.get(player.getUniqueId());
        if (target != null && target.equalsIgnoreCase(name)) pendingTargets.remove(player.getUniqueId());
        RejoinQueueManager queue = plugin.getRejoinQueueManager();
        if (queue != null) queue.onPlayerConnectedToServer(player.getUniqueId(), name);
        if (plugin.isPlayerRoutedViaModdedRouting(player.getUniqueId()) &&
                !name.equalsIgnoreCase(plugin.getConfigManager().getModdedRoutingTargetServer())) {
            plugin.removePlayerFromModdedRoutingTracking(player.getUniqueId());
        }
        if (!pendingTargets.containsKey(player.getUniqueId()) && (queue == null || !queue.isQueued(player.getUniqueId())) &&
                !plugin.isPlayerRoutedViaModdedRouting(player.getUniqueId())) {
            plugin.getStorageManager().setLastServer(player.getUniqueId(), name);
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (!authentication.canRoute(player) || !deferredAuthentication.remove(player.getUniqueId())) return;
        // NavAuth owns the limbo connection. Route only after its authenticated connection succeeds.
        chooseServer(player).thenAccept(server -> server.ifPresent(target -> {
            rememberTarget(player, target);
            player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
                if (error != null || !result.isSuccessful()) {
                    RejoinQueueManager queue = plugin.getRejoinQueueManager();
                    if (queue != null) queue.queuePlayer(player, target);
                }
            });
        }));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        RejoinQueueManager queue = plugin.getRejoinQueueManager();
        if (authentication.canRoute(player) && !deferredAuthentication.contains(player.getUniqueId()) &&
                !pendingTargets.containsKey(player.getUniqueId()) &&
                (queue == null || !queue.isQueued(player.getUniqueId())) &&
                !plugin.isPlayerRoutedViaModdedRouting(player.getUniqueId())) {
            player.getCurrentServer().ifPresent(server -> plugin.getStorageManager().setLastServer(
                    player.getUniqueId(), server.getServerInfo().getName()));
        }
        if (queue != null) {
            player.getCurrentServer().ifPresent(server -> queue.onPlayerDisconnectedFromServer(
                    player.getUniqueId(), server.getServerInfo().getName()));
            queue.removePlayerFromQueues(player.getUniqueId());
        }
        pendingTargets.remove(player.getUniqueId());
        fallbackDeadlines.remove(player.getUniqueId());
        deferredAuthentication.remove(player.getUniqueId());
        plugin.removePlayerFromModdedRoutingTracking(player.getUniqueId());
    }

    @Subscribe
    public EventTask onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        if (plugin.getServer().getPluginManager().getPlugin("navauth").isPresent() || !authentication.canRoute(player)) {
            deferredAuthentication.add(player.getUniqueId());
            return null;
        }
        deferredAuthentication.remove(player.getUniqueId());
        return EventTask.resumeWhenComplete(chooseServer(player).thenAccept(server -> server.ifPresent(target -> {
            rememberTarget(player, target);
            event.setInitialServer(target);
        })));
    }

    private CompletableFuture<Optional<RegisteredServer>> chooseServer(Player player) {
        return plugin.getStorageManager().getLastServer(player.getUniqueId())
                .map(last -> plugin.getServerFinder().findServerFor(player, last))
                .orElseGet(() -> plugin.getServerFinder().findFallbackServer());
    }

    private void rememberTarget(Player player, RegisteredServer target) {
        pendingTargets.put(player.getUniqueId(), target.getServerInfo().getName());
    }

    @Subscribe
    public EventTask onKickedFromServer(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        if (!authentication.canRoute(player)) return null;
        RejoinQueueManager queue = plugin.getRejoinQueueManager();
        RegisteredServer failed = event.getServer();
        String original = pendingTargets.computeIfAbsent(player.getUniqueId(),
                id -> failed.getServerInfo().getName());
        if (queue != null) plugin.getServer().getServer(original).ifPresent(target -> queue.queuePlayer(player, target));
        long timeout = Math.max(0, plugin.getConfigManager().getInt("rejoin_queue.fallback_wait_timeout", 200)) * 50L;
        Long deadline = fallbackDeadlines.putIfAbsent(player.getUniqueId(),
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout));
        if (deadline != null) {
            // A real fallback attempt already fired the hooks that start AutoServer.
            // Wait for that fallback to become ready before redirecting again.
            return EventTask.resumeWhenComplete(waitForFallback(failed, deadline).thenAccept(server ->
                    server.ifPresent(target -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(target)))));
        }
        return EventTask.resumeWhenComplete(plugin.getServerFinder().findFallbackServer().thenAccept(candidate -> {
            Optional<RegisteredServer> target = candidate.filter(server -> !server.equals(failed));
            if (target.isEmpty()) {
                target = plugin.getServer().getConfiguration().getAttemptConnectionOrder().stream()
                        .map(name -> plugin.getServer().getServer(name)).flatMap(Optional::stream)
                        .filter(server -> !server.equals(failed)).findFirst();
            }
            // Redirect immediately to trigger on-demand startup via ServerPreConnectEvent.
            target.ifPresent(server -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(server)));
        }));
    }

    private CompletableFuture<Optional<RegisteredServer>> waitForFallback(RegisteredServer fallback, long deadline) {
        if (System.nanoTime() >= deadline) return CompletableFuture.completedFuture(Optional.empty());
        long remaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        return fallback.ping().orTimeout(Math.min(1000, remaining), TimeUnit.MILLISECONDS)
                .handle((ping, error) -> error == null).thenCompose(online -> {
                    if (online) return CompletableFuture.completedFuture(Optional.of(fallback));
                    if (System.nanoTime() >= deadline) return CompletableFuture.completedFuture(Optional.empty());
                    CompletableFuture<Optional<RegisteredServer>> retry = new CompletableFuture<>();
                    plugin.getServer().getScheduler().buildTask(plugin, () -> waitForFallback(fallback, deadline)
                            .whenComplete((result, error) -> {
                                if (error != null) retry.completeExceptionally(error);
                                else retry.complete(result);
                            })).delay(250, TimeUnit.MILLISECONDS).schedule();
                    return retry;
                });
    }
}
