package server.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Represents a fully parsed HTTP request. */
public final class HttpRequest {

    private String method = "";
    private String rawTarget = "";
    private String path = "";
    private String queryString = "";
    private String version = "HTTP/1.1";
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, String> cookies = new HashMap<>();
    private byte[] body = new byte[0];

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getRawTarget() {
        return rawTarget;
    }

    public void setTarget(String target) {
        this.rawTarget = target;
        int q = target.indexOf('?');
        if (q >= 0) {
            this.path = decode(target.substring(0, q));
            this.queryString = target.substring(q + 1);
        } else {
            this.path = decode(target);
            this.queryString = "";
        }
    }

    public String getPath() {
        return path;
    }

    public String getQueryString() {
        return queryString;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    /** Header names are stored lowercased for case-insensitive lookup. */
    public void addHeader(String name, String value) {
        headers.put(name.toLowerCase(), value);
    }

    public String getHeader(String name) {
        return headers.get(name.toLowerCase());
    }

    public String getHeader(String name, String def) {
        return headers.getOrDefault(name.toLowerCase(), def);
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public void setBody(byte[] body) {
        this.body = body == null ? new byte[0] : body;
    }

    public Map<String, String> getCookies() {
        return cookies;
    }

    public boolean keepAlive() {
        String conn = getHeader("connection");
        if (conn != null) {
            return conn.toLowerCase().contains("keep-alive")
                    || (!conn.toLowerCase().contains("close") && version.equals("HTTP/1.1"));
        }
        return version.equals("HTTP/1.1");
    }

    /** Parses the Cookie header into the cookies map. */
    public void parseCookies() {
        String cookieHeader = getHeader("cookie");
        if (cookieHeader == null) {
            return;
        }
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                cookies.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
    }

    private static String decode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int value = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    sb.append((char) value);
                    i += 2;
                } catch (NumberFormatException e) {
                    sb.append(c);
                }
            } else if (c == '+') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Splits a comma or ampersand separated query string into key/value pairs. */
    public Map<String, String> getQueryParams() {
        Map<String, String> params = new HashMap<>();
        if (queryString.isEmpty()) {
            return params;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            } else {
                params.put(decode(pair), "");
            }
        }
        return params;
    }

    public static List<String> supportedMethods() {
        List<String> methods = new ArrayList<>();
        methods.add("GET");
        methods.add("POST");
        methods.add("DELETE");
        return methods;
    }
}
