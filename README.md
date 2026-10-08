# Java LocalServer

A custom, HTTP/1.1-compliant web server written in **pure Java** with **no external
dependencies** and **no server frameworks**. It uses `java.nio` for non-blocking,
event-driven I/O and runs entirely in **one process on a single thread**.

## Features

- Event-driven, non-blocking I/O with a single `Selector` multiplexing every
  listening socket and every client connection (single process, single thread).
- Listens on **multiple ports** and hosts **multiple virtual servers**.
- Handles **`GET`**, **`POST`**, and **`DELETE`**.
- **File uploads** via `multipart/form-data`.
- **Cookies and sessions** (auto-issued `SESSIONID`, in-memory session store with TTL).
- **CGI execution** (e.g. Python `.py`) via `ProcessBuilder`, with the script passed
  as the first argument and full CGI environment including `PATH_INFO`.
- **Chunked** and **unchunked** request bodies.
- **Client body size limit** (`413 Payload Too Large`).
- **Request/connection timeouts** (`408 Request Timeout`) and keep-alive.
- **Default error pages** for `400, 403, 404, 405, 413, 500` (custom pages configurable).
- **Directory listing** (toggleable per route), index / default files, and redirects.
- **Path-traversal protection** and CGI input sanitization.
- **Crash-proof**: every connection is isolated; a failure closes only that
  connection and never the event loop.

## Requirements

- Java 17+ (developed and tested on OpenJDK 21)
- `python3` (only for the sample CGI script)

## Project structure

```
java-localserver
├── src/server/
│   ├── Main.java                # Entry point
│   ├── Server.java              # NIO event loop + connection lifecycle
│   ├── Connection.java          # Per-connection state
│   ├── Router.java              # Request routing (static, upload, delete, redirect, CGI)
│   ├── CGIHandler.java          # CGI execution via ProcessBuilder
│   ├── config/                  # JSON parser + config model + loader/validator
│   │   ├── Json.java
│   │   ├── Config.java
│   │   ├── ServerConfig.java
│   │   ├── RouteConfig.java
│   │   └── ConfigLoader.java
│   ├── http/                    # HTTP primitives
│   │   ├── HttpParser.java      # Incremental request parser (chunked + fixed body)
│   │   ├── HttpRequest.java
│   │   ├── HttpResponse.java
│   │   ├── HttpStatus.java
│   │   ├── MimeTypes.java
│   │   ├── MultipartParser.java # multipart/form-data parser for uploads
│   │   └── ErrorPages.java
│   └── utils/
│       ├── Session.java
│       ├── SessionManager.java
│       └── Cookie.java
├── config.json                  # Server configuration
├── error_pages/                 # Custom error HTML files (400/403/404/405/413/500)
├── www/                         # Static content for the main server
├── www2/                        # Static content for the secondary server
├── cgi-bin/hello.py             # Sample CGI script
├── tests/run_tests.sh           # Integration test suite
└── Makefile
```

## Build & run

```bash
make build          # compile sources into out/
make run            # build then run with config.json
# or explicitly:
java -cp out server.Main config.json
```

Then open http://127.0.0.1:8080/ in a browser.

## Configuration

The server is fully configured via `config.json`. Example route options:

```json
{
  "servers": [
    {
      "server_name": "main",
      "host": "127.0.0.1",
      "ports": [8080, 8081],
      "default": true,
      "client_max_body_size": 10485760,
      "error_pages": { "404": "error_pages/404.html" },
      "routes": [
        {
          "path": "/",
          "methods": ["GET", "POST", "DELETE"],
          "root": "www",
          "index": "index.html",
          "directory_listing": true
        },
        {
          "path": "/uploads",
          "methods": ["GET", "POST", "DELETE"],
          "root": "www/uploads",
          "upload_dir": "www/uploads",
          "directory_listing": true
        },
        {
          "path": "/cgi-bin",
          "methods": ["GET", "POST"],
          "root": "cgi-bin",
          "cgi": { ".py": "/usr/bin/python3" }
        },
        { "path": "/old", "methods": ["GET"], "redirect": "/" }
      ]
    }
  ]
}
```

Supported keys:

| Scope  | Key                     | Description                                        |
|--------|-------------------------|----------------------------------------------------|
| server | `server_name`           | Label for logs                                     |
| server | `host`                  | Bind address                                       |
| server | `ports`                 | List of ports to listen on                         |
| server | `default`               | Marks the default server (only one allowed)        |
| server | `client_max_body_size`  | Max request body in bytes (`413` beyond this)      |
| server | `error_pages`           | Map of status code -> HTML file path               |
| route  | `path`                  | URL prefix (longest-prefix match)                  |
| route  | `methods`               | Allowed methods (empty = all)                      |
| route  | `root`                  | Filesystem root for the route                      |
| route  | `index` / `default_file`| Default file served for a directory                |
| route  | `directory_listing`     | Enable auto directory listing                      |
| route  | `redirect`              | Redirect target (302)                              |
| route  | `upload_dir`            | Destination directory for uploads                  |
| route  | `cgi`                   | Map of extension -> interpreter path               |

The configuration is validated at startup; invalid configs fail fast with a
descriptive message.

## Testing

Run the integration suite (starts the server, exercises every feature, tears down):

```bash
make test
# or
bash tests/run_tests.sh
```

### Stress / availability testing with siege

The project targets **99.5%+ availability** under load. With `siege` installed:

```bash
siege -b 127.0.0.1:8080
```

If `siege` is unavailable, a quick parallel `curl` load check works too:

```bash
seq 2000 | xargs -P 50 -I{} curl -s -o /dev/null -w "%{http_code}\n" \
  http://127.0.0.1:8080/ | sort | uniq -c
```

In local testing this yields **100% availability** over 2000 requests at 50-way
concurrency, with the server remaining up throughout.

> **Disclaimer:** Only run stress tools against servers you own or are explicitly
> permitted to test.

## Design notes

- **Single thread, single process:** one `Selector` handles accepts, reads, and
  writes for all sockets. No thread pool, no blocking calls in the hot path.
- **Incremental parsing:** `HttpParser` is a byte-fed state machine so partial
  reads from the selector never block; it supports both `Content-Length` and
  `Transfer-Encoding: chunked`.
- **Crash safety:** the event loop wraps all per-key handling in a `try/catch`
  that closes only the offending connection. Handlers additionally degrade to
  `500` rather than propagating exceptions.
- **Resource safety:** connections are always closed (channel + key) on error,
  timeout, or completion, preventing file-descriptor leaks. Sessions expire and
  are pruned to bound memory.
```
