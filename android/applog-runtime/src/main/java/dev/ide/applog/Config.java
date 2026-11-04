package dev.ide.applog;

import org.json.JSONException;
import org.json.JSONObject;

/** Explicit, per-application configuration. Merely including the SDK never opts a user in. */
public final class Config {
    public static final String ASSET_METADATA_KEY = "dev.ide.reliability.CONFIG_ASSET";
    public final boolean enabled, consentGranted, captureLifecycle, captureCrashes;
    public final boolean capturePreviousExitInfo, captureNetwork, teeStdout;
    public final String buildId, sourceRevision, idePackage, ideService, pairingNonce, ideSignerSha256;
    public final int maxJournalBytes, maxEvents;

    private Config(Builder b) {
        enabled = b.enabled;
        consentGranted = b.consentGranted;
        captureLifecycle = b.captureLifecycle;
        captureCrashes = b.captureCrashes;
        capturePreviousExitInfo = b.capturePreviousExitInfo;
        captureNetwork = b.captureNetwork;
        teeStdout = b.teeStdout;
        buildId = identifier(b.buildId, "buildId", 128);
        sourceRevision = identifier(b.sourceRevision, "sourceRevision", 128);
        idePackage = identifier(b.idePackage, "idePackage", 200);
        ideService = identifier(b.ideService, "ideService", 240);
        pairingNonce = b.pairingNonce == null ? "" : b.pairingNonce;
        if (!pairingNonce.isEmpty() && !pairingNonce.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("pairingNonce must be 32 random bytes in lowercase hex");
        }
        ideSignerSha256 = b.ideSignerSha256 == null ? "" : b.ideSignerSha256.replace(":", "").toLowerCase(java.util.Locale.US);
        if (!ideSignerSha256.isEmpty() && !ideSignerSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("IDE signer must be a SHA-256 hex fingerprint");
        }
        if (enabled && (buildId.isEmpty() || sourceRevision.isEmpty())) {
            throw new IllegalArgumentException("Enabled collection requires buildId and sourceRevision");
        }
        if (b.maxJournalBytes < 32768 || b.maxJournalBytes > 4 * 1024 * 1024 || b.maxEvents < 4 || b.maxEvents > 2048) {
            throw new IllegalArgumentException("Journal bounds out of range");
        }
        maxJournalBytes = b.maxJournalBytes;
        maxEvents = b.maxEvents;
    }

    public boolean isCollectionAllowed() { return enabled && consentGranted; }
    public boolean isPaired() { return !idePackage.isEmpty() && !ideService.isEmpty() && !pairingNonce.isEmpty(); }

    /** Parsing is strict about version; missing booleans remain privacy-preserving defaults. */
    public static Config fromJson(String json) throws JSONException {
        if (json == null || json.length() > 16384) throw new JSONException("Configuration too large");
        JSONObject o = new JSONObject(json);
        if (o.getInt("version") != 1) throw new JSONException("Unsupported configuration version");
        return new Builder().enabled(o.optBoolean("enabled", false)).consentGranted(o.optBoolean("consentGranted", false))
            .buildId(o.optString("buildId", "")).sourceRevision(o.optString("sourceRevision", ""))
            .ideComponent(o.optString("idePackage", ""), o.optString("ideService", ""))
            .pairingNonce(o.optString("pairingNonce", "")).ideSignerSha256(o.optString("ideSignerSha256", ""))
            .captureLifecycle(o.optBoolean("captureLifecycle", true)).captureCrashes(o.optBoolean("captureCrashes", true))
            .capturePreviousExitInfo(o.optBoolean("capturePreviousExitInfo", true)).captureNetwork(o.optBoolean("captureNetwork", false))
            .teeStdout(o.optBoolean("teeStdout", false)).maxJournalBytes(o.optInt("maxJournalBytes", 262144))
            .maxEvents(o.optInt("maxEvents", 256)).build();
    }

    static String identifier(String value, String name, int max) {
        String s = value == null ? "" : value;
        if (s.length() > max || (!s.isEmpty() && !s.matches("[A-Za-z0-9_.:$@+\\-]+"))) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return s;
    }

    public static final class Builder {
        private boolean enabled, consentGranted, captureNetwork, teeStdout;
        private boolean captureLifecycle = true, captureCrashes = true, capturePreviousExitInfo = true;
        private String buildId = "", sourceRevision = "", idePackage = "", ideService = "", pairingNonce = "", ideSignerSha256 = "";
        private int maxJournalBytes = 262144, maxEvents = 256;
        public Builder enabled(boolean v) { enabled = v; return this; }
        public Builder consentGranted(boolean v) { consentGranted = v; return this; }
        public Builder buildId(String v) { buildId = v; return this; }
        public Builder sourceRevision(String v) { sourceRevision = v; return this; }
        public Builder ideComponent(String pkg, String service) { idePackage = pkg; ideService = service; return this; }
        public Builder pairingNonce(String v) { pairingNonce = v; return this; }
        public Builder ideSignerSha256(String v) { ideSignerSha256 = v; return this; }
        public Builder captureLifecycle(boolean v) { captureLifecycle = v; return this; }
        public Builder captureCrashes(boolean v) { captureCrashes = v; return this; }
        public Builder capturePreviousExitInfo(boolean v) { capturePreviousExitInfo = v; return this; }
        public Builder captureNetwork(boolean v) { captureNetwork = v; return this; }
        public Builder teeStdout(boolean v) { teeStdout = v; return this; }
        public Builder maxJournalBytes(int v) { maxJournalBytes = v; return this; }
        public Builder maxEvents(int v) { maxEvents = v; return this; }
        public Config build() { return new Config(this); }
    }
}
