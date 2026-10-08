package server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import server.config.Config;
import server.config.ServerConfig;
import server.http.ErrorPages;
import server.http.HttpParser;
import server.http.HttpRequest;
import server.http.HttpResponse;
import server.http.HttpStatus;
import server.utils.SessionManager;

/**
 * Single-threaded, event-driven HTTP server built on java.nio. A single
 * Selector multiplexes every listening socket (across all configured servers
 * and ports) and every active client connection. The server is designed to
 * never crash: all per-connection work is wrapped so a failure closes only that
 * connection.
 */
public final class Server {

    private static final long CONNECTION_TIMEOUT_MS = 30_000;
    private static final long SELECT_TIMEOUT_MS = 1_000;
    private static final int READ_BUFFER_SIZE = 16 * 1024;
    private static final int MAX_REQUESTS_PER_CONNECTION = 1000;

    private final Config config;
    private final Router router;
    private Selector selector;
    /** Maps each listening server-socket channel to its owning server config. */
    private final Map<ServerSocketChannel, ServerConfig> listeners = new HashMap<>();
    private final ByteBuffer readBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
    private volatile boolean running = false;

    public Server(Config config) {
        this.config = config;
        this.router = new Router(new SessionManager());
    }

    public void start() throws IOException {
        selector = Selector.open();
        bindListeners();
        running = true;

        System.out.println("Server started. Listening on:");
        for (Map.Entry<ServerSocketChannel, ServerConfig> e : listeners.entrySet()) {
            ServerConfig sc = e.getValue();
            InetSocketAddress addr = (InetSocketAddress) e.getKey().socket().getLocalSocketAddress();
            System.out.println("  http://" + sc.host + ":" + addr.getPort()
                    + "  (" + sc.serverName + ")");
        }

        eventLoop();
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void bindListeners() throws IOException {
        for (ServerConfig sc : config.servers) {
            for (int port : sc.ports) {
                ServerSocketChannel ssc = ServerSocketChannel.open();
                ssc.configureBlocking(false);
                ssc.socket().setReuseAddress(true);
                ssc.bind(new InetSocketAddress(sc.host, port));
                ssc.register(selector, SelectionKey.OP_ACCEPT);
                listeners.put(ssc, sc);
            }
        }
        if (listeners.isEmpty()) {
            throw new IOException("No listeners configured");
        }
    }

    private void eventLoop() {
        while (running) {
            try {
                selector.select(SELECT_TIMEOUT_MS);
            } catch (IOException e) {
                System.err.println("[Server] select() failed: " + e.getMessage());
                continue;
            }

            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();
                if (!key.isValid()) {
                    continue;
                }
                try {
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                } catch (CancelledKeyException | ClosedChannelException e) {
                    closeQuietly(key);
                } catch (Throwable t) {
                    // Absolute safety net: an error on one connection must never
                    // take down the event loop.
                    System.err.println("[Server] Error handling key: " + t);
                    closeQuietly(key);
                }
            }

            sweepTimeouts();
        }
        shutdown();
    }

    private void accept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        ServerConfig sc = listeners.get(ssc);
        SocketChannel client;
        // Accept all pending connections for this listener.
        while ((client = ssc.accept()) != null) {
            client.configureBlocking(false);
            Connection conn = new Connection(client, sc);
            client.register(selector, SelectionKey.OP_READ, conn);
        }
    }

    private void read(SelectionKey key) {
        Connection conn = (Connection) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        readBuffer.clear();

        int bytesRead;
        try {
            bytesRead = channel.read(readBuffer);
        } catch (IOException e) {
            closeQuietly(key);
            return;
        }

        if (bytesRead == -1) {
            closeQuietly(key);
            return;
        }
        if (bytesRead == 0) {
            return;
        }

        conn.touch();
        readBuffer.flip();
        byte[] data = new byte[readBuffer.remaining()];
        readBuffer.get(data);

        HttpParser parser = conn.parser;
        parser.feed(data, data.length);

        if (parser.isError()) {
            respondError(key, conn, parser.getErrorStatus());
            return;
        }

        if (parser.isComplete()) {
            handleRequest(key, conn, parser.getRequest());
        }
    }

    private void handleRequest(SelectionKey key, Connection conn, HttpRequest request) {
        HttpResponse response;
        try {
            response = router.route(request, conn.serverConfig);
        } catch (Throwable t) {
            System.err.println("[Server] Router threw: " + t);
            response = ErrorPages.build(HttpStatus.INTERNAL_SERVER_ERROR, conn.serverConfig, false);
        }

        boolean keepAlive = request.keepAlive() && response.isKeepAlive()
                && conn.requestsServed < MAX_REQUESTS_PER_CONNECTION;
        response.keepAlive(keepAlive);
        queueResponse(key, conn, response, !keepAlive);
    }

    private void respondError(SelectionKey key, Connection conn, HttpStatus status) {
        HttpResponse response = ErrorPages.build(status, conn.serverConfig, false);
        response.keepAlive(false);
        queueResponse(key, conn, response, true);
    }

    private void queueResponse(SelectionKey key, Connection conn, HttpResponse response,
                               boolean closeAfter) {
        conn.writeBuffer = ByteBuffer.wrap(response.toBytes());
        conn.closeAfterWrite = closeAfter;
        try {
            key.interestOps(SelectionKey.OP_WRITE);
        } catch (CancelledKeyException e) {
            closeQuietly(key);
            return;
        }
        // Attempt an immediate write to reduce latency.
        write(key);
    }

    private void write(SelectionKey key) {
        Connection conn = (Connection) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        if (conn.writeBuffer == null) {
            return;
        }
        try {
            channel.write(conn.writeBuffer);
        } catch (IOException e) {
            closeQuietly(key);
            return;
        }
        conn.touch();

        if (conn.writeBuffer.hasRemaining()) {
            // Partial write: stay interested in writability.
            try {
                key.interestOps(SelectionKey.OP_WRITE);
            } catch (CancelledKeyException e) {
                closeQuietly(key);
            }
            return;
        }

        // Write complete.
        if (conn.closeAfterWrite) {
            closeQuietly(key);
        } else {
            conn.resetForNextRequest();
            try {
                key.interestOps(SelectionKey.OP_READ);
            } catch (CancelledKeyException e) {
                closeQuietly(key);
            }
        }
    }

    private void sweepTimeouts() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            Object att = key.attachment();
            if (!(att instanceof Connection conn)) {
                continue;
            }
            if (now - conn.lastActivity > CONNECTION_TIMEOUT_MS) {
                // If a partial request is in flight, send a timeout status.
                if (conn.parser != null && conn.parser.getState() != HttpParser.State.REQUEST_LINE
                        && !conn.parser.isComplete() && !conn.hasPendingWrite()) {
                    try {
                        HttpResponse resp = ErrorPages.build(
                                HttpStatus.REQUEST_TIMEOUT, conn.serverConfig, false);
                        resp.keepAlive(false);
                        conn.channel.write(ByteBuffer.wrap(resp.toBytes()));
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
                closeQuietly(key);
            }
        }
    }

    private void closeQuietly(SelectionKey key) {
        try {
            key.cancel();
        } catch (Exception ignored) {
            // ignore
        }
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void shutdown() {
        System.out.println("Server shutting down...");
        for (SelectionKey key : selector.keys()) {
            closeQuietly(key);
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
