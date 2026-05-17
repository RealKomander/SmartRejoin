package org.dristmine.smartRejoin;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages the rejoin queue feature.
 * Monitors backend servers and queues players for automatic rejoin when servers come back up.
 */
public class RejoinQueueManager {

    private final SmartRejoin plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final ConfigManager config;

    /**
     * Represents the state of a monitored server.
     */
    private enum ServerState {
        UP, DOWN, UNKNOWN
    }

    /**
     * Represents a queued player waiting to rejoin a server.
     */
    private static class PlayerQueueEntry {
        final UUID playerUuid;
        final long queuedAt;
        final String targetServerName; // Store the target server name directly

        PlayerQueueEntry(UUID playerUuid, String targetServerName) {
            this.playerUuid = playerUuid;
            this.targetServerName = targetServerName;
            this.queuedAt = System.currentTimeMillis();
        }
    }

    /**
     * Tracks state and queue for a single monitored server.
     */
    private static class ServerMonitorData {
        ServerState state = ServerState.UNKNOWN;
        final Queue<PlayerQueueEntry> queue = new LinkedList<>();
        ScheduledTask drainTask = null;
        long queueCreatedTime = 0; // Timestamp when the queue was created

        ServerMonitorData() {
        }
    }

    // Server name -> monitor data
    private final Map<String, ServerMonitorData> serverMonitors = new ConcurrentHashMap<>();

    // Server name -> set of player UUIDs currently connected (tracked continuously)
    private final Map<String, Set<UUID>> serverPlayerCache = new ConcurrentHashMap<>();

    // Player UUID -> current server name (tracked continuously)
    private final Map<UUID, String> playerCurrentServer = new ConcurrentHashMap<>();

    // Player UUID -> set of server names they are queued for (for fast lookup on disconnect)
    private final Map<UUID, Set<String>> playerQueueIndex = new ConcurrentHashMap<>();

    // The monitoring task
    private ScheduledTask monitorTask = null;

    // The actionbar update task (shared for all queued players)
    private ScheduledTask actionbarTask = null;

    // MiniMessage instance for parsing messages
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Lock for thread-safe operations
    private final Object monitorLock = new Object();

    // Debug mode flag
    private boolean debugEnabled = false;

    public RejoinQueueManager(SmartRejoin plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getServer();
        this.logger = plugin.getLogger();
        this.config = plugin.getConfigManager();
        this.debugEnabled = config.getRejoinQueueDebug();
    }

    /**
     * Called when a player connects to a server (for tracking).
     * Also removes players from queues if they join a different server.
     */
    public void onPlayerConnectedToServer(UUID playerUuid, String serverName) {
        serverPlayerCache.computeIfAbsent(serverName, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
        playerCurrentServer.put(playerUuid, serverName);

        // If player is in a queue and joined a different server, remove them from the queue
        Set<String> queuedServers = playerQueueIndex.get(playerUuid);
        if (queuedServers != null && !queuedServers.isEmpty()) {
            Set<String> queuedCopy = new HashSet<>(queuedServers);
            for (String queuedServer : queuedCopy) {
                if (!queuedServer.equals(serverName)) {
                    ServerMonitorData data = serverMonitors.get(queuedServer);
                    if (data != null) {
                        synchronized (data.queue) {
                            data.queue.removeIf(e -> e.playerUuid.equals(playerUuid));
                        }
                    }
                    removeFromIndex(playerUuid, queuedServer);
                    plugin.logInfo("Player " + playerUuid + " left the queue for '" + queuedServer + "' by joining '" + serverName + "'.");
                }
            }
        }

        debugLog("Player " + playerUuid + " connected to '" + serverName + "'");
    }

    /**
     * Called when a player disconnects from a server (for tracking).
     */
    public void onPlayerDisconnectedFromServer(UUID playerUuid, String serverName) {
        Set<UUID> players = serverPlayerCache.get(serverName);
        if (players != null) {
            players.remove(playerUuid);
            if (players.isEmpty()) {
                serverPlayerCache.remove(serverName);
            }
        }
        playerCurrentServer.remove(playerUuid);
        debugLog("Player " + playerUuid + " disconnected from '" + serverName + "'");
    }

    /**
     * Debug logging helper - only logs if debug mode is enabled.
     */
    private void debugLog(String message) {
        if (debugEnabled) {
            logger.info("[RejoinQueue DEBUG] " + message);
        }
    }

    /**
     * Start monitoring servers.
     */
    public void start() {
        if (monitorTask != null) {
            logger.warn("RejoinQueueManager is already running.");
            return;
        }

        int intervalTicks = config.getRejoinQueueMonitorInterval();
        monitorTask = proxy.getScheduler().buildTask(plugin, this::monitorServers)
                .repeat(intervalTicks, TimeUnit.MILLISECONDS)
                .schedule();

        // Start actionbar task if enabled
        if (config.getRejoinQueueActionbarEnabled()) {
            int actionbarIntervalTicks = 20; // Update every 20 ticks (1 second) to prevent fading
            actionbarTask = proxy.getScheduler().buildTask(plugin, this::updateActionbars)
                    .repeat(actionbarIntervalTicks, TimeUnit.MILLISECONDS)
                    .schedule();
        }

        logger.info("RejoinQueueManager started. Monitoring servers every {} ticks.", intervalTicks);
    }

    /**
     * Stop monitoring servers and clean up.
     */
    public void stop() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }

