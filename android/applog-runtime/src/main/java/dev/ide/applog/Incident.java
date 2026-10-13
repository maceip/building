package dev.ide.applog;

import org.json.JSONException;
import org.json.JSONObject;

/** Immutable public record. No headers, request/response bodies, credentials, intents, or user identifiers. */
public final class Incident {
    public final String eventId, sessionId, packageName, buildId, sourceRevision, type, redactedText;
    public final long timestamp;

    public Incident(String eventId, String sessionId, String packageName, String buildId, String sourceRevision,
                    long timestamp, String type, String redactedText) {
        this.eventId = uuid(eventId);
        this.sessionId = uuid(sessionId);
        this.packageName = Config.identifier(packageName, "packageName", 200);
        this.buildId = Config.identifier(buildId, "buildId", 128);
        this.sourceRevision = Config.identifier(sourceRevision, "sourceRevision", 128);
        this.type = Config.identifier(type, "type", 40);
        if (this.packageName.isEmpty() || this.buildId.isEmpty() || this.sourceRevision.isEmpty() || timestamp < 0) {
            throw new IllegalArgumentException("Missing incident identity");
        }
        this.timestamp = timestamp;
        this.redactedText = Redactor.text(redactedText);
    }

    private static String uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Expected UUID");
        }
        return value;
    }

    /** Deterministic JSON field ordering, shared by exports and authenticated backup/relay adapters. */
    public String toJson() {
        return "{\"eventId\":" + JSONObject.quote(eventId) + ",\"sessionId\":" + JSONObject.quote(sessionId)
            + ",\"packageName\":" + JSONObject.quote(packageName) + ",\"buildId\":" + JSONObject.quote(buildId)
            + ",\"sourceRevision\":" + JSONObject.quote(sourceRevision) + ",\"timestamp\":" + timestamp
            + ",\"type\":" + JSONObject.quote(type) + ",\"redactedText\":" + JSONObject.quote(redactedText) + "}";
    }

    public static Incident fromJson(String json) throws JSONException {
        if (json == null || json.length() > IncidentProtocol.MAX_FRAME_CHARS) throw new JSONException("Incident too large");
        return fromObject(new JSONObject(json));
    }

    static Incident fromObject(JSONObject o) throws JSONException {
        return new Incident(o.getString("eventId"), o.getString("sessionId"), o.getString("packageName"),
            o.getString("buildId"), o.getString("sourceRevision"), o.getLong("timestamp"),
            o.getString("type"), o.getString("redactedText"));
    }

    boolean sameIdentity(Incident other) {
        return sessionId.equals(other.sessionId) && packageName.equals(other.packageName)
            && buildId.equals(other.buildId) && sourceRevision.equals(other.sourceRevision);
    }
}
