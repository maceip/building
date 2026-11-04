package dev.ide.applog.relay;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Lazy TLS transport, platform trust/hostname verification intact. Constructing this opens NO connection. */
public final class HttpsRelayTransport implements RelayTransport {
    interface Connections { HttpsURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    public HttpsRelayTransport() {
        this(new Connections() {
            @Override public HttpsURLConnection open(URL url) throws IOException {
                return (HttpsURLConnection) url.openConnection();
            }
        });
    }
    HttpsRelayTransport(Connections connections) { this.connections = connections; }

    @Override public Response post(RelayConfig config, RelayBatch batch, byte[] encryptedPayload,
                                   RelayCredentials.Token token, RelayConsent.Permit consent,
                                   int maxResponseBytes) throws IOException {
        if (!config.enabled) throw new IOException("Relay is disabled");
        RelayConfig.validateEndpoint(config.endpoint);
        if (!config.applicationId.equals(batch.applicationId) || maxResponseBytes < 0
                || maxResponseBytes > config.maxBatchBytes + 128 || encryptedPayload.length > config.maxBatchBytes + 128)
            throw new IOException("Relay request exceeds configured scope/bounds");
        consent.check();
        token.validate(batch.applicationId, batch.scope, System.currentTimeMillis());
        byte[] context = batch.context();
        long contentLength = 12L + context.length + encryptedPayload.length;
        final HttpsURLConnection connection = connections.open(config.endpoint.toURL());
        java.io.Closeable revocation = null;
        try {
            revocation = consent.onRevoke(new Runnable() {
                @Override public void run() { connection.disconnect(); }
            });
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(config.connectTimeoutMillis);
            connection.setReadTimeout(config.readTimeoutMillis);
            connection.setUseCaches(false);
            connection.setAllowUserInteraction(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(contentLength);
            connection.setRequestProperty("Content-Type", "application/vnd.ide.relay-v1");
            connection.setRequestProperty("Accept", "application/vnd.ide.relay-v1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-store");
            connection.setRequestProperty("Authorization", token.authorizationHeader());
            consent.check();
            token.validate(batch.applicationId, batch.scope, System.currentTimeMillis());
            final HttpsURLConnection startedConnection = connection;
            OutputStream requestStream = consent.beginRequest(new RelayConsent.RequestStart<OutputStream>() {
                @Override public OutputStream start() throws IOException { return startedConnection.getOutputStream(); }
            });
            try (DataOutputStream output = new DataOutputStream(requestStream)) {
                output.writeInt(0x52515031);
                output.writeInt(context.length); output.write(context);
                output.writeInt(encryptedPayload.length);
                for (int offset = 0; offset < encryptedPayload.length; offset += 16384) {
                    consent.check();
                    output.write(encryptedPayload, offset, Math.min(16384, encryptedPayload.length - offset));
                }
            }
            consent.check();
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                // Includes every redirect. Never follow Location or forward Authorization elsewhere.
                return new Response(status, null, null);
            }
            long declared = connection.getContentLengthLong();
            if (declared > (long) maxResponseBytes + 40) throw new IOException("Relay response exceeds limit");
            String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("Encoded response unsupported");
            try (DataInputStream input = new DataInputStream(new BufferedInputStream(connection.getInputStream(), 8192))) {
                if (input.readInt() != 0x52535031) throw new IOException("Invalid relay response version");
                byte[] receipt = new byte[32]; input.readFully(receipt);
                int size = input.readInt();
                if (size < 0 || size > maxResponseBytes) throw new IOException("Relay response exceeds limit");
                byte[] bytes = new byte[size];
                for (int offset = 0; offset < size; offset += 16384) {
                    consent.check();
                    input.readFully(bytes, offset, Math.min(16384, size - offset));
                }
                if (input.read() != -1) throw new IOException("Unexpected response trailer");
                consent.check();
                return new Response(status, receipt, bytes);
            }
        } finally {
            if (revocation != null) revocation.close();
            connection.disconnect();
        }
    }
}