        if (actionbarTask != null) {
            actionbarTask.cancel();
            actionbarTask = null;
        }

        // Cancel all drain tasks
        for (ServerMonitorData data : serverMonitors.values()) {
            if (data.drainTask != null) {
                data.drainTask.cancel();
            }
        }

        serverMonitors.clear();
        playerQueueIndex.clear();

        logger.info("RejoinQueueManager stopped.");
    }

    /**
     * Remove a player from all queues when they disconnect.
     */
    public void removePlayerFromQueues(UUID playerUuid) {
        Set<String> queuedServers = playerQueueIndex.remove(playerUuid);
        if (queuedServers != null) {
            for (String serverName : queuedServers) {
                ServerMonitorData data = serverMonitors.get(serverName);
                if (data != null) {
                    synchronized (data.queue) {
                        data.queue.removeIf(entry -> entry.playerUuid.equals(playerUuid));
                    }
                }
            }
            plugin.logInfo("Player " + playerUuid + " removed from rejoin queues due to disconnect.");
        }
    }

    /**
     * Manually remove a player from a specific queue (for leave-queue command).
     * Returns the server name if the player was in a queue, or null if not.
     */
    public String removePlayerFromQueueManually(UUID playerUuid) {
        Set<String> queuedServers = playerQueueIndex.get(playerUuid);
        if (queuedServers == null || queuedServers.isEmpty()) {
            return null;
        }

        // Get the first (and typically only) server the player is queued for
        String serverName = queuedServers.iterator().next();

        ServerMonitorData data = serverMonitors.get(serverName);
        if (data != null) {
            synchronized (data.queue) {
                data.queue.removeIf(entry -> entry.playerUuid.equals(playerUuid));
            }
        }

        // Remove from index
        removeFromIndex(playerUuid, serverName);

        return serverName;
    }

    /**
     * Monitor all configured servers for state changes.
     */
    private void monitorServers() {
        // Check for wait timeout on all queues
        checkWaitTimeouts();

        // Get all servers that should be monitored based on whitelist
        Set<RegisteredServer> serversToMonitor = getServersToMonitor();

        for (RegisteredServer server : serversToMonitor) {
            String serverName = server.getServerInfo().getName();
            ServerMonitorData data = serverMonitors.computeIfAbsent(serverName, k -> new ServerMonitorData());

            // Ping the server to check availability
            CompletableFuture<ServerPing> pingFuture = server.ping();
            final ServerMonitorData monitorData = data;

            pingFuture.whenComplete((ping, throwable) -> {
                ServerState newState;
                if (throwable != null) {
                    newState = ServerState.DOWN;
                } else {
                    newState = ServerState.UP;
                }

                handleServerStateChange(serverName, server, monitorData, newState);
            });
        }
    }

    /**
     * Check for wait timeout on all queues and discard expired ones.
     */
    private void checkWaitTimeouts() {
        int waitTimeoutTicks = config.getRejoinQueueWaitTimeout();
        if (waitTimeoutTicks <= 0) {
            return; // Timeout disabled
        }

        long waitTimeoutMillis = waitTimeoutTicks * 50L; // Convert ticks to milliseconds (20 ticks = 1000ms)
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, ServerMonitorData> entry : serverMonitors.entrySet()) {
            String serverName = entry.getKey();
            ServerMonitorData data = entry.getValue();

            // Only check queues that are in DOWN state with a creation time
            if (data.state == ServerState.DOWN && data.queueCreatedTime > 0) {
                long elapsed = currentTime - data.queueCreatedTime;
                if (elapsed >= waitTimeoutMillis) {
                    plugin.logInfo("Queue for '" + serverName + "' has timed out after " + elapsed + "ms. Discarding queue.");

                    // Remove all players from the index
                    synchronized (data.queue) {
                        for (PlayerQueueEntry queueEntry : data.queue) {
                            removeFromIndex(queueEntry.playerUuid, serverName);
                        }
                        data.queue.clear();
                    }

                    data.queueCreatedTime = 0;
                }
            }
        }
    }

    /**
     * Update actionbar messages for all queued players.
     */
    private void updateActionbars() {
        if (!config.getRejoinQueueActionbarEnabled()) {
            return;
        }

        String format = config.getRejoinQueueActionbarFormat();

        // Collect all players and their target servers (avoiding duplicates)
        Map<UUID, PlayerQueueEntry> playersToShow = new HashMap<>();

        for (Map.Entry<String, ServerMonitorData> entry : serverMonitors.entrySet()) {
            ServerMonitorData data = entry.getValue();

            // Send actionbar for DOWN queues OR queues that are actively being drained
            if (data.state != ServerState.DOWN && data.drainTask == null) {
                continue;
            }

            List<PlayerQueueEntry> entries;
            synchronized (data.queue) {
                if (data.queue.isEmpty()) {
                    continue;
                }
                // Create a copy to avoid concurrent modification
                entries = new java.util.ArrayList<>(data.queue);
            }

            // Add players to map (later entries will overwrite earlier ones, so each player gets one actionbar)
            for (PlayerQueueEntry queueEntry : entries) {
                playersToShow.put(queueEntry.playerUuid, queueEntry);
            }
        }

        // Send actionbar to each unique player
        for (PlayerQueueEntry queueEntry : playersToShow.values()) {
            Optional<Player> playerOpt = proxy.getPlayer(queueEntry.playerUuid);
            if (playerOpt.isPresent()) {
                Player player = playerOpt.get();

                // Use the target server name from the queue entry
                String targetServer = queueEntry.targetServerName;

                // Get position and total for this specific server's queue
                ServerMonitorData targetData = serverMonitors.get(targetServer);
                int position = 1;
                int total = 0;
                if (targetData != null) {
                    synchronized (targetData.queue) {
                        total = targetData.queue.size();
                        // Find position in queue
                        for (PlayerQueueEntry e : targetData.queue) {
                            if (e.playerUuid.equals(queueEntry.playerUuid)) {
                                break;
                            }
                            position++;
                        }
                    }
                }

                // Replace placeholders
                String message = format
                        .replace("{server}", targetServer)
                        .replace("{position}", String.valueOf(position))
                        .replace("{total}", String.valueOf(total));

                try {
                    Component component = miniMessage.deserialize(message);
                    player.sendActionBar(component);
                } catch (Exception e) {
                    // If MiniMessage parsing fails, send plain text
                    player.sendActionBar(Component.text(message));
                }
            }
        }
    }

    /**
     * Get the set of servers that should be monitored based on whitelist configuration.
     */
    private Set<RegisteredServer> getServersToMonitor() {
        String whitelistType = config.getRejoinQueueWhitelistType();
        List<String> whitelistList = config.getRejoinQueueWhitelistList();

        Set<RegisteredServer> servers = new HashSet<>();

        for (RegisteredServer server : proxy.getAllServers()) {
            String serverName = server.getServerInfo().getName();

            boolean shouldMonitor = switch (whitelistType) {
                case "ALL" -> true;
                case "CONTAINS" -> whitelistList.stream().anyMatch(serverName::contains);
                case "EQUALS" -> whitelistList.stream().anyMatch(serverName::equalsIgnoreCase);
                default -> false;
            };

            if (shouldMonitor) {
                servers.add(server);
            }
        }

        return servers;
    }

    /**
     * Handle state transitions for a monitored server.
     */
    private void handleServerStateChange(String serverName, RegisteredServer server, ServerMonitorData data, ServerState newState) {
        ServerState oldState;

        synchronized (monitorLock) {
            oldState = data.state;

            // Only act on state changes
            if (oldState == newState) {
                return;
            }

            data.state = newState;
        }

        plugin.logInfo("Server '" + serverName + "' state changed: " + oldState + " -> " + newState);

        if (oldState == ServerState.UP && newState == ServerState.DOWN) {
            handleServerGoingDown(serverName, server);
        } else if (oldState == ServerState.DOWN && newState == ServerState.UP) {
            handleServerComingUp(serverName, server, data);
        }
    }

    /**
     * Handle a server going down (UP -> DOWN transition).
     * Snapshot connected players and add them to the queue.
     * Uses cached player list since players may have already been kicked by the time this runs.
     */
    private void handleServerGoingDown(String serverName, RegisteredServer server) {
        plugin.logInfo("Server '" + serverName + "' went down. Creating rejoin queue...");

        // Use cached player list instead of getPlayersConnected() (which may be empty by now)
        Set<UUID> cachedPlayers = serverPlayerCache.get(serverName);
        int playerCount = (cachedPlayers != null) ? cachedPlayers.size() : 0;
        debugLog("handleServerGoingDown: server=" + serverName + ", cachedPlayers=" + playerCount);

        if (playerCount == 0) {
            debugLog("No players to queue for '" + serverName + "'");
            return;
        }

        ServerMonitorData data = serverMonitors.computeIfAbsent(serverName, k -> new ServerMonitorData());
        data.queueCreatedTime = System.currentTimeMillis();

        // Clear any existing queue entries for this server (prevents duplicates if state flaps)
        synchronized (data.queue) {
            for (PlayerQueueEntry existingEntry : data.queue) {
                removeFromIndex(existingEntry.playerUuid, serverName);
            }
            data.queue.clear();
        }

        // Add cached players to the queue
        for (UUID playerUuid : new HashSet<>(cachedPlayers)) {
            // IMPORTANT: Remove player from all OTHER server queues before adding to this one
            // This prevents players from accumulating in multiple queues
            Set<String> otherQueues = playerQueueIndex.get(playerUuid);
            if (otherQueues != null) {
                for (String otherServer : new HashSet<>(otherQueues)) {
                    if (!otherServer.equals(serverName)) {
                        debugLog("Removing " + playerUuid + " from queue for '" + otherServer + "' (now queued for '" + serverName + "')");
                        ServerMonitorData otherData = serverMonitors.get(otherServer);
                        if (otherData != null) {
                            synchronized (otherData.queue) {
                                otherData.queue.removeIf(e -> e.playerUuid.equals(playerUuid));
                            }
                        }
                        removeFromIndex(playerUuid, otherServer);
                    }
                }
            }

            PlayerQueueEntry entry = new PlayerQueueEntry(playerUuid, serverName);

            synchronized (data.queue) {
                data.queue.add(entry);
            }

            // Add to index for fast lookup on disconnect
            playerQueueIndex.computeIfAbsent(playerUuid, k -> new HashSet<>()).add(serverName);

            // Try to get player name for logging (player might be offline now)
            proxy.getPlayer(playerUuid).ifPresent(player -> {
                plugin.logInfo("Player " + player.getUsername() + " (" + playerUuid + ") added to rejoin queue for '" + serverName + "'.");
            });
        }

        // The existing v1.1 fallback logic will handle moving players to fallback servers
        // We don't need to do anything special here - the ServerFinder will use fallback when the server is unreachable
    }

    /**
     * Handle a server coming back up (DOWN -> UP transition).
     * Start draining the queue with configured delays.
     */
    private void handleServerComingUp(String serverName, RegisteredServer server, ServerMonitorData data) {
        plugin.logInfo("Server '" + serverName + "' came back up. Starting queue drain...");

        int queueSize;
        synchronized (data.queue) {
            queueSize = data.queue.size();
        }
        debugLog("handleServerComingUp: server=" + serverName + ", queueSize=" + queueSize);

        // Reset queue creation time since server is back up
        data.queueCreatedTime = 0;

        // Cancel any existing drain task
        if (data.drainTask != null) {
            data.drainTask.cancel();
        }

        int initialDelayTicks = config.getRejoinQueueInitialDelay();
        debugLog("Scheduling drain task with initial delay: " + initialDelayTicks + " ticks");

        // Schedule the drain task
        data.drainTask = proxy.getScheduler().buildTask(plugin, () -> drainQueue(serverName, server, data))
                .delay(initialDelayTicks, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /**
     * Drain the queue for a server, sending players back one by one.
     */
    private void drainQueue(String serverName, RegisteredServer server, ServerMonitorData data) {
        int cooldownTicks = config.getRejoinQueueCooldown();
        debugLog("drainQueue called for server='" + serverName + "'");

        // Process one player from the queue
        PlayerQueueEntry entry;
        synchronized (data.queue) {
            entry = data.queue.poll();
        }

        if (entry == null) {
            // Queue is empty, we're done
            plugin.logInfo("Rejoin queue for '" + serverName + "' is now empty.");
            data.drainTask = null;
            return;
        }

        UUID playerUuid = entry.playerUuid;
        debugLog("Processing player " + playerUuid + " from queue for '" + serverName + "'");
        debugLog("Queue entry target server: " + entry.targetServerName);

        Optional<Player> playerOpt = proxy.getPlayer(playerUuid);

        if (playerOpt.isEmpty()) {
            // Player is no longer online, skip them
            plugin.logInfo("Player " + playerUuid + " is no longer online. Skipping rejoin.");
            removeFromIndex(playerUuid, serverName);

            // Schedule next player
            scheduleNextDrain(serverName, server, data, cooldownTicks);
            return;
        }

        Player player = playerOpt.get();
        String currentServer = player.getCurrentServer()
                .map(sc -> sc.getServerInfo().getName())
                .orElse("none");

        debugLog("Player " + player.getUsername() + " is currently on: " + currentServer);

        // Check if player is currently connected to the target server
        boolean isOnTargetServer = isPlayerOnServer(player, serverName);
        debugLog("isOnTargetServer(" + serverName + "): " + isOnTargetServer);

        if (isOnTargetServer) {
            plugin.logInfo("Player " + player.getUsername() + " is already on '" + serverName + "'. Removing from queue.");
            removeFromIndex(playerUuid, serverName);
            scheduleNextDrain(serverName, server, data, cooldownTicks);
            return;
        }

        // Check if player is on a fallback server
        boolean isOnFallbackServer = isPlayerOnFallbackServer(player);
        debugLog("isOnFallbackServer: " + isOnFallbackServer);

        if (!isOnTargetServer && !isOnFallbackServer) {
            // Player has moved to a different non-fallback server, drop them from queue
            plugin.logInfo("Player " + player.getUsername() + " has moved to a different server. Dropping from queue.");
            debugLog("Dropping player " + player.getUsername() + " from queue (not on target or fallback)");
            removeFromIndex(playerUuid, serverName);

            // Schedule next player
            scheduleNextDrain(serverName, server, data, cooldownTicks);
            return;
        }

        // Send the player back to the original server
        // If they're on the target server, this will be a no-op or refresh their connection
        // If they're on a fallback server, this will move them to the target server
        String location = isOnTargetServer ? "target" : "fallback";
        plugin.logInfo("Sending player " + player.getUsername() + " from " + location + " to '" + serverName + "'.");
        debugLog("Initiating connection request for " + player.getUsername() + " to '" + serverName + "'");

        player.createConnectionRequest(server).connect().whenComplete((result, throwable) -> {
            if (throwable != null) {
                plugin.logWarn("Failed to send player " + player.getUsername() + " to '" + serverName + "': " + throwable.getMessage());
                debugLog("Connection failed with exception: " + throwable.getClass().getName() + ": " + throwable.getMessage());
            } else if (!result.isSuccessful()) {
                if (result.getStatus().toString().equals("ALREADY_CONNECTED")) {
                    plugin.logInfo("Player " + player.getUsername() + " is already on '" + serverName + "'. Removing from queue.");
                } else {
                    plugin.logWarn("Failed to send player " + player.getUsername() + " to '" + serverName + "': " + result.getStatus());
                    debugLog("Connection failed with status: " + result.getStatus());
                }
            } else {
                plugin.logInfo("Successfully sent player " + player.getUsername() + " to '" + serverName + "'.");
                debugLog("Connection successful for " + player.getUsername());
            }
            removeFromIndex(playerUuid, serverName);
        });

        // Schedule next player
        scheduleNextDrain(serverName, server, data, cooldownTicks);
    }

    /**
     * Schedule the next player in the queue to be sent.
     */
    private void scheduleNextDrain(String serverName, RegisteredServer server, ServerMonitorData data, int cooldownTicks) {
        data.drainTask = proxy.getScheduler().buildTask(plugin, () -> drainQueue(serverName, server, data))
                .delay(cooldownTicks, TimeUnit.MILLISECONDS)
                .schedule();
    }

    /**
     * Check if a player is currently on a fallback server.
     * A fallback server is determined by checking if it matches the fallback configuration.
     */
    private boolean isPlayerOnFallbackServer(Player player) {
        boolean fallbackEnabled = config.getBoolean("fallback.enabled", false);
        debugLog("isPlayerOnFallbackServer: fallbackEnabled=" + fallbackEnabled);

        if (!fallbackEnabled) {
            // If fallback is disabled, we can't determine if they're on a fallback server
            // In this case, we'll consider any server as a potential fallback
            debugLog("Fallback disabled, returning true (any server is considered fallback)");
            return true;
        }

        String fallbackType = config.getString("fallback.type", "RANDOM");
        String fallbackName = config.getString("fallback.name", "lobby");

        String currentServerName = player.getCurrentServer()
                .map(sc -> sc.getServerInfo().getName())
                .orElse("");

        debugLog("Fallback config: type=" + fallbackType + ", name=" + fallbackName);
        debugLog("Player's current server: " + currentServerName);

        if (currentServerName.isEmpty()) {
            debugLog("Player has no current server, returning false");
            return false;
        }

        // Check if the current server matches the fallback configuration
        boolean matches = switch (fallbackType.toUpperCase()) {
            case "EQUALS", "SERVER" -> currentServerName.equalsIgnoreCase(fallbackName);
            case "RANDOM" -> currentServerName.contains(fallbackName);
            default -> {
                debugLog("Unknown fallback type, defaulting to true");
                yield true; // Default to true for unknown types
            }
        };

        debugLog("Fallback check result: " + matches);
        return matches;
    }

    /**
     * Check if a player is currently on a specific server.
     * This is used to detect players who are still "connected" to a downed server
     * (when fallback is disabled, players remain on the downed server).
     */
    private boolean isPlayerOnServer(Player player, String serverName) {
        return player.getCurrentServer()
                .map(sc -> sc.getServerInfo().getName().equalsIgnoreCase(serverName))
                .orElse(false);
    }

    /**
     * Remove a player from the queue index for a specific server.
     */
    private void removeFromIndex(UUID playerUuid, String serverName) {
        Set<String> queuedServers = playerQueueIndex.get(playerUuid);
        if (queuedServers != null) {
            queuedServers.remove(serverName);
            if (queuedServers.isEmpty()) {
                playerQueueIndex.remove(playerUuid);
            }
        }
    }
}
