package server;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import server.config.ServerConfig;
import server.http.HttpParser;

/**
 * Holds all per-connection state: the socket channel, the incremental parser,
 * the pending write buffer, and timing/keep-alive bookkeeping.
 */
public final class Connection {

    public final SocketChannel channel;
    public final ServerConfig serverConfig;
    public HttpParser parser;
    public ByteBuffer writeBuffer;
    public long lastActivity;
    public boolean closeAfterWrite = false;
    public int requestsServed = 0;

    public Connection(SocketChannel channel, ServerConfig serverConfig) {
        this.channel = channel;
        this.serverConfig = serverConfig;
        this.parser = new HttpParser(serverConfig.clientMaxBodySize);
        this.lastActivity = System.currentTimeMillis();
    }

    public void touch() {
        this.lastActivity = System.currentTimeMillis();
    }

    public boolean hasPendingWrite() {
        return writeBuffer != null && writeBuffer.hasRemaining();
    }

    public void resetForNextRequest() {
        this.parser = new HttpParser(serverConfig.clientMaxBodySize);
        this.writeBuffer = null;
        this.requestsServed++;
    }
}
