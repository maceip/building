package dev.ide.applog;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class UrlConnectionInstrumentationTest {
    @Test void realLoopbackBytesAndHttpErrorStatusRemainTransparent() throws Exception {
        try (Response response = new Response("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello")) {
            HttpURLConnection connection = UrlConnectionInstrumentation.open(response.url());
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
            assertEquals(200, connection.getResponseCode());
            assertEquals("hello", consume(connection.getInputStream()));
            connection.disconnect(); response.finish();
        }
        try (Response response = new Response("HTTP/1.1 401 Unauthorized\r\nContent-Length: 6\r\nConnection: close\r\n\r\ndenied")) {
            HttpURLConnection connection = UrlConnectionInstrumentation.open(response.url());
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
            assertEquals(401, connection.getResponseCode());
            assertThrows(IOException.class, connection::getInputStream);
            assertEquals("denied", consume(connection.getErrorStream()));
            connection.disconnect(); response.finish();
        }
    }

    @Test void realPrematureEofIsAnErrorNotSuccessfulResponse() throws Exception {
        try (Response response = new Response("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nshort")) {
            HttpURLConnection connection = UrlConnectionInstrumentation.open(response.url());
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
            assertEquals(200, connection.getResponseCode());
            assertThrows(EOFException.class, () -> consume(connection.getInputStream()));
            connection.disconnect(); response.finish();
        }
    }

    @Test void realRequestHeadersAndBodyDelegateWithoutBeingCaptured() throws Exception {
        try (Response response = new Response("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")) {
            HttpURLConnection connection = UrlConnectionInstrumentation.open(response.url());
            connection.setConnectTimeout(1000); connection.setReadTimeout(1000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(7);
            connection.setRequestProperty("Authorization", "Bearer private-test-token");
            try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write("payload".getBytes(Redactor.UTF8)); }
            assertEquals(204, connection.getResponseCode());
            connection.disconnect();
            String request = response.finish();
            assertTrue(request.contains("POST /private?token=secret"));
            assertTrue(request.contains("Bearer private-test-token"));
            assertTrue(request.endsWith("payload"));
            assertFalse(Reliability.isInitialized()); // No hidden SDK opt-in as a side effect of instrumentation.
        }
    }

    @Test void enabledSdkCapturesOneCorrelatedEndAndNeverRequestSecrets() throws Exception {
        java.io.File temp = java.nio.file.Files.createTempDirectory("network-journal-test").toFile();
        ConsentState consent = ConsentState.open(new java.io.File(temp, "sdk"), false);
        Config config = new Config.Builder().enabled(true).consentGranted(true).buildId("v1").sourceRevision("rev1")
            .captureNetwork(true).captureLifecycle(false).capturePreviousExitInfo(false).captureCrashes(false).build();
        DiskJournal store = new DiskJournal(new java.io.File(consent.root, "process"), "sample.app", config, consent);
        Class<?> runtimeClass = Class.forName("dev.ide.applog.Reliability$RuntimeState");
        java.lang.reflect.Constructor<?> constructor = runtimeClass.getDeclaredConstructor(android.content.Context.class, Config.class, DiskJournal.class, ConsentState.class);
        constructor.setAccessible(true);
        Object runtime = constructor.newInstance(null, config, store, consent);
        java.lang.reflect.Field field = Reliability.class.getDeclaredField("runtime"); field.setAccessible(true);
        java.lang.reflect.Method close = runtimeClass.getDeclaredMethod("close", boolean.class); close.setAccessible(true);
        Object previous = field.get(null);
        try {
            field.set(null, runtime);
            try (Response response = new Response("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nprivate-body")) {
                HttpURLConnection connection = UrlConnectionInstrumentation.open(response.url());
                connection.setReadTimeout(1000);
                connection.setRequestProperty("Authorization", "Bearer private-test-token");
                assertEquals(200, connection.getResponseCode());
                assertTrue(Reliability.flush());
                assertEquals(1, store.snapshot("local", 16).size()); // Headers are not a completed request.
                assertThrows(EOFException.class, () -> consume(connection.getInputStream()));
                connection.disconnect(); response.finish();
            }
            assertTrue(Reliability.flush());
            java.util.List<Incident> events = store.snapshot("local", 16);
            assertEquals(2, events.size());
            assertEquals("network_start", events.get(0).type); assertEquals("network_end", events.get(1).type);
            String prefix = events.get(0).redactedText.substring(0, "request: ".length() + 16);
            assertTrue(events.get(1).redactedText.startsWith(prefix));
            assertTrue(events.get(1).redactedText.contains("status=200"));
            assertTrue(events.get(1).redactedText.contains("error=io"));
            String json = IncidentProtocol.encodeEvents(events);
            assertFalse(json.contains("private-test-token")); assertFalse(json.contains("private-body"));
            assertFalse(json.contains("/private")); assertFalse(json.contains("token=secret"));
        } finally {
            field.set(null, previous); close.invoke(runtime, false);
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(temp.toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    private static String consume(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[32]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            return new String(bytes.toByteArray(), Redactor.UTF8);
        }
    }

    private static final class Response implements AutoCloseable {
        final ServerSocket server;
        final FutureTask<String> request;
        final Thread thread;
        Response(final String response) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            server.setSoTimeout(2000);
            request = new FutureTask<String>(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2000);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    InputStream in = socket.getInputStream();
                    int value;
                    while ((value = in.read()) != -1) {
                        bytes.write(value);
                        String headers = new String(bytes.toByteArray(), Redactor.UTF8);
                        if (headers.endsWith("\r\n\r\n")) break;
                        if (bytes.size() > 16384) throw new IOException("Request too large");
                    }
                    String headers = new String(bytes.toByteArray(), Redactor.UTF8);
                    int length = 0;
                    for (String line : headers.split("\r\n")) if (line.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                    for (int i = 0; i < length; i++) { value = in.read(); if (value < 0) throw new EOFException(); bytes.write(value); }
                    socket.getOutputStream().write(response.getBytes(Redactor.UTF8)); socket.getOutputStream().flush();
                    return new String(bytes.toByteArray(), Redactor.UTF8);
                }
            });
            thread = new Thread(request, "sdk-loopback-test"); thread.setDaemon(true); thread.start();
        }
        URL url() throws Exception { return new URL("http://127.0.0.1:" + server.getLocalPort() + "/private?token=secret"); }
        String finish() throws Exception { return request.get(3, TimeUnit.SECONDS); }
        @Override public void close() throws IOException { server.close(); }
    }
}
