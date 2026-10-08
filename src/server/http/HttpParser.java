package server.http;

import java.io.ByteArrayOutputStream;

/**
 * Incremental, non-blocking HTTP/1.1 request parser.
 *
 * Bytes are fed in via {@link #feed(byte[], int)} as they arrive from the
 * selector. The parser maintains its own state machine and only signals
 * completion once a full request (headers + body) has been received. It
 * supports both Content-Length delimited bodies and chunked transfer encoding.
 */
public final class HttpParser {

    public enum State { REQUEST_LINE, HEADERS, BODY, CHUNKED_BODY, COMPLETE, ERROR }

    private static final int MAX_REQUEST_LINE = 8192;
    private static final int MAX_HEADER_SIZE = 65536;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private State state = State.REQUEST_LINE;
    private HttpRequest request = new HttpRequest();

    private int contentLength = -1;
    private boolean chunked = false;
    private final long maxBodySize;
    private HttpStatus errorStatus = HttpStatus.BAD_REQUEST;

    // Chunked parsing state
    private int chunkRemaining = -1;
    private boolean readingChunkSize = true;

    public HttpParser(long maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public State getState() {
        return state;
    }

    public boolean isComplete() {
        return state == State.COMPLETE;
    }

    public boolean isError() {
        return state == State.ERROR;
    }

    public HttpStatus getErrorStatus() {
        return errorStatus;
    }

    public HttpRequest getRequest() {
        return request;
    }

    /** Feeds a slice of newly-read bytes into the parser. */
    public void feed(byte[] data, int len) {
        int i = 0;
        while (i < len && state != State.COMPLETE && state != State.ERROR) {
            switch (state) {
                case REQUEST_LINE:
                case HEADERS:
                    i = feedHeaderPhase(data, i, len);
                    break;
                case BODY:
                    i = feedFixedBody(data, i, len);
                    break;
                case CHUNKED_BODY:
                    i = feedChunkedBody(data, i, len);
                    break;
                default:
                    return;
            }
        }
    }

    private int feedHeaderPhase(byte[] data, int start, int len) {
        for (int i = start; i < len; i++) {
            buffer.write(data[i]);
            if (buffer.size() > MAX_HEADER_SIZE) {
                fail(HttpStatus.BAD_REQUEST);
                return len;
            }
            if (endsWithLine(buffer.toByteArray())) {
                String line = takeLine();
                if (!processHeaderLine(line)) {
                    return i + 1; // error, or transitioned to a body state
                }
                if (state != State.REQUEST_LINE && state != State.HEADERS) {
                    return i + 1;
                }
            }
        }
        return len;
    }

    /**
     * Consumes chunked transfer-encoded body bytes. Uses the shared buffer to
     * accumulate the current chunk-size line, then reads exactly that many data
     * bytes, then the trailing CRLF, repeating until the terminating 0-chunk.
     */
    private int feedChunkedBody(byte[] data, int start, int len) {
        for (int i = start; i < len; i++) {
            byte b = data[i];
            if (readingChunkSize) {
                buffer.write(b);
                if (endsWithCRLF(buffer.toByteArray())) {
                    byte[] buf = buffer.toByteArray();
                    String sizeLine = new String(buf, 0, buf.length - 2,
                            java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                    buffer.reset();
                    int semi = sizeLine.indexOf(';');
                    if (semi >= 0) {
                        sizeLine = sizeLine.substring(0, semi).trim();
                    }
                    try {
                        chunkRemaining = Integer.parseInt(sizeLine, 16);
                    } catch (NumberFormatException e) {
                        fail(HttpStatus.BAD_REQUEST);
                        return i + 1;
                    }
                    if (chunkRemaining == 0) {
                        finishBody();
                        return i + 1;
                    }
                    readingChunkSize = false;
                }
            } else if (chunkRemaining > 0) {
                body.write(b);
                chunkRemaining--;
                if (body.size() > maxBodySize) {
                    fail(HttpStatus.PAYLOAD_TOO_LARGE);
                    return i + 1;
                }
            } else {
                // Expect the CRLF terminating the chunk data.
                buffer.write(b);
                if (endsWithCRLF(buffer.toByteArray())) {
                    buffer.reset();
                    readingChunkSize = true;
                    chunkRemaining = -1;
                }
            }
        }
        return len;
    }

    private int feedFixedBody(byte[] data, int start, int len) {
        int i = start;
        while (i < len && body.size() < contentLength) {
            body.write(data[i]);
            i++;
            if (body.size() > maxBodySize) {
                fail(HttpStatus.PAYLOAD_TOO_LARGE);
                return len;
            }
        }
        if (body.size() >= contentLength) {
            finishBody();
        }
        return i;
    }

    private boolean processHeaderLine(String line) {
        if (state == State.REQUEST_LINE) {
            return parseRequestLine(line);
        }
        // HEADERS
        if (line.isEmpty()) {
            return finishHeaders();
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            fail(HttpStatus.BAD_REQUEST);
            return false;
        }
        String name = line.substring(0, colon).trim();
        String value = line.substring(colon + 1).trim();
        request.addHeader(name, value);
        return true;
    }

    private boolean parseRequestLine(String line) {
        if (line.length() > MAX_REQUEST_LINE) {
            fail(HttpStatus.URI_TOO_LONG);
            return false;
        }
        String[] parts = line.split(" ");
        if (parts.length != 3) {
            fail(HttpStatus.BAD_REQUEST);
            return false;
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (!version.startsWith("HTTP/")) {
            fail(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (!version.equals("HTTP/1.1") && !version.equals("HTTP/1.0")) {
            fail(HttpStatus.HTTP_VERSION_NOT_SUPPORTED);
            return false;
        }
        request.setMethod(method);
        request.setTarget(target);
        request.setVersion(version);
        state = State.HEADERS;
        return true;
    }

    private boolean finishHeaders() {
        request.parseCookies();

        String te = request.getHeader("transfer-encoding");
        String cl = request.getHeader("content-length");
        if (te != null && te.toLowerCase().contains("chunked")) {
            chunked = true;
            state = State.CHUNKED_BODY;
            readingChunkSize = true;
            buffer.reset();
            return true;
        }
        if (cl != null) {
            try {
                contentLength = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                fail(HttpStatus.BAD_REQUEST);
                return false;
            }
            if (contentLength < 0) {
                fail(HttpStatus.BAD_REQUEST);
                return false;
            }
            if (contentLength > maxBodySize) {
                fail(HttpStatus.PAYLOAD_TOO_LARGE);
                return false;
            }
            if (contentLength == 0) {
                finishBody();
            } else {
                state = State.BODY;
            }
            return true;
        }
        // No body
        finishBody();
        return true;
    }

    private void finishBody() {
        request.setBody(body.toByteArray());
        state = State.COMPLETE;
    }

    private void fail(HttpStatus status) {
        this.errorStatus = status;
        this.state = State.ERROR;
    }

    private String takeLine() {
        byte[] all = buffer.toByteArray();
        buffer.reset();
        int end = all.length;
        if (end >= 2 && all[end - 1] == '\n' && all[end - 2] == '\r') {
            end -= 2;
        } else if (end >= 1 && all[end - 1] == '\n') {
            end -= 1;
        }
        return new String(all, 0, end, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static boolean endsWithLine(byte[] b) {
        return b.length >= 1 && b[b.length - 1] == '\n';
    }

    private static boolean endsWithCRLF(byte[] b) {
        return b.length >= 2 && b[b.length - 2] == '\r' && b[b.length - 1] == '\n';
    }

    /** Resets the parser to handle the next request on a keep-alive connection. */
    public void reset() {
        buffer.reset();
        body.reset();
        state = State.REQUEST_LINE;
        request = new HttpRequest();
        contentLength = -1;
        chunked = false;
        chunkRemaining = -1;
        readingChunkSize = true;
        errorStatus = HttpStatus.BAD_REQUEST;
    }
}
