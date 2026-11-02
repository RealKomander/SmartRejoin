package org.dristmine.smartRejoin;

import com.velocitypowered.api.proxy.Player;

/** Optional NavAuth integration without bundling or requiring its API. */
final class AuthenticationGuard {
    private final SmartRejoin plugin;

    AuthenticationGuard(SmartRejoin plugin) {
        this.plugin = plugin;
    }

    boolean canRoute(Player player) {
        var auth = plugin.getServer().getPluginManager().getPlugin("navauth");
        if (auth.isEmpty()) return true;
        try {
            Object instance = auth.get().getInstance().orElseThrow();
            Class<?> api = Class.forName("pl.spcode.navauth.api.NavAuthAPI", true,
                    instance.getClass().getClassLoader());
            Object service = api.getMethod("getInstance").invoke(null);
            return service != null && Boolean.TRUE.equals(
                    api.getMethod("isAuthenticated", java.util.UUID.class).invoke(service, player.getUniqueId()));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Leave authentication routing in control if the API is unavailable.
            plugin.logWarn("Cannot check NavAuth authentication; deferring SmartRejoin routing: " + e.getMessage());
            return false;
        }
    }
}
