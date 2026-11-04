package dev.ide.applog;

import java.net.URL;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit metadata-only network span; never reads a request/response body, header, URL path, or query. */
public final class NetworkTrace implements AutoCloseable {
    private final String id, method, endpoint;
    private final long startedNanos;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final boolean enabled;

    private NetworkTrace(String method, URL url) {
        enabled = Reliability.networkEnabled();
        this.id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.method = method != null && method.matches("(?i)GET|HEAD|POST|PUT|DELETE|CONNECT|OPTIONS|TRACE|PATCH")
            ? method.toUpperCase(Locale.US) : "OTHER";
        // Hostnames can themselves embed tenant IDs or secrets. Only scheme + coarse address class survive.
        String scheme = url != null && "https".equalsIgnoreCase(url.getProtocol()) ? "https" : "http";
        String host = url == null ? "" : url.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
        endpoint = scheme + ":" + (local ? "loopback" : "remote");
        startedNanos = System.nanoTime();
        if (enabled) Reliability.recordNetwork(IncidentProtocol.NETWORK_START, "request: " + id + " method=" + this.method + " endpoint=" + endpoint);
    }

    public static NetworkTrace begin(String method, URL url) { return new NetworkTrace(method, url); }

    /** Idempotent. Error MESSAGE, class names of arbitrary user exceptions, and headers are never captured. */
    public void end(int statusCode, Throwable error) {
        if (!ended.compareAndSet(false, true) || !enabled) return;
        long duration = Math.min(86400000L, Math.max(0L, (System.nanoTime() - startedNanos) / 1000000L));
        int status = statusCode >= 100 && statusCode <= 599 ? statusCode : 0;
        String category = error == null ? "none" : error instanceof java.net.SocketTimeoutException ? "timeout"
            : error instanceof javax.net.ssl.SSLException ? "tls" : error instanceof java.net.UnknownHostException ? "dns"
            : error instanceof java.net.ConnectException ? "connect" : error instanceof java.io.IOException ? "io" : "other";
        Reliability.recordNetwork(IncidentProtocol.NETWORK_END, "request: " + id + " method=" + method + " endpoint=" + endpoint
            + " status=" + status + " durationMs=" + duration + " error=" + category);
    }

    void cancel(int statusCode) {
        if (ended.compareAndSet(false, true) && enabled) {
            int status = statusCode >= 100 && statusCode <= 599 ? statusCode : 0;
            Reliability.recordNetwork(IncidentProtocol.NETWORK_END, "request: " + id + " method=" + method + " endpoint=" + endpoint + " status=" + status + " error=cancelled");
        }
    }

    /** Call when the caller abandons a request without a status/error. */
    @Override public void close() { cancel(0); }
}
