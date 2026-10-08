package server.http;

import java.util.HashMap;
import java.util.Map;

/** Maps file extensions to MIME types for the Content-Type header. */
public final class MimeTypes {

    private static final Map<String, String> TYPES = new HashMap<>();

    static {
        TYPES.put("html", "text/html; charset=utf-8");
        TYPES.put("htm", "text/html; charset=utf-8");
        TYPES.put("css", "text/css; charset=utf-8");
        TYPES.put("js", "application/javascript; charset=utf-8");
        TYPES.put("json", "application/json; charset=utf-8");
        TYPES.put("txt", "text/plain; charset=utf-8");
        TYPES.put("xml", "application/xml; charset=utf-8");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("gif", "image/gif");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("ico", "image/x-icon");
        TYPES.put("webp", "image/webp");
        TYPES.put("pdf", "application/pdf");
        TYPES.put("zip", "application/zip");
        TYPES.put("mp4", "video/mp4");
        TYPES.put("mp3", "audio/mpeg");
        TYPES.put("wav", "audio/wav");
        TYPES.put("woff", "font/woff");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("ttf", "font/ttf");
    }

    private MimeTypes() {
    }

    public static String forPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase();
            String type = TYPES.get(ext);
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }
}
