package server.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Configuration for a single route (location block). */
public final class RouteConfig {

    public String path = "/";
    public Set<String> methods = new HashSet<>();
    public String root;
    public String index;
    public boolean directoryListing = false;
    public String redirect;
    public String uploadDir;
    /** Maps a file extension (including the dot) to the interpreter path. */
    public Map<String, String> cgi = new HashMap<>();
    /** Default response file served when a directory is requested. */
    public String defaultFile;

    public boolean allowsMethod(String method) {
        return methods.isEmpty() || methods.contains(method.toUpperCase());
    }

    public boolean hasCgi() {
        return cgi != null && !cgi.isEmpty();
    }
}
