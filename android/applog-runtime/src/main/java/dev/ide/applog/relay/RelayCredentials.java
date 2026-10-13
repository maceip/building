package dev.ide.applog.relay;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;

/** Fresh per-app/per-operation access tokens only; a service administrator key is never a valid credential. */
public final class RelayCredentials {
    private RelayCredentials() { }
    public interface Provider {
        /** Implement locally cached refresh as appropriate; revoked relay must never trigger this callback. */
        Token acquire(String applicationId, RelayScope scope) throws IOException;
    }
    public static final class Token implements Closeable {
        private final String applicationId;
        private final RelayScope scope;
        private final long expiresAtMillis;
        private char[] secret;
        public Token(String applicationId, RelayScope scope, long expiresAtMillis, char[] bearerToken) {
            if (applicationId == null || scope == null || bearerToken == null
                    || bearerToken.length < 1 || bearerToken.length > 8192)
                throw new IllegalArgumentException("Scoped token required");
            for (char c : bearerToken) {
                if (c < 33 || c > 126 || c == '\r' || c == '\n') throw new IllegalArgumentException("Invalid bearer token");
            }
            this.applicationId = applicationId; this.scope = scope;
            this.expiresAtMillis = expiresAtMillis; this.secret = bearerToken.clone();
        }
        synchronized void validate(String app, RelayScope operation, long now) throws IOException {
            if (secret == null || !app.equals(applicationId) || scope != operation
                    || expiresAtMillis <= now || expiresAtMillis - now > 15L * 60 * 1000)
                throw new IOException("A short-lived application/operation-scoped token is required");
        }
        synchronized String authorizationHeader() throws IOException {
            if (secret == null) throw new IOException("Credential already cleared");
            // HTTPS implementation necessarily creates a transient String header. Never retain/log it.
            return "Bearer " + new String(secret);
        }
        @Override public synchronized void close() { if (secret != null) Arrays.fill(secret, '\0'); secret = null; }
        @Override public String toString() { return "RelayToken[redacted]"; }
    }
}
