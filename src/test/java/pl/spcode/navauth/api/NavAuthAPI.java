package pl.spcode.navauth.api;

import java.util.UUID;

/** Test double for the optional public API loaded through NavAuth's class loader. */
public class NavAuthAPI {
    public static boolean authenticated;
    public static NavAuthAPI getInstance() { return new NavAuthAPI(); }
    public boolean isAuthenticated(UUID id) { return authenticated; }
}
