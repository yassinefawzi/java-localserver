package server.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Configuration for a single virtual server (host + one or more ports). */
public final class ServerConfig {

    public String serverName = "";
    public String host = "127.0.0.1";
    public List<Integer> ports = new ArrayList<>();
    public boolean isDefault = false;
    public long clientMaxBodySize = 1024 * 1024; // 1 MB default
    public Map<Integer, String> errorPages = new HashMap<>();
    public List<RouteConfig> routes = new ArrayList<>();

    /**
     * Finds the best matching route for a request path using longest-prefix
     * matching. Returns null if no route matches.
     */
    public RouteConfig matchRoute(String requestPath) {
        RouteConfig best = null;
        int bestLen = -1;
        for (RouteConfig route : routes) {
            String rp = route.path;
            boolean matches;
            if (rp.equals("/")) {
                matches = true;
            } else if (requestPath.equals(rp)) {
                matches = true;
            } else {
                matches = requestPath.startsWith(rp.endsWith("/") ? rp : rp + "/");
            }
            if (matches && rp.length() > bestLen) {
                best = route;
                bestLen = rp.length();
            }
        }
        return best;
    }
}
