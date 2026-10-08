package server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import server.config.RouteConfig;
import server.config.ServerConfig;
import server.http.ErrorPages;
import server.http.HttpRequest;
import server.http.HttpResponse;
import server.http.HttpStatus;
import server.http.MimeTypes;
import server.http.MultipartParser;
import server.utils.Cookie;
import server.utils.Session;
import server.utils.SessionManager;

/**
 * Routes a parsed request to the appropriate handler based on the matched route
 * configuration. Handles static files, directory listings, redirects, uploads,
 * DELETE, and CGI dispatch, plus session cookie management.
 */
public final class Router {

    private final SessionManager sessionManager;

    public Router(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public HttpResponse route(HttpRequest request, ServerConfig config) {
        boolean keepAlive = request.keepAlive();
        try {
            // Session handling: ensure every client gets a session cookie.
            String existing = request.getCookies().get(SessionManager.COOKIE_NAME);
            boolean isNew = !sessionManager.exists(existing);
            Session session = sessionManager.getOrCreate(existing);

            RouteConfig route = config.matchRoute(request.getPath());
            if (route == null) {
                return error(HttpStatus.NOT_FOUND, config, keepAlive);
            }

            if (!route.allowsMethod(request.getMethod())) {
                HttpResponse resp = error(HttpStatus.METHOD_NOT_ALLOWED, config, keepAlive);
                resp.header("Allow", String.join(", ", route.methods));
                return attachSession(resp, session, isNew);
            }

            HttpResponse response;
            if (route.redirect != null) {
                response = new HttpResponse()
                        .status(HttpStatus.FOUND)
                        .header("Location", route.redirect)
                        .keepAlive(keepAlive)
                        .contentType("text/html; charset=utf-8")
                        .body("<html><body>Redirecting to <a href=\"" + route.redirect
                                + "\">" + route.redirect + "</a></body></html>");
                return attachSession(response, session, isNew);
            }

            response = switch (request.getMethod()) {
                case "GET" -> handleGet(request, config, route, keepAlive);
                case "POST" -> handlePost(request, config, route, keepAlive);
                case "DELETE" -> handleDelete(request, config, route, keepAlive);
                default -> error(HttpStatus.NOT_IMPLEMENTED, config, keepAlive);
            };
            return attachSession(response, session, isNew);
        } catch (Exception e) {
            // Never let a handler crash the server; degrade to a 500 page.
            System.err.println("[Router] Unhandled error: " + e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, config, keepAlive);
        }
    }

    private HttpResponse attachSession(HttpResponse response, Session session, boolean isNew) {
        if (isNew) {
            response.addCookie(new Cookie(SessionManager.COOKIE_NAME, session.getId())
                    .path("/").httpOnly(true).maxAge(1800).toHeader());
        }
        return response;
    }

    private HttpResponse handleGet(HttpRequest request, ServerConfig config,
                                   RouteConfig route, boolean keepAlive) throws IOException {
        Path resolved = resolvePath(request, route);
        if (resolved == null) {
            return error(HttpStatus.FORBIDDEN, config, keepAlive);
        }

        // CGI dispatch by extension.
        String cgiInterpreter = matchCgi(route, resolved);
        if (cgiInterpreter != null && Files.isRegularFile(resolved)) {
            return new CGIHandler(cgiInterpreter).execute(request, resolved, route.root).keepAlive(keepAlive);
        }

        if (Files.isDirectory(resolved)) {
            // Try index / default file first.
            String indexName = route.index != null ? route.index
                    : (route.defaultFile != null ? route.defaultFile : null);
            if (indexName != null) {
                Path indexPath = resolved.resolve(indexName);
                if (Files.isRegularFile(indexPath)) {
                    return serveFile(indexPath, keepAlive);
                }
            }
            if (route.directoryListing) {
                return directoryListing(resolved, request.getPath(), keepAlive);
            }
            return error(HttpStatus.FORBIDDEN, config, keepAlive);
        }

        if (Files.isRegularFile(resolved)) {
            return serveFile(resolved, keepAlive);
        }
        return error(HttpStatus.NOT_FOUND, config, keepAlive);
    }

    private HttpResponse handlePost(HttpRequest request, ServerConfig config,
                                    RouteConfig route, boolean keepAlive) throws IOException {
        Path resolved = resolvePath(request, route);

        // CGI dispatch takes precedence for POST too.
        if (resolved != null) {
            String cgiInterpreter = matchCgi(route, resolved);
            if (cgiInterpreter != null && Files.isRegularFile(resolved)) {
                return new CGIHandler(cgiInterpreter).execute(request, resolved, route.root).keepAlive(keepAlive);
            }
        }

        String contentType = request.getHeader("content-type", "");
        if (contentType.toLowerCase().startsWith("multipart/form-data")) {
            return handleUpload(request, config, route, keepAlive);
        }

        // Generic POST: echo receipt (useful for forms / API bodies).
        String summary = "Received " + request.getBody().length + " bytes via POST to "
                + request.getPath();
        return new HttpResponse()
                .status(HttpStatus.OK)
                .contentType("text/plain; charset=utf-8")
                .keepAlive(keepAlive)
                .body(summary);
    }

    private HttpResponse handleUpload(HttpRequest request, ServerConfig config,
                                      RouteConfig route, boolean keepAlive) throws IOException {
        String uploadDir = route.uploadDir != null ? route.uploadDir : route.root;
        if (uploadDir == null) {
            return error(HttpStatus.FORBIDDEN, config, keepAlive);
        }
        Path dir = Path.of(uploadDir);
        Files.createDirectories(dir);

        List<MultipartParser.Part> parts = MultipartParser.parse(
                request.getBody(), request.getHeader("content-type"));
        StringBuilder saved = new StringBuilder();
        int count = 0;
        for (MultipartParser.Part part : parts) {
            if (part.isFile()) {
                String safe = sanitizeFilename(part.filename);
                Path target = dir.resolve(safe).normalize();
                if (!target.startsWith(dir.normalize())) {
                    continue; // path traversal guard
                }
                Files.write(target, part.content);
                saved.append(safe).append(" (").append(part.content.length).append(" bytes)\n");
                count++;
            }
        }
        if (count == 0) {
            return new HttpResponse()
                    .status(HttpStatus.BAD_REQUEST)
                    .contentType("text/plain; charset=utf-8")
                    .keepAlive(keepAlive)
                    .body("No files found in upload");
        }
        return new HttpResponse()
                .status(HttpStatus.CREATED)
                .contentType("text/plain; charset=utf-8")
                .keepAlive(keepAlive)
                .body("Uploaded " + count + " file(s):\n" + saved);
    }

    private HttpResponse handleDelete(HttpRequest request, ServerConfig config,
                                      RouteConfig route, boolean keepAlive) throws IOException {
        Path resolved = resolvePath(request, route);
        if (resolved == null) {
            return error(HttpStatus.FORBIDDEN, config, keepAlive);
        }
        if (!Files.exists(resolved)) {
            return error(HttpStatus.NOT_FOUND, config, keepAlive);
        }
        if (Files.isDirectory(resolved)) {
            return error(HttpStatus.FORBIDDEN, config, keepAlive);
        }
        Files.delete(resolved);
        return new HttpResponse()
                .status(HttpStatus.OK)
                .contentType("text/plain; charset=utf-8")
                .keepAlive(keepAlive)
                .body("Deleted " + request.getPath());
    }

    private HttpResponse serveFile(Path path, boolean keepAlive) throws IOException {
        byte[] content = Files.readAllBytes(path);
        return new HttpResponse()
                .status(HttpStatus.OK)
                .contentType(MimeTypes.forPath(path.toString()))
                .keepAlive(keepAlive)
                .body(content);
    }

    private HttpResponse directoryListing(Path dir, String urlPath, boolean keepAlive) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        sb.append("<title>Index of ").append(escape(urlPath)).append("</title>");
        sb.append("<style>body{font-family:system-ui,sans-serif;max-width:800px;margin:40px auto;"
                + "padding:0 16px}h1{color:#0f172a}a{color:#2563eb;text-decoration:none}"
                + "a:hover{text-decoration:underline}li{margin:4px 0}</style></head><body>");
        sb.append("<h1>Index of ").append(escape(urlPath)).append("</h1><ul>");
        if (!urlPath.equals("/")) {
            String parent = urlPath.endsWith("/") ? urlPath.substring(0, urlPath.length() - 1) : urlPath;
            parent = parent.substring(0, parent.lastIndexOf('/') + 1);
            sb.append("<li><a href=\"").append(escape(parent)).append("\">../</a></li>");
        }
        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.sorted().toList();
            String base = urlPath.endsWith("/") ? urlPath : urlPath + "/";
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                boolean isDir = Files.isDirectory(entry);
                String display = isDir ? name + "/" : name;
                sb.append("<li><a href=\"").append(escape(base + name))
                        .append("\">").append(escape(display)).append("</a></li>");
            }
        }
        sb.append("</ul></body></html>");
        return new HttpResponse()
                .status(HttpStatus.OK)
                .contentType("text/html; charset=utf-8")
                .keepAlive(keepAlive)
                .body(sb.toString());
    }

    /**
     * Resolves the request path to a file under the route root, enforcing that
     * the result stays within the root (no path traversal). Returns null when
     * the request escapes the root.
     */
    private Path resolvePath(HttpRequest request, RouteConfig route) {
        if (route.root == null) {
            return null;
        }
        Path root = Path.of(route.root).toAbsolutePath().normalize();
        String relative = request.getPath();
        if (!route.path.equals("/") && relative.startsWith(route.path)) {
            relative = relative.substring(route.path.length());
        }
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            return null; // path traversal attempt
        }
        return resolved;
    }

    private String matchCgi(RouteConfig route, Path path) {
        if (!route.hasCgi() || path == null) {
            return null;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String ext = name.substring(dot);
        return route.cgi.get(ext);
    }

    private String sanitizeFilename(String filename) {
        String name = filename.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            name = "upload_" + System.currentTimeMillis();
        }
        return name;
    }

    private HttpResponse error(HttpStatus status, ServerConfig config, boolean keepAlive) {
        return ErrorPages.build(status, config, keepAlive);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
