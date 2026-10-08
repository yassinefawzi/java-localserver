package server.utils;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store. Sessions expire after a period of inactivity and are
 * pruned lazily. Thread-safe storage is used even though the server is single
 * threaded, to keep the component reusable and future-proof.
 */
public final class SessionManager {

    private static final long SESSION_TTL_MS = 30 * 60 * 1000L; // 30 minutes
    public static final String COOKIE_NAME = "SESSIONID";

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Returns the existing session for the id, or creates a new one. */
    public Session getOrCreate(String existingId) {
        prune();
        if (existingId != null) {
            Session s = sessions.get(existingId);
            if (s != null) {
                s.touch();
                return s;
            }
        }
        String id = newId();
        Session session = new Session(id);
        sessions.put(id, session);
        return session;
    }

    public Session get(String id) {
        if (id == null) {
            return null;
        }
        Session s = sessions.get(id);
        if (s != null) {
            s.touch();
        }
        return s;
    }

    public boolean exists(String id) {
        return id != null && sessions.containsKey(id);
    }

    private String newId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Session> e = it.next();
            if (now - e.getValue().getLastAccess() > SESSION_TTL_MS) {
                it.remove();
            }
        }
    }
}
