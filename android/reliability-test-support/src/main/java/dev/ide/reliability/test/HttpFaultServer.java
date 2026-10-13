package dev.ide.reliability.test;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in DEBUG test artifact, not a transport replacement. The client's normal HTTP stack connects to
 * a real loopback socket. It cannot intercept another app, change global connectivity, or manufacture
 * SDK events. Include this jar with debugImplementation, never implementation or a release classpath.
 */
public final class HttpFaultServer implements Closeable {
    public enum Fault { OK, READ_TIMEOUT, UNAUTHORIZED, RATE_LIMITED, SERVER_ERROR, MALFORMED_JSON, PARTIAL_BODY }

    private final Fault fault;
    private final ServerSocket listener;
    private final String path = "/fixture-" + UUID.randomUUID().toString();
    private final AtomicInteger requests = new AtomicInteger();
    private final Thread worker;
    private volatile Socket active;
    private volatile boolean closed;
    private volatile IOException failure;

    /** Only an explicitly participating debuggable process may turn on fault injection. */
    public static HttpFaultServer start(Context application, Fault fault) throws IOException {
        if (application == null) throw new IllegalArgumentException("Application context is required");
        Context context = application.getApplicationContext();
        if (context == null) throw new IllegalArgumentException("Application context is unavailable");
        requireDebuggable(context.getApplicationInfo().flags);
        return new HttpFaultServer(fault);
    }

    static void requireDebuggable(int flags) {
        if ((flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
            throw new SecurityException("Fault injection is restricted to debuggable test applications");
    }

    // Package-private only so this jar's own JVM tests exercise the wire protocol without Android stubs.
    HttpFaultServer(Fault fault) throws IOException {
        if (fault == null) throw new IllegalArgumentException("An explicit fault is required");
        this.fault = fault;
        listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        worker = new Thread(new Runnable() {
            @Override public void run() { serve(); }
        }, "fixture-http-fault");
        worker.setDaemon(true);
        worker.start();
    }

    public URL url() throws IOException { return new URL("http://127.0.0.1:" + listener.getLocalPort() + path); }
    /** Observed socket requests, not predicted requests or a substitute for SDK events. */
    public int observedRequests() { return requests.get(); }
    public IOException serverFailure() { return failure; }

    private void serve() {
        try {
            while (!closed) {
                try (Socket socket = listener.accept()) {
                    active = socket;
                    socket.setSoTimeout(2000);
                    String headers = readHeaders(socket.getInputStream());
                    if (!headers.startsWith("GET " + path + " HTTP/1.")) {
                        respond(socket.getOutputStream(), 404, "Not Found", "{}", false);
                        continue;
                    }
                    requests.incrementAndGet();
                    OutputStream out = socket.getOutputStream();
                    switch (fault) {
                        case READ_TIMEOUT:
                            // A real connected peer deliberately withholds response bytes. No sleeps in
                            // the client adapter and no fabricated SocketTimeoutException.
                            try { Thread.sleep(3000); }
                            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                            break;
                        case UNAUTHORIZED: respond(out, 401, "Unauthorized", "{\"error\":\"unauthorized\"}", false); break;
                        case RATE_LIMITED: respond(out, 429, "Too Many Requests", "{\"error\":\"rate_limit\"}", false); break;
                        case SERVER_ERROR: respond(out, 500, "Internal Server Error", "{\"error\":\"server\"}", false); break;
                        case MALFORMED_JSON: respond(out, 200, "OK", "{\"saved\":", false); break;
                        case PARTIAL_BODY: respond(out, 200, "OK", "{\"saved\":", true); break;
                        default: respond(out, 200, "OK", "{\"saved\":true}", false);
                    }
                } finally { active = null; }
            }
        } catch (IOException e) {
            if (!closed) failure = e;
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        byte[] ending = new byte[] { '\r', '\n', '\r', '\n' };
        while (bytes.size() < 8192) {
            int next = in.read();
            if (next < 0) throw new IOException("Peer closed before request headers");
            bytes.write(next);
            matched = next == ending[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
            if (matched == ending.length) return new String(bytes.toByteArray(), StandardCharsets.US_ASCII);
        }
        throw new IOException("Request headers exceed fixture limit");
    }

    private static void respond(OutputStream out, int status, String reason, String body, boolean truncate)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        int advertised = bytes.length + (truncate ? 64 : 0);
        String headers = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + advertised + "\r\nConnection: close\r\n"
                + (status == 429 ? "Retry-After: 1\r\n" : "") + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
        // Closing the socket with fewer bytes than Content-Length is an actual partial response.
    }

    @Override public void close() throws IOException {
        closed = true;
        listener.close();
        Socket socket = active;
        if (socket != null) socket.close();
        worker.interrupt();
        try { worker.join(2500); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
