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

    public ServerFinder(SmartRejoin plugin) {
        this.plugin = plugin;
        this.proxy = plugin.getServer();
        this.config = plugin.getConfigManager();
    }

    public CompletableFuture<Optional<RegisteredServer>> findServerFor(Player player, String lastServerName) {
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
}
