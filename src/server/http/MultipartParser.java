package server.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses multipart/form-data request bodies into individual parts, extracting
 * uploaded files and their metadata. Operates on the raw byte body so binary
 * uploads are preserved exactly.
 */
public final class MultipartParser {

    /** A single part of a multipart body. */
    public static final class Part {
        public String name;
        public String filename;
        public String contentType;
        public byte[] content;

        public boolean isFile() {
            return filename != null && !filename.isEmpty();
        }
    }

    private MultipartParser() {
    }

    public static List<Part> parse(byte[] body, String contentTypeHeader) {
        List<Part> parts = new ArrayList<>();
        String boundary = extractBoundary(contentTypeHeader);
        if (boundary == null) {
            return parts;
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);

        int pos = 0;
        List<Integer> boundaries = new ArrayList<>();
        while (pos <= body.length - delimiter.length) {
            if (matchesAt(body, pos, delimiter)) {
                boundaries.add(pos);
                pos += delimiter.length;
            } else {
                pos++;
            }
        }

        for (int i = 0; i < boundaries.size() - 1; i++) {
            int start = boundaries.get(i) + delimiter.length;
            int end = boundaries.get(i + 1);
            // Skip CRLF after the delimiter
            if (start + 1 < body.length && body[start] == '\r' && body[start + 1] == '\n') {
                start += 2;
            } else if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') {
                continue; // closing boundary marker
            }
            // Trim the trailing CRLF before the next boundary
            int contentEnd = end;
            if (contentEnd - 2 >= start && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }
            Part part = parsePart(body, start, contentEnd);
            if (part != null) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static Part parsePart(byte[] body, int start, int end) {
        int headerEnd = findHeaderEnd(body, start, end);
        if (headerEnd < 0) {
            return null;
        }
        String headerBlock = new String(body, start, headerEnd - start, StandardCharsets.ISO_8859_1);
        Part part = new Part();
        for (String line : headerBlock.split("\r\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-disposition:")) {
                part.name = extractParam(line, "name");
                part.filename = extractParam(line, "filename");
            } else if (lower.startsWith("content-type:")) {
                part.contentType = line.substring(line.indexOf(':') + 1).trim();
            }
        }
        int contentStart = headerEnd + 4; // skip the \r\n\r\n
        int len = Math.max(0, end - contentStart);
        byte[] content = new byte[len];
        System.arraycopy(body, contentStart, content, 0, len);
        part.content = content;
        return part;
    }

    private static int findHeaderEnd(byte[] body, int start, int end) {
        for (int i = start; i <= end - 4; i++) {
            if (body[i] == '\r' && body[i + 1] == '\n'
                    && body[i + 2] == '\r' && body[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static String extractParam(String line, String key) {
        String needle = key + "=\"";
        int idx = line.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        int end = line.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return line.substring(start, end);
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        int idx = contentType.toLowerCase().indexOf("boundary=");
        if (idx < 0) {
            return null;
        }
        String boundary = contentType.substring(idx + "boundary=".length()).trim();
        if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
            boundary = boundary.substring(1, boundary.length() - 1);
        }
        int semi = boundary.indexOf(';');
        if (semi >= 0) {
            boundary = boundary.substring(0, semi).trim();
        }
        return boundary;
    }

    private static boolean matchesAt(byte[] body, int pos, byte[] pattern) {
        if (pos + pattern.length > body.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (body[pos + i] != pattern[i]) {
                return false;
            }
        }
        return true;
    }
}
