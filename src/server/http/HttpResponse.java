package server.http;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds an HTTP/1.1 response into a byte buffer ready for the socket. */
public final class HttpResponse {

    private HttpStatus status = HttpStatus.OK;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final List<String> cookies = new ArrayList<>();
    private byte[] body = new byte[0];
    private boolean keepAlive = true;

    public HttpResponse() {
        headers.put("Server", "JavaLocalServer/1.0");
    }

    public HttpResponse status(HttpStatus status) {
        this.status = status;
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse addCookie(String cookie) {
        cookies.add(cookie);
        return this;
    }

    public HttpResponse body(byte[] body) {
        this.body = body == null ? new byte[0] : body;
        return this;
    }

    public HttpResponse body(String body) {
        return body(body.getBytes(StandardCharsets.UTF_8));
    }

    public HttpResponse contentType(String type) {
        return header("Content-Type", type);
    }

    public HttpResponse keepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
        return this;
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    /** Serializes the response (status line + headers + body) into bytes. */
    public byte[] toBytes() {
        headers.putIfAbsent("Content-Type", "text/plain; charset=utf-8");
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("Date", httpDate());
        headers.put("Connection", keepAlive ? "keep-alive" : "close");

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status.code).append(' ').append(status.reason).append("\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        for (String cookie : cookies) {
            sb.append("Set-Cookie: ").append(cookie).append("\r\n");
        }
        sb.append("\r\n");

        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + body.length);
        out.writeBytes(head);
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static String httpDate() {
        return DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
    }
}
