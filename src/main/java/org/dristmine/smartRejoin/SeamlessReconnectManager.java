package org.dristmine.smartRejoin;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.ReferenceCountUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Retains an already loaded client world while its backend restarts. All session
 * and pipeline mutations run on that player's Netty event loop. No world is
 * stored or replayed on a subsequent login.
 */
public final class SeamlessReconnectManager {
    private static final String CLIENT_HOOK = "smartrejoin-freeze-client";
    private static final String BACKEND_HOOK = "smartrejoin-freeze-backend";
    private final SmartRejoin plugin;
    private final java.util.function.LongSupplier recoveryClock;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<String> unavailable = ConcurrentHashMap.newKeySet();
    private final Set<String> pingsInFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recoveryAfter = new ConcurrentHashMap<>();
    private final Map<String, Integer> successfulPings = new ConcurrentHashMap<>();
    private ScheduledTask monitor;
    private boolean registeredLeave;
    private volatile boolean stopped;

    public SeamlessReconnectManager(SmartRejoin plugin) { this(plugin, System::nanoTime); }

    SeamlessReconnectManager(SmartRejoin plugin, java.util.function.LongSupplier recoveryClock) {
        this.plugin = plugin;
        this.recoveryClock = recoveryClock;
    }

    public void start() {
        stopped = false;
        plugin.getServer().getEventManager().register(plugin, this);
        monitor = plugin.getServer().getScheduler().buildTask(plugin, this::monitorServers)
                .repeat(1, TimeUnit.SECONDS).schedule();
        if (!plugin.getServer().getCommandManager().hasCommand("leave")) {
            var meta = plugin.getServer().getCommandManager().metaBuilder("leave").plugin(plugin).build();
            plugin.getServer().getCommandManager().register(meta, (SimpleCommand) invocation -> {
                if (invocation.source() instanceof Player player && isFrozen(player.getUniqueId())) leave(player);
                else invocation.source().sendMessage(Component.text("You are not waiting for a restarting server."));
            });
            registeredLeave = true;
        }
    }

    /** Attach players already online when an administrator enables the feature via /srr. */
    public void refresh() {
        if (!enabled()) return;
        for (Player player : plugin.getServer().getAllPlayers()) {
            if (!isFrozen(player.getUniqueId())) {
                player.getCurrentServer().ifPresent(server ->
                        onServerConnected(new ServerConnectedEvent(player, server.getServer(), null)));
            }
        }
    }

    public boolean isFrozen(UUID id) {
        Session session = sessions.get(id);
        return session != null && session.waiting;
    }

    public boolean isUnavailable(String name) { return unavailable.contains(name.toLowerCase(Locale.ROOT)); }

    private boolean enabled() {
        return !stopped && plugin.getConfigManager().getBoolean("seamless_reconnect.enabled", false);
    }

    /** Checked at failure time so /srr immediately updates backend selection. */
    boolean isEnabledFor(String serverName) {
        if (!enabled()) return false;
        ConfigManager config = plugin.getConfigManager();
        List<String> names = config.getList("seamless_reconnect.whitelist.list");
        return switch (config.getString("seamless_reconnect.whitelist.type", "ALL").toUpperCase(Locale.ROOT)) {
            case "ALL" -> true;
            case "EQUALS" -> names.stream().anyMatch(serverName::equalsIgnoreCase);
            case "CONTAINS" -> names.stream().anyMatch(serverName::contains);
            default -> false;
        };
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onServerConnected(ServerConnectedEvent event) {
        if (!isEnabledFor(event.getServer().getServerInfo().getName()) &&
                !sessions.containsKey(event.getPlayer().getUniqueId())) return;
        Player player = event.getPlayer();
        FrozenProtocol protocol = FrozenProtocol.forVersion(player.getProtocolVersion().getProtocol());
        if (protocol == null || !new AuthenticationGuard(plugin).canRoute(player)) return;
        try {
            Object minecraftConnection = invoke(player, "getConnection");
            Channel client = (Channel) invoke(minecraftConnection, "getChannel");
            Object backend = invoke(player, "getConnectionInFlight");
            if (backend == null) backend = invoke(player, "getConnectedServer");
            if (backend == null) return;
            Channel downstream = (Channel) invoke(invoke(backend, "getConnection"), "getChannel");
            Object backendConnection = backend;
            Runnable attach = () -> {
                Session session = sessions.computeIfAbsent(player.getUniqueId(),
                        id -> new Session(player, client, protocol));
                session.completeReturn();
                session.play = true;
                session.backend = (ServerConnection) backendConnection;
                session.target = event.getServer();
                plugin.logInfo("Seamless tracking attached for " + player.getUsername());
                if (client.pipeline().get(CLIENT_HOOK) == null) {
                    client.pipeline().addBefore("minecraft-decoder", CLIENT_HOOK, session);
                    // Compression encoders sit between decoder and encoder. Observe
                    // outbound packets before compression, independently of input.
                    client.pipeline().addBefore("minecraft-encoder", CLIENT_HOOK + "-out", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
                            session.observeWrite(message);
                            ctx.write(message, promise);
                        }
                    });
                }
                if (downstream.pipeline().get(BACKEND_HOOK) == null) {
                    downstream.pipeline().addBefore("handler", BACKEND_HOOK, new BackendHook(session, backendConnection));
                }
            };
            if (client.eventLoop().inEventLoop()) attach.run();
            else client.eventLoop().submit(attach).syncUninterruptibly();
        } catch (Exception e) {
            plugin.getLogger().warn("Seamless reconnect could not attach for {}; using normal fallback.", player.getUsername(), e);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Session session = sessions.remove(event.getPlayer().getUniqueId());
        if (session != null) session.client.eventLoop().execute(session::cleanup);
    }

