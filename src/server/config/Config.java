package server.config;

import java.util.ArrayList;
import java.util.List;

/** Top-level configuration holding all virtual servers. */
public final class Config {

    public final List<ServerConfig> servers = new ArrayList<>();

    /** Returns the server marked as default, or the first server if none is. */
    public ServerConfig getDefaultServer() {
        for (ServerConfig s : servers) {
            if (s.isDefault) {
                return s;
            }
        }
        return servers.isEmpty() ? null : servers.get(0);
    }
}
