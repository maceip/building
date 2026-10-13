package dev.ide.applog.relay;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory HttpsURLConnection boundary tests. No socket, cloud request, or permissive TLS trust manager. */
class HttpsRelayTransportTest {
    private final RelayBatch batch = new RelayBatch("dev.test", "batch1", RelayScope.EVENTS, "", Collections.singletonList("incident1"));
    private RelayConfig config() { return RelayConfig.builder("dev.test").enabled(true).endpoint(URI.create("https://private.example.test/relay")).build(); }
    private RelayCredentials.Token token() {
        return new RelayCredentials.Token("dev.test", RelayScope.EVENTS, System.currentTimeMillis() + 60000, "short-app-token".toCharArray());
    }

    @Test void constructingAndDisabledPostNeverOpenConnection() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        HttpsRelayTransport transport = new HttpsRelayTransport(url -> { opens.incrementAndGet(); throw new AssertionError("must not open"); });
        new HttpsRelayTransport(); // default constructor is inert too.
        assertEquals(0, opens.get()); RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
        try (RelayCredentials.Token token = token()) {
            assertThrows(IOException.class, () -> transport.post(RelayConfig.builder("dev.test").build(), batch, new byte[0], token, consent.permit(RelayScope.EVENTS), 0));
        }
        assertEquals(0, opens.get());
    }
    @Test void strictEndpointConfigurationRejectsCleartextAuthQueryAndRedirectLikePaths() {
        for (String endpoint : new String[] { "http://example.test/relay", "https://user:password@example.test", "https://example.test?token=secret",
                "https://example.test/#frag", "https://example.test/%2e%2e/other", "https://example.test/../other", "file:///tmp/relay" })
            assertThrows(IllegalArgumentException.class, () -> RelayConfig.builder("dev.test").enabled(true).endpoint(URI.create(endpoint)).build());
    }
    @Test void redirectIsReturnedWithoutFollowingOrLeakingAuthorization() throws Exception {
        AtomicInteger opens = new AtomicInteger(); FakeConnection connection = new FakeConnection(302, new byte[0]);
        HttpsRelayTransport transport = new HttpsRelayTransport(url -> { opens.incrementAndGet(); assertEquals(config().endpoint.toURL(), url); return connection; });
        RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
        try (RelayCredentials.Token token = token()) {
            RelayTransport.Response response = transport.post(config(), batch, new byte[20], token, consent.permit(RelayScope.EVENTS), 0);
            assertEquals(302, response.status);
        }
        assertEquals(1, opens.get()); assertFalse(connection.getInstanceFollowRedirects());
        assertEquals("Bearer short-app-token", connection.authorization);
        assertTrue(connection.disconnected); assertEquals(0, connection.reads);
    }
    @Test void tlsHandshakeFailureDoesNotDowngradeRetryInTransportOrInstallTrustOverrides() throws Exception {
        FakeConnection connection = new FakeConnection(200, new byte[0]); connection.tlsFailure = true;
        AtomicInteger opens = new AtomicInteger();
        HttpsRelayTransport transport = new HttpsRelayTransport(url -> { opens.incrementAndGet(); return connection; });
        RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
        try (RelayCredentials.Token token = token()) {
            assertThrows(SSLHandshakeException.class, () -> transport.post(config(), batch, new byte[20], token, consent.permit(RelayScope.EVENTS), 0));
        }
        assertEquals(1, opens.get()); assertTrue(connection.disconnected);
        assertEquals(0, connection.output.size());
    }
    @Test void revokedPermitDoesNotOpenAndActiveRevocationDisconnectsAndStopsNextRequest() throws Exception {
        RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
        RelayConsent.Permit oldPermit = consent.permit(RelayScope.EVENTS); consent.revokeAll();
        AtomicInteger opens = new AtomicInteger(); FakeConnection connection = new FakeConnection(200, new byte[0]);
        HttpsRelayTransport transport = new HttpsRelayTransport(url -> { opens.incrementAndGet(); return connection; });
        try (RelayCredentials.Token token = token()) {
            assertThrows(RelayConsent.ConsentRevokedException.class, () -> transport.post(config(), batch, new byte[20], token, oldPermit, 0));
            assertEquals(0, opens.get());
            consent.grant(RelayScope.EVENTS); connection.onWrite = consent::revokeAll;
            assertThrows(RelayConsent.ConsentRevokedException.class,
                    () -> transport.post(config(), batch, new byte[20000], token, consent.permit(RelayScope.EVENTS), 0));
            assertTrue(connection.disconnected); assertEquals(1, opens.get());
            assertTrue(connection.output.size() < 20000, "revocation should stop later payload chunks");
        }
    }
    @Test void postWireUsesOriginalIdsAndBoundedResponseFramingRejectsOversizeAndCompression() throws Exception {
        byte[] receipt = new byte[32]; receipt[0] = 42;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream frame = new DataOutputStream(bytes);
        frame.writeInt(0x52535031); frame.write(receipt); frame.writeInt(0);
        FakeConnection connection = new FakeConnection(200, bytes.toByteArray());
        HttpsRelayTransport transport = new HttpsRelayTransport(url -> connection);
        RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
        try (RelayCredentials.Token token = token()) {
            RelayTransport.Response response = transport.post(config(), batch, new byte[32], token, consent.permit(RelayScope.EVENTS), 0);
            assertArrayEquals(receipt, response.receipt()); assertEquals(0, response.body().length);
            DataInputStream posted = new DataInputStream(new ByteArrayInputStream(connection.output.toByteArray()));
            assertEquals(0x52515031, posted.readInt()); int size = posted.readInt(); byte[] context = new byte[size]; posted.readFully(context);
            assertArrayEquals(batch.context(), context); assertEquals(32, posted.readInt());
        }
        FakeConnection tooLarge = new FakeConnection(200, new byte[41]);
        HttpsRelayTransport bounded = new HttpsRelayTransport(url -> tooLarge);
        try (RelayCredentials.Token token = token()) {
            assertThrows(IOException.class, () -> bounded.post(config(), batch, new byte[20], token, consent.permit(RelayScope.EVENTS), 0));
        }
        FakeConnection zipped = new FakeConnection(200, bytes.toByteArray()); zipped.encoding = "gzip";
        try (RelayCredentials.Token token = token()) {
            assertThrows(IOException.class, () -> new HttpsRelayTransport(url -> zipped).post(config(), batch, new byte[20], token, consent.permit(RelayScope.EVENTS), 0));
        }
    }

    private static final class FakeConnection extends HttpsURLConnection {
        final int status;
        final byte[] response;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean disconnected, tlsFailure;
        int reads;
        String authorization, encoding;
        Runnable onWrite;
        FakeConnection(int status, byte[] response) throws Exception { super(new URL("https://private.example.test/relay")); this.status = status; this.response = response; }
        @Override public void setRequestProperty(String name, String value) { if ("Authorization".equals(name)) authorization = value; super.setRequestProperty(name, value); }
        @Override public OutputStream getOutputStream() throws IOException {
            if (tlsFailure) throw new SSLHandshakeException("fixture TLS failure");
            return new OutputStream() {
                @Override public void write(int b) { output.write(b); if (onWrite != null) { Runnable action = onWrite; onWrite = null; action.run(); } }
                @Override public void write(byte[] b, int off, int len) { output.write(b, off, len); if (onWrite != null) { Runnable action = onWrite; onWrite = null; action.run(); } }
            };
        }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return response.length; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? "https://attacker.example/steal" : "Content-Encoding".equals(name) ? encoding : null; }
        @Override public InputStream getInputStream() { reads++; return new ByteArrayInputStream(response); }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
        @Override public String getCipherSuite() { return "TLS_AES_128_GCM_SHA256"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
}
