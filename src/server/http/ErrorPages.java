package server.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import server.config.ServerConfig;

/** Produces error responses, preferring configured custom pages. */
public final class ErrorPages {

    private ErrorPages() {
    }

    /** Builds a complete error response for the given status. */
    public static HttpResponse build(HttpStatus status, ServerConfig config, boolean keepAlive) {
        byte[] body = null;
        if (config != null && config.errorPages.containsKey(status.code)) {
            String path = config.errorPages.get(status.code);
            try {
                Path p = Path.of(path);
                if (Files.exists(p) && Files.isReadable(p)) {
                    body = Files.readAllBytes(p);
                }
            } catch (IOException ignored) {
                // fall back to the generated page
            }
        }
        if (body == null) {
            body = defaultPage(status).getBytes(StandardCharsets.UTF_8);
        }
        // On 4xx/5xx we generally close the connection to be safe, except 404/403
        // which are recoverable. Keep-alive is preserved for client convenience.
        return new HttpResponse()
                .status(status)
                .contentType("text/html; charset=utf-8")
                .keepAlive(keepAlive)
                .body(body);
    }

    public static String defaultPage(HttpStatus status) {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<title>" + status.code + " " + status.reason + "</title>\n"
                + "<style>body{font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
                + ".box{text-align:center}h1{font-size:5rem;margin:0;color:#38bdf8}"
                + "p{font-size:1.25rem;color:#94a3b8}</style>\n</head>\n<body>\n"
                + "<div class=\"box\"><h1>" + status.code + "</h1>"
                + "<p>" + status.reason + "</p></div>\n</body>\n</html>\n";
    }
}
