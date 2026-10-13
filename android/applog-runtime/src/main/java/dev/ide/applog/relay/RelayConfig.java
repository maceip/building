package dev.ide.applog.relay;

import java.net.URI;

/** Bring-your-own HTTPS endpoint. There is deliberately no default endpoint, credential, or auto-start. */
public final class RelayConfig {
    public final boolean enabled;
    public final URI endpoint;
    public final String applicationId;
    public final int maxBatchBytes;
    public final int maxQueueEntries;
    public final long maxQueueBytes;
    public final long maxQueueAgeMillis;
    public final int maxAttempts;
    public final long initialBackoffMillis;
    public final long maxBackoffMillis;
    public final int connectTimeoutMillis;
    public final int readTimeoutMillis;

    private RelayConfig(Builder builder) {
        if (builder.applicationId == null || !builder.applicationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))
            throw new IllegalArgumentException("Explicit application ID required");
        if (builder.enabled && builder.endpoint == null) throw new IllegalArgumentException("Explicit HTTPS endpoint required");
        if (builder.endpoint != null) validateEndpoint(builder.endpoint);
        if (builder.maxBatchBytes < 1024 || builder.maxBatchBytes > 64 * 1024 * 1024
                || builder.maxQueueEntries < 1 || builder.maxQueueEntries > 4096
                || builder.maxQueueBytes < builder.maxBatchBytes || builder.maxQueueBytes > 1024L * 1024 * 1024
                || builder.maxQueueAgeMillis < 1000 || builder.maxQueueAgeMillis > 30L * 86400000
                || builder.maxAttempts < 1 || builder.maxAttempts > 32
                || builder.initialBackoffMillis < 1000 || builder.maxBackoffMillis < builder.initialBackoffMillis
                || builder.maxBackoffMillis > 86400000L
                || builder.connectTimeoutMillis < 1 || builder.connectTimeoutMillis > 120000
                || builder.readTimeoutMillis < 1 || builder.readTimeoutMillis > 120000)
            throw new IllegalArgumentException("Invalid relay bounds");
        enabled = builder.enabled; endpoint = builder.endpoint; applicationId = builder.applicationId;
        maxBatchBytes = builder.maxBatchBytes; maxQueueEntries = builder.maxQueueEntries;
        maxQueueBytes = builder.maxQueueBytes; maxQueueAgeMillis = builder.maxQueueAgeMillis;
        maxAttempts = builder.maxAttempts; initialBackoffMillis = builder.initialBackoffMillis;
        maxBackoffMillis = builder.maxBackoffMillis; connectTimeoutMillis = builder.connectTimeoutMillis;
        readTimeoutMillis = builder.readTimeoutMillis;
    }
    public static Builder builder(String applicationId) { return new Builder(applicationId); }
    public static final class Builder {
        private boolean enabled;
        private URI endpoint;
        private final String applicationId;
        private int maxBatchBytes = 2 * 1024 * 1024;
        private int maxQueueEntries = 64;
        private long maxQueueBytes = 16L * 1024 * 1024;
        private long maxQueueAgeMillis = 7L * 86400000;
        private int maxAttempts = 8;
        private long initialBackoffMillis = 1000;
        private long maxBackoffMillis = 3600000;
        private int connectTimeoutMillis = 10000;
        private int readTimeoutMillis = 15000;
        private Builder(String applicationId) { this.applicationId = applicationId; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }
        public Builder endpoint(URI endpoint) { this.endpoint = endpoint; return this; }
        public Builder bounds(int batchBytes, int queueEntries, long queueBytes, long queueAgeMillis) {
            maxBatchBytes = batchBytes; maxQueueEntries = queueEntries;
            maxQueueBytes = queueBytes; maxQueueAgeMillis = queueAgeMillis; return this;
        }
        public Builder retries(int attempts, long initialMillis, long maximumMillis) {
            maxAttempts = attempts; initialBackoffMillis = initialMillis; maxBackoffMillis = maximumMillis; return this;
        }
        public Builder timeouts(int connectMillis, int readMillis) {
            connectTimeoutMillis = connectMillis; readTimeoutMillis = readMillis; return this;
        }
        public RelayConfig build() { return new RelayConfig(this); }
    }
    static void validateEndpoint(URI endpoint) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null || endpoint.getQuery() != null
                || endpoint.getPort() == 0 || endpoint.getPort() > 65535 || endpoint.getPort() < -1
                || endpoint.getRawPath() != null && (endpoint.getRawPath().contains("%") || endpoint.getRawPath().contains("..")))
            throw new IllegalArgumentException("Use a fixed HTTPS endpoint without credentials, query, fragment or encoded path");
    }
}
