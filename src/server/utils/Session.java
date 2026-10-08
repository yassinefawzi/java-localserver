package server.utils;

import java.util.HashMap;
import java.util.Map;

/** A single user session holding arbitrary attributes with an expiry. */
public final class Session {

    private final String id;
    private final Map<String, String> attributes = new HashMap<>();
    private long lastAccess;

    public Session(String id) {
        this.id = id;
        this.lastAccess = System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public void touch() {
        this.lastAccess = System.currentTimeMillis();
    }

    public long getLastAccess() {
        return lastAccess;
    }

    public void set(String key, String value) {
        attributes.put(key, value);
    }

    public String get(String key) {
        return attributes.get(key);
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