    void markUnavailable(RegisteredServer server) {
        String name = server.getServerInfo().getName().toLowerCase(Locale.ROOT);
        unavailable.add(name);
        // A stopping backend may still answer status while kicking players.
        // Do not treat that stale answer as a completed restart.
        long grace = Math.max(20, plugin.getConfigManager().getInt("seamless_reconnect.recovery_grace", 40)) * 50L;
        recoveryAfter.put(name, recoveryClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(grace));
        successfulPings.remove(name);
    }

    private void monitorServers() {
        for (String name : new HashSet<>(unavailable)) {
            if (recoveryClock.getAsLong() < recoveryAfter.getOrDefault(name, 0L)) continue;
            if (!pingsInFlight.add(name)) continue;
            Optional<RegisteredServer> server = plugin.getServer().getServer(name);
            if (server.isEmpty()) { pingsInFlight.remove(name); continue; }
            Long recoveryEpoch = recoveryAfter.get(name);
            server.get().ping().orTimeout(3, TimeUnit.SECONDS).whenComplete((ping, error) -> {
                pingsInFlight.remove(name);
                if (stopped || !Objects.equals(recoveryEpoch, recoveryAfter.get(name))) return;
                if (error != null) {
                    successfulPings.remove(name);
                    return;
                }
                if (successfulPings.merge(name, 1, Integer::sum) < 2) return;
                unavailable.remove(name);
                recoveryAfter.remove(name);
                successfulPings.remove(name);
                for (Session session : sessions.values()) {
                    session.client.eventLoop().execute(() -> {
                        if (session.waiting && !session.connecting && !session.resolvingLeave && session.target.equals(server.get())) {
                            session.connecting = true;
                            long delay = Math.max(0, plugin.getConfigManager().getInt("seamless_reconnect.rejoin_delay", 20)) * 50L;
                            session.client.eventLoop().schedule(() -> session.connect(server.get()), delay, TimeUnit.MILLISECONDS);
                        }
                    });
                }
            });
        }
    }

