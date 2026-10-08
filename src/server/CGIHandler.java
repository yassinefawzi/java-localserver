package server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import server.http.HttpRequest;
import server.http.HttpResponse;
import server.http.HttpStatus;

/**
 * Executes CGI scripts via ProcessBuilder. The script is passed as the first
 * argument to the interpreter, request metadata is exposed through CGI
 * environment variables (including PATH_INFO), and the request body is written
 * to the script's stdin. The script's stdout is parsed as a CGI response.
 */
public final class CGIHandler {

    private static final long CGI_TIMEOUT_SECONDS = 15;

    private final String interpreter;

    public CGIHandler(String interpreter) {
        this.interpreter = interpreter;
    }

    public HttpResponse execute(HttpRequest request, Path scriptPath, String scriptRoot) {
        try {
            ProcessBuilder pb = new ProcessBuilder(interpreter, scriptPath.toAbsolutePath().toString());
            pb.directory(scriptPath.toAbsolutePath().getParent().toFile());
            pb.redirectErrorStream(false);

            Map<String, String> env = pb.environment();
            env.clear();
            env.put("GATEWAY_INTERFACE", "CGI/1.1");
            env.put("SERVER_PROTOCOL", "HTTP/1.1");
            env.put("SERVER_SOFTWARE", "JavaLocalServer/1.0");
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("QUERY_STRING", request.getQueryString());
            env.put("SCRIPT_NAME", request.getPath());
            env.put("SCRIPT_FILENAME", scriptPath.toAbsolutePath().toString());
            env.put("PATH_INFO", scriptPath.toAbsolutePath().toString());
            env.put("PATH_TRANSLATED", scriptPath.toAbsolutePath().toString());
            env.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
            env.put("REDIRECT_STATUS", "200"); // required by some CGI runtimes (e.g. php-cgi)

            byte[] body = request.getBody();
            if (body != null && body.length > 0) {
                env.put("CONTENT_LENGTH", String.valueOf(body.length));
                String ct = request.getHeader("content-type");
                if (ct != null) {
                    env.put("CONTENT_TYPE", ct);
                }
            }
            // Expose selected request headers as HTTP_* variables.
            for (Map.Entry<String, String> h : request.getHeaders().entrySet()) {
                String key = "HTTP_" + h.getKey().toUpperCase().replace('-', '_');
                env.put(key, h.getValue());
            }

            Process process = pb.start();

            if (body != null && body.length > 0) {
                try (OutputStream stdin = process.getOutputStream()) {
                    stdin.write(body);
                    stdin.flush();
                } catch (IOException ignored) {
                    // script may not read stdin
                }
            } else {
                process.getOutputStream().close();
            }

            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderr = process.getErrorStream().readAllBytes();

            boolean finished = process.waitFor(CGI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "CGI script timed out");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                String msg = new String(stderr, StandardCharsets.UTF_8);
                return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                        "CGI script exited with code " + exit + "\n" + msg);
            }

            return parseCgiOutput(stdout);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "CGI execution failed: " + e.getMessage());
        }
    }

    /**
     * Parses CGI output which may begin with header lines (e.g. Content-Type,
     * Status) followed by a blank line and the body.
     */
    private HttpResponse parseCgiOutput(byte[] output) {
        int headerEnd = findHeaderEnd(output);
        HttpResponse response = new HttpResponse();
        response.status(HttpStatus.OK);

        if (headerEnd < 0) {
            // No headers, treat entire output as an HTML body.
            return response.contentType("text/html; charset=utf-8").body(output);
        }

        String headerBlock = new String(output, 0, headerEnd, StandardCharsets.ISO_8859_1);
        boolean hasContentType = false;
        for (String line : headerBlock.split("\r?\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Status")) {
                try {
                    int code = Integer.parseInt(value.split(" ")[0]);
                    response.status(HttpStatus.fromCode(code));
                } catch (NumberFormatException ignored) {
                    // keep 200
                }
            } else if (name.equalsIgnoreCase("Content-Type")) {
                response.contentType(value);
                hasContentType = true;
            } else {
                response.header(name, value);
            }
        }
        if (!hasContentType) {
            response.contentType("text/html; charset=utf-8");
        }

        int bodyStart = headerEnd;
        // skip the blank line separator
        if (bodyStart + 3 < output.length && output[bodyStart] == '\r'
                && output[bodyStart + 1] == '\n') {
            bodyStart += 2;
        } else if (bodyStart + 1 < output.length && output[bodyStart] == '\n') {
            bodyStart += 1;
        }
        int len = Math.max(0, output.length - bodyStart);
        byte[] bodyBytes = new byte[len];
        System.arraycopy(output, bodyStart, bodyBytes, 0, len);
        return response.body(bodyBytes);
    }

    private static int findHeaderEnd(byte[] out) {
        for (int i = 0; i < out.length - 1; i++) {
            if (out[i] == '\n' && out[i + 1] == '\n') {
                return i; // LF LF
            }
            if (i < out.length - 3 && out[i] == '\r' && out[i + 1] == '\n'
                    && out[i + 2] == '\r' && out[i + 3] == '\n') {
                return i; // CRLF CRLF
            }
        }
        return -1;
    }

    private HttpResponse errorResponse(HttpStatus status, String message) {
        return new HttpResponse()
                .status(status)
                .contentType("text/plain; charset=utf-8")
                .body(message);
    }
}
