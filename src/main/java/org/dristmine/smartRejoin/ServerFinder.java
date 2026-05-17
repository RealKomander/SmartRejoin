package org.dristmine.smartRejoin;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ServerFinder {

    private final SmartRejoin plugin;
    private final ProxyServer proxy;
    private final ConfigManager config;

    private static final Pattern SMART_CLEANUP_PATTERN = Pattern.compile("[_\\- ]*\\d+$");

    // Map of protocol versions to Minecraft version strings
    private static final Map<Integer, String> PROTOCOL_TO_VERSION = new HashMap<>();
    static {
        // Modern versions
        PROTOCOL_TO_VERSION.put(767, "1.21");
        PROTOCOL_TO_VERSION.put(766, "1.20.6");
        PROTOCOL_TO_VERSION.put(765, "1.20.5");
        PROTOCOL_TO_VERSION.put(764, "1.20.4");
        PROTOCOL_TO_VERSION.put(763, "1.20.1");
        PROTOCOL_TO_VERSION.put(762, "1.19.4");
        PROTOCOL_TO_VERSION.put(761, "1.19.3");
        PROTOCOL_TO_VERSION.put(760, "1.19.2");
        PROTOCOL_TO_VERSION.put(759, "1.19.1");
        PROTOCOL_TO_VERSION.put(758, "1.19");
        PROTOCOL_TO_VERSION.put(757, "1.18.1");
        PROTOCOL_TO_VERSION.put(756, "1.17.1");
        PROTOCOL_TO_VERSION.put(755, "1.17");
        PROTOCOL_TO_VERSION.put(754, "1.16.5");
        PROTOCOL_TO_VERSION.put(753, "1.16.3");
        PROTOCOL_TO_VERSION.put(752, "1.16.2");
        PROTOCOL_TO_VERSION.put(751, "1.16.1");
        PROTOCOL_TO_VERSION.put(736, "1.16");
        PROTOCOL_TO_VERSION.put(578, "1.15.2");
        PROTOCOL_TO_VERSION.put(575, "1.15.1");
        PROTOCOL_TO_VERSION.put(573, "1.15");
        PROTOCOL_TO_VERSION.put(498, "1.14.4");
        PROTOCOL_TO_VERSION.put(490, "1.14.3");
        PROTOCOL_TO_VERSION.put(485, "1.14.2");
        PROTOCOL_TO_VERSION.put(480, "1.14.1");
        PROTOCOL_TO_VERSION.put(477, "1.14");
        PROTOCOL_TO_VERSION.put(404, "1.13.2");
        PROTOCOL_TO_VERSION.put(401, "1.13.1");
        PROTOCOL_TO_VERSION.put(393, "1.13");
        PROTOCOL_TO_VERSION.put(340, "1.12.2");
        PROTOCOL_TO_VERSION.put(335, "1.12");
        PROTOCOL_TO_VERSION.put(316, "1.11.2");
        PROTOCOL_TO_VERSION.put(315, "1.11.1");
        PROTOCOL_TO_VERSION.put(210, "1.10.2");
        PROTOCOL_TO_VERSION.put(110, "1.9.4");
        PROTOCOL_TO_VERSION.put(107, "1.9");
        PROTOCOL_TO_VERSION.put(47, "1.8");
    }

    public ServerFinder(SmartRejoin plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getServer();
        this.config = plugin.getConfigManager();
    }

    public CompletableFuture<Optional<RegisteredServer>> findServerFor(Player player, String lastServerName) {
        // 0. Check modded routing first (highest priority)
        if (config.getModdedRoutingEnabled()) {
            Optional<RegisteredServer> moddedServer = findModdedServerFor(player);
            if (moddedServer.isPresent()) {
                plugin.logInfo("Player " + player.getUsername() + " matched modded routing criteria. Sending to: " + moddedServer.get().getServerInfo().getName());
                // Mark player as routed via modded routing to prevent data.yml overwrite
                plugin.markPlayerRoutedViaModdedRouting(player.getUniqueId());
                return CompletableFuture.completedFuture(moddedServer);
            }
        }

        // 1. Check custom rules first
        for (Map.Entry<String, Map<String, Object>> entry : config.getRules().entrySet()) {
            String ruleName = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> ruleData = entry.getValue();

            @SuppressWarnings("unchecked")
            Map<String, Object> lastSeen = (Map<String, Object>) ruleData.get("last_seen");
            String matchType = (String) lastSeen.get("type");
            String matchName = (String) lastSeen.get("name");

            if (matches(lastServerName, matchType, matchName)) {
                plugin.logInfo("Player " + player.getUsername() + " matched rule '" + ruleName + "'. Finding target server...");
                @SuppressWarnings("unchecked")
                Map<String, Object> whereToJoin = (Map<String, Object>) ruleData.get("where_to_join");
                return findServerFromRule(whereToJoin).thenCompose(serverOpt -> {
                    if (serverOpt.isPresent()) {
                        return CompletableFuture.completedFuture(serverOpt);
                    }
                    plugin.logWarn("Target for rule '" + ruleName + "' not found. Using fallback.");
                    return findFallbackServer();
                });
            }
        }

        // 2. If no rules matched, use the default rule
        plugin.logInfo("Player " + player.getUsername() + " did not match any specific rules. Using default rule.");
        return findDefaultServer(lastServerName);
    }

    private CompletableFuture<Optional<RegisteredServer>> findDefaultServer(String lastServerName) {
        String ruleType = config.getString("default.rule", "SAME").toUpperCase();
        switch (ruleType) {
            case "SAME":
                return findServer(s -> s.getServerInfo().getName().equalsIgnoreCase(lastServerName), "SAME", lastServerName)
                        .thenCompose(serverOpt -> serverOpt.isPresent() ? CompletableFuture.completedFuture(serverOpt) : findFallbackServer());
            case "SMART":
                List<String> args = config.getList("default.arguments");
                if (args.size() < 2) {
                    plugin.logWarn("Default rule is SMART but arguments are invalid. Using fallback.");
                    return findFallbackServer();
                }
                String strategy = args.get(0);
                String namePattern = args.get(1);
                String cleanedName = SMART_CLEANUP_PATTERN.matcher(lastServerName).replaceAll("");
                String targetName = namePattern.replace("%last_seen%", cleanedName);

                Map<String, Object> smartRule = Map.of("type", strategy, "name", targetName);
                return findServerFromRule(smartRule)
                        .thenCompose(serverOpt -> serverOpt.isPresent() ? CompletableFuture.completedFuture(serverOpt) : findFallbackServer());
            case "FALLBACK":
            default:
                return findFallbackServer();
        }
    }

    public CompletableFuture<Optional<RegisteredServer>> findFallbackServer() {
        if (!config.getBoolean("fallback.enabled", true)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        plugin.logInfo("Executing fallback logic...");
        String type = config.getString("fallback.type", "RANDOM");
        String name = config.getString("fallback.name", "lobby");
        Map<String, Object> fallbackRule = Map.of("type", type, "name", name);
        return findServerFromRule(fallbackRule);
    }

    private CompletableFuture<Optional<RegisteredServer>> findServerFromRule(Map<String, Object> rule) {
        String type = ((String) rule.get("type")).toUpperCase();
        String name = (String) rule.get("name");

        Predicate<RegisteredServer> filter = switch (type) {
            case "EQUALS", "SAME" -> s -> s.getServerInfo().getName().equalsIgnoreCase(name);
            default -> s -> s.getServerInfo().getName().contains(name);
        };

        return findServer(filter, type, name);
    }

    private CompletableFuture<Optional<RegisteredServer>> findServer(Predicate<RegisteredServer> serverFilter, String strategy, String name) {
        List<RegisteredServer> candidates = proxy.getAllServers().stream()
                .filter(serverFilter)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            plugin.logWarn("Strategy '" + strategy + "' for name '" + name + "' found no candidate servers.");
            return CompletableFuture.completedFuture(Optional.empty());
        }

        Map<RegisteredServer, CompletableFuture<ServerPing>> pingFutures = new HashMap<>();
        for (RegisteredServer server : candidates) {
            pingFutures.put(server, server.ping());
        }

        return CompletableFuture.allOf(pingFutures.values().toArray(new CompletableFuture[0]))
                .handle((v, throwable) -> {
                    Map<RegisteredServer, ServerPing> onlineServers = new HashMap<>();
                    for (Map.Entry<RegisteredServer, CompletableFuture<ServerPing>> entry : pingFutures.entrySet()) {
                        if (!entry.getValue().isCompletedExceptionally()) {
                            onlineServers.put(entry.getKey(), entry.getValue().join());
                        }
                    }

                    List<Map.Entry<RegisteredServer, ServerPing>> availableServers = onlineServers.entrySet().stream()
                            .filter(entry -> entry.getValue().getPlayers().isPresent() &&
                                    entry.getValue().getPlayers().get().getOnline() < entry.getValue().getPlayers().get().getMax())
                            .collect(Collectors.toList());

                    if (availableServers.isEmpty()) {
                        plugin.logWarn("Strategy '" + strategy + "' for name '" + name + "' found no online servers with free slots.");
                        return Optional.empty();
                    }

                    Optional<Map.Entry<RegisteredServer, ServerPing>> selectedEntry = switch (strategy.toUpperCase()) {
                        case "MOST" -> availableServers.stream().max(Comparator.comparingInt(e -> e.getValue().getPlayers().get().getOnline()));
                        case "LEAST" -> availableServers.stream().min(Comparator.comparingInt(e -> e.getValue().getPlayers().get().getOnline()));
                        case "RANDOM" -> Optional.of(availableServers.get(new Random().nextInt(availableServers.size())));
                        case "EQUALS", "SAME" -> availableServers.stream().findFirst();
                        default -> Optional.empty();
                    };

                    if (selectedEntry.isEmpty()) {
                        plugin.logWarn("Strategy '" + strategy + "' for name '" + name + "' could not select a server from the online list.");
                        return Optional.empty();
                    }

                    RegisteredServer finalServer = selectedEntry.get().getKey();
                    plugin.logInfo("Strategy '" + strategy + "' selected server '" + finalServer.getServerInfo().getName() + "'.");
                    return Optional.of(finalServer);
                });
    }

    private boolean matches(String input, String type, String value) {
        return switch (type.toUpperCase()) {
            case "EQUALS" -> input.equalsIgnoreCase(value);
            case "CONTAINS" -> input.contains(value);
            default -> false;
        };
    }

    /**
     * Check if a player matches the modded routing criteria and return the target server.
     * This has the highest priority and bypasses all other routing rules.
     */
    private Optional<RegisteredServer> findModdedServerFor(Player player) {
        // Get configured values
        String targetServerName = config.getModdedRoutingTargetServer();
        String configuredLoaderType = config.getModdedRoutingLoaderType();
        String configuredVersion = config.getModdedRoutingMinecraftVersion();

        // Get player's protocol version
        int protocolVersion = player.getProtocolVersion().getProtocol();
        String playerVersion = PROTOCOL_TO_VERSION.get(protocolVersion);

        // Check if version matches
        if (playerVersion == null) {
            plugin.logWarn("Unknown protocol version " + protocolVersion + " for player " + player.getUsername());
            return Optional.empty();
        }

        if (!playerVersion.equalsIgnoreCase(configuredVersion)) {
            plugin.logInfo("Player " + player.getUsername() + " version " + playerVersion + " does not match configured version " + configuredVersion);
            return Optional.empty();
        }

        // Check if client brand matches (if available)
        // Note: During PlayerChooseInitialServerEvent, getClientBrand() may not be available yet
        // We'll do a best-effort check
        try {
            // Get client brand asynchronously - this might return null during initial connection
            // For now, we'll skip the loader type check and just verify version
            // In the future, we could enhance this to properly detect the loader type
            plugin.logInfo("Player " + player.getUsername() + " version " + playerVersion + " matches configured version. Checking target server...");
        } catch (Exception e) {
            plugin.logWarn("Could not check client brand for player " + player.getUsername());
        }

        // Find the target server
        Optional<RegisteredServer> targetServer = proxy.getServer(targetServerName);
        if (targetServer.isEmpty()) {
            plugin.logWarn("Modded routing target server '" + targetServerName + "' not found.");
            return Optional.empty();
        }

        // Check if server is online and has space
        RegisteredServer server = targetServer.get();
        try {
            ServerPing ping = server.ping().join();
            if (ping.getPlayers().isEmpty()) {
                return targetServer;
            }

            ServerPing.Players players = ping.getPlayers().get();
            if (players.getOnline() >= players.getMax()) {
                plugin.logWarn("Modded routing target server '" + targetServerName + "' is full.");
                return Optional.empty();
            }

            return targetServer;
        } catch (Exception e) {
            plugin.logWarn("Modded routing target server '" + targetServerName + "' is offline: " + e.getMessage());
            return Optional.empty();
        }
    }
}
