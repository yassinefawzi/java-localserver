package server.utils;

/** Helper for constructing Set-Cookie header values. */
public final class Cookie {

    private final String name;
    private final String value;
    private String path = "/";
    private int maxAge = -1;
    private boolean httpOnly = false;
    private String sameSite = "Lax";

    public Cookie(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public Cookie path(String path) {
        this.path = path;
        return this;
    }

    public Cookie maxAge(int seconds) {
        this.maxAge = seconds;
        return this;
    }

    public Cookie httpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    public Cookie sameSite(String sameSite) {
        this.sameSite = sameSite;
        return this;
    }

    /** Builds the value for a Set-Cookie header. */
    public String toHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value);
        if (path != null) {
            sb.append("; Path=").append(path);
        }
        if (maxAge >= 0) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (sameSite != null) {
            sb.append("; SameSite=").append(sameSite);
        }
        return sb.toString();
    }
}
