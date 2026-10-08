package server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Loads and validates the JSON configuration file into strongly-typed objects.
 * Fails fast with a clear message when the configuration is invalid.
 */
public final class ConfigLoader {

    public static Config load(String path) throws IOException {
        String text = Files.readString(Path.of(path));
        Object root = Json.parse(text);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("Config root must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> rootMap = (Map<String, Object>) root;

        Object serversObj = rootMap.get("servers");
        if (!(serversObj instanceof List) || ((List<?>) serversObj).isEmpty()) {
            throw new IllegalArgumentException("Config must contain a non-empty 'servers' array");
        }

        Config config = new Config();
        for (Object serverObj : (List<?>) serversObj) {
            config.servers.add(parseServer(asObject(serverObj, "server entry")));
        }
        validate(config);
        return config;
    }

    private static ServerConfig parseServer(Map<String, Object> map) {
        ServerConfig sc = new ServerConfig();
        sc.serverName = asString(map.get("server_name"), "");
        sc.host = asString(map.get("host"), "127.0.0.1");
        sc.isDefault = asBool(map.get("default"), false);

        Object bodySize = map.get("client_max_body_size");
        if (bodySize instanceof Number) {
            sc.clientMaxBodySize = ((Number) bodySize).longValue();
        }

        Object portsObj = map.get("ports");
        if (portsObj instanceof List) {
            for (Object p : (List<?>) portsObj) {
                if (p instanceof Number) {
                    sc.ports.add(((Number) p).intValue());
                }
            }
        }
        if (sc.ports.isEmpty()) {
            throw new IllegalArgumentException("Server '" + sc.serverName + "' must define at least one port");
        }

        Object errorsObj = map.get("error_pages");
        if (errorsObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> errors = (Map<String, Object>) errorsObj;
            for (Map.Entry<String, Object> e : errors.entrySet()) {
                try {
                    sc.errorPages.put(Integer.parseInt(e.getKey()), String.valueOf(e.getValue()));
                } catch (NumberFormatException ignored) {
                    // skip non-numeric status keys
                }
            }
        }

        Object routesObj = map.get("routes");
        if (routesObj instanceof List) {
            for (Object r : (List<?>) routesObj) {
                sc.routes.add(parseRoute(asObject(r, "route")));
            }
        }
        if (sc.routes.isEmpty()) {
            throw new IllegalArgumentException("Server '" + sc.serverName + "' must define at least one route");
        }
        return sc;
    }

    private static RouteConfig parseRoute(Map<String, Object> map) {
        RouteConfig rc = new RouteConfig();
        rc.path = asString(map.get("path"), "/");
        rc.root = asString(map.get("root"), null);
        rc.index = asString(map.get("index"), null);
        rc.redirect = asString(map.get("redirect"), null);
        rc.uploadDir = asString(map.get("upload_dir"), null);
        rc.defaultFile = asString(map.get("default_file"), null);
        rc.directoryListing = asBool(map.get("directory_listing"), false);

        Object methodsObj = map.get("methods");
        if (methodsObj instanceof List) {
            for (Object m : (List<?>) methodsObj) {
                rc.methods.add(String.valueOf(m).toUpperCase());
            }
        }

        Object cgiObj = map.get("cgi");
        if (cgiObj instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cgi = (Map<String, Object>) cgiObj;
            for (Map.Entry<String, Object> e : cgi.entrySet()) {
                rc.cgi.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return rc;
    }

    private static void validate(Config config) {
        int defaults = 0;
        for (ServerConfig sc : config.servers) {
            if (sc.isDefault) {
                defaults++;
            }
            if (sc.clientMaxBodySize < 0) {
                throw new IllegalArgumentException("client_max_body_size must be >= 0");
            }
            for (int port : sc.ports) {
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("Invalid port: " + port);
                }
            }
            for (RouteConfig rc : sc.routes) {
                if (!rc.path.startsWith("/")) {
                    throw new IllegalArgumentException("Route path must start with '/': " + rc.path);
                }
                if (rc.redirect == null && rc.root == null && !rc.hasCgi()) {
                    throw new IllegalArgumentException(
                            "Route '" + rc.path + "' must define a root, redirect, or cgi");
                }
            }
        }
        if (defaults > 1) {
            throw new IllegalArgumentException("Only one server may be marked as default");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object o, String what) {
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("Expected object for " + what);
        }
        return (Map<String, Object>) o;
    }

    private static String asString(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static boolean asBool(Object o, boolean def) {
        return o instanceof Boolean ? (Boolean) o : def;
    }
}