    public void leave(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) return;
        session.client.eventLoop().execute(() -> {
            if (!session.waiting || session.connecting || session.resolvingLeave) return;
            session.resolvingLeave = true;
            plugin.getServerFinder().findFallbackServer().whenComplete((fallback, error) -> session.client.eventLoop().execute(() -> {
                session.resolvingLeave = false;
                if (!session.waiting || !player.isActive()) return;
                Optional<RegisteredServer> target = error == null ? fallback.filter(server -> !server.equals(session.target)) : Optional.empty();
                if (target.isEmpty()) {
                    target = plugin.getServer().getConfiguration().getAttemptConnectionOrder().stream()
                            .map(name -> plugin.getServer().getServer(name)).flatMap(Optional::stream)
                            .filter(server -> !server.equals(session.target) && !isUnavailable(server.getServerInfo().getName())).findFirst();
                }
                if (target.isPresent()) session.connect(target.get());
                else {
                    session.connecting = false;
                    player.sendMessage(Component.text("No lobby is available. You can try /leave again."));
                }
            }));
        });
    }

    public void stop() {
        stopped = true;
        if (monitor != null) monitor.cancel();
        for (Session session : sessions.values()) {
            session.client.eventLoop().execute(() -> {
                if (session.waiting) session.player.disconnect(Component.text("Proxy shutting down. Please reconnect."));
                session.cleanup();
            });
        }
        sessions.clear();
        unavailable.clear();
        recoveryAfter.clear();
        successfulPings.clear();
        if (registeredLeave) plugin.getServer().getCommandManager().unregister("leave");
    }

    private static Object invoke(Object object, String method) throws ReflectiveOperationException {
        return object.getClass().getMethod(method).invoke(object);
    }

    private static void field(Object object, String name, Object value) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private final class BackendHook extends ChannelInboundHandlerAdapter {
        private final Session session;
        private final Object backend;
        BackendHook(Session session, Object backend) { this.session = session; this.backend = backend; }

        private boolean current() {
            return isEnabledFor(session.target.getServerInfo().getName()) &&
                    session.player.isActive() && session.backend == backend &&
                    session.player.getCurrentServer().orElse(null) == backend;
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object packet) throws Exception {
            if (current() && packet.getClass().getSimpleName().equals("DisconnectPacket")) {
                Object reason = invoke(packet, "getReason");
                Component component = (Component) invoke(reason, "getComponent");
                String text = PlainTextComponentSerializer.plainText().serialize(component).toLowerCase(Locale.ROOT);
                boolean restart = plugin.getConfigManager().getList("seamless_reconnect.restart_kick_messages").stream()
                        .anyMatch(word -> !word.isBlank() && text.contains(word.toLowerCase(Locale.ROOT)));
                if (restart && session.freeze(backend)) {
                    ReferenceCountUtil.release(packet);
                    ctx.close();
                    return;
                }
            }
            ctx.fireChannelRead(packet);
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (current()) {
                Field graceful = backend.getClass().getDeclaredField("gracefulDisconnect");
                graceful.setAccessible(true);
                if (!graceful.getBoolean(backend)) session.freeze(backend);
            }
            ctx.fireChannelInactive(); // Let Velocity clean up its backend player list.
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (current() && session.freeze(backend)) ctx.close();
            else ctx.fireExceptionCaught(cause);
        }
    }

    private final class Session extends ChannelDuplexHandler {
        final Player player;
        final Channel client;
        final FrozenProtocol protocol;
        final FrozenProtocol.Pose pose = new FrozenProtocol.Pose();
        ServerConnection backend;
        RegisteredServer target;
        volatile boolean waiting;
        boolean connecting, resolvingLeave, play = true;
        int teleport = 1_000_000_000;
        long lastActionbar, lastKeepalive, keepaliveId, keepaliveSent;
        boolean awaitingKeepalive;
        io.netty.util.concurrent.ScheduledFuture<?> pulse;

        Session(Player player, Channel client, FrozenProtocol protocol) {
            this.player = player; this.client = client; this.protocol = protocol;
        }

        boolean freeze(Object source) {
            if (!pose.known || !play || !player.isActive()) return false;
            try {
                // Suppress only this backend's automatic kick; retain the client world.
                field(source, "gracefulDisconnect", true);
                if (waiting) return true;
                waiting = true;
                connecting = false;
                markUnavailable(target);
                plugin.getStorageManager().setLastServer(player.getUniqueId(), target.getServerInfo().getName());
                if (plugin.getRejoinQueueManager() != null) plugin.getRejoinQueueManager().removePlayerFromQueues(player.getUniqueId());
                applyFreeze();
                plugin.logInfo("Keeping " + player.getUsername() + " in their frozen world while '" + target.getServerInfo().getName() + "' restarts.");
                pulse = client.eventLoop().scheduleAtFixedRate(this::pulse, 0, 100, TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception e) {
                plugin.getLogger().error("Cannot retain frozen world for {}", player.getUsername(), e);
                return false;
            }
        }

        void applyFreeze() {
            client.write(protocol.ticking(true));
            client.write(protocol.spectator());
            client.write(protocol.abilities());
            client.writeAndFlush(protocol.position(pose, teleport++));
        }

        void pulse() {
            if (!waiting || !player.isActive()) { cleanup(); return; }
            if (connecting) return;
            long now = System.nanoTime();
            if (awaitingKeepalive && now - keepaliveSent > TimeUnit.SECONDS.toNanos(30)) {
                player.disconnect(Component.text("Timed out"));
                return;
            }
            if (!awaitingKeepalive && now - lastKeepalive >= TimeUnit.SECONDS.toNanos(5)) {
                keepaliveId = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                keepaliveSent = lastKeepalive = now;
                awaitingKeepalive = true;
                client.write(protocol.keepalive(keepaliveId));
            }
            client.writeAndFlush(protocol.position(pose, teleport++));
            if (now - lastActionbar >= TimeUnit.SECONDS.toNanos(1)) {
                lastActionbar = now;
                String format = plugin.getConfigManager().getString("seamless_reconnect.actionbar",
                        "The server is restarting, /leave to return to lobby");
                try {
                    player.sendActionBar(MiniMessage.miniMessage().deserialize(format));
                } catch (RuntimeException invalidMessage) {
                    player.sendActionBar(Component.text(format));
                }
            }
        }

        void connect(RegisteredServer destination) {
            if (!waiting || !player.isActive() || stopped) return;
            connecting = true;
            if (play) client.writeAndFlush(protocol.ticking(false));
            try {
                // Velocity otherwise treats a retry to the same stale backend as ALREADY_CONNECTED.
                field(player, "connectedServer", null);
                player.createConnectionRequest(destination).connect().whenComplete((result, error) -> client.eventLoop().execute(() -> {
                    if (!waiting || !player.isActive()) return;
                    if (error == null && result.isSuccessful()) completeReturn();
                    else {
                        connecting = false;
                        markUnavailable(target);
                        // If failure occurred before configuration, the retained world is intact.
                        if (play) applyFreeze();
                        else player.disconnect(Component.text("Unable to reconnect. Please join again."));
                    }
                }));
            } catch (Exception e) {
                connecting = false;
                applyFreeze();
                plugin.getLogger().error("Could not reconnect {}", player.getUsername(), e);
            }
        }

        void completeReturn() {
            waiting = false;
            connecting = false;
            resolvingLeave = false;
            awaitingKeepalive = false;
            if (pulse != null) { pulse.cancel(false); pulse = null; }
        }

        void cleanup() {
            completeReturn();
            if (client.pipeline().context(this) != null) client.pipeline().remove(this);
            if (client.pipeline().get(CLIENT_HOOK + "-out") != null) client.pipeline().remove(CLIENT_HOOK + "-out");
        }

        @Override public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            try {
                handleInput(ctx, message);
            } catch (RuntimeException invalidPacket) {
                ReferenceCountUtil.release(message);
                ctx.fireExceptionCaught(invalidPacket);
            }
        }

        private void handleInput(ChannelHandlerContext ctx, Object message) {
            if (play && message instanceof ByteBuf bytes) {
                ByteBuf in = bytes.duplicate();
                int id = FrozenProtocol.varInt(in);
                if (waiting && !connecting) {
                    if (id == protocol.keepaliveReply && in.readableBytes() == 8) {
                        if (in.readLong() == keepaliveId) awaitingKeepalive = false;
                    } else if (id == protocol.command || id == protocol.signedCommand || id == protocol.chat) {
                        String command = FrozenProtocol.string(in).trim();
                        if (command.equals("leave") || command.equals("/leave")) leave(player);
                    }
                    ReferenceCountUtil.release(message);
                    return; // Every movement, inventory, attack, and use packet is discarded.
                }
                if (!waiting) pose.readMovement(id, in, protocol);
            }
            ctx.fireChannelRead(message);
        }

        void observeWrite(Object message) {
            if (play && message instanceof ByteBuf bytes && !waiting) {
                ByteBuf in = bytes.duplicate();
                int id = FrozenProtocol.varInt(in);
                if (id == protocol.position) pose.readTeleport(in, protocol);
            }
            if (play && message instanceof ByteBuf bytes) {
                ByteBuf in = bytes.duplicate();
                if (FrozenProtocol.varInt(in) == protocol.configuration) play = false;
            }
        }
    }
}
