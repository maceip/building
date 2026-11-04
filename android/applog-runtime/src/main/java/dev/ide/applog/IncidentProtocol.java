package dev.ide.applog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned synchronous Binder contract. A receipt acknowledges only a receiver's durable commit. */
public final class IncidentProtocol {
    public static final String BINDER_DESCRIPTOR = "dev.ide.reliability.IIncidentSink";
    public static final int TXN_SUBMIT = 1, VERSION = 1;
    public static final int MAX_FRAME_BYTES = 65536, MAX_FRAME_CHARS = 32768, MAX_EVENTS = 16, MAX_TEXT_BYTES = 8192;
    public static final String BREADCRUMB = "breadcrumb", LIFECYCLE = "lifecycle", FATAL_JAVA = "fatal_java";
    public static final String NONFATAL_JAVA = "nonfatal_java", PREVIOUS_EXIT = "previous_exit";
    public static final String NETWORK_START = "network_start", NETWORK_END = "network_end", STDOUT = "stdout";

    private IncidentProtocol() {}

    /** Canonical event-only JSON. Never contains pairing grants, relay credentials, or private paths. */
    public static String encodeEvents(List<Incident> events) {
        StringBuilder b = new StringBuilder("[");
        for (Incident event : events) {
            if (b.length() > 1) b.append(',');
            b.append(event.toJson());
        }
        return b.append(']').toString();
    }

    static JSONObject identity(Incident event, String op, String clientNonce) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", VERSION);
        o.put("op", op);
        o.put("packageName", event.packageName);
        o.put("buildId", event.buildId);
        o.put("sourceRevision", event.sourceRevision);
        o.put("sessionId", event.sessionId);
        o.put("clientNonce", clientNonce);
        return o;
    }

    static String hello(Incident event, String clientNonce, String pairingNonce) throws JSONException {
        JSONObject o = identity(event, "hello", clientNonce);
        o.put("pairingNonce", pairingNonce);
        return bounded(o.toString());
    }

    static String submit(List<Incident> events, String clientNonce, String serverNonce, long droppedCount) throws JSONException {
        if (events.isEmpty() || events.size() > MAX_EVENTS) throw new JSONException("Invalid batch size");
        Incident first = events.get(0);
        for (Incident event : events) if (!first.sameIdentity(event)) throw new JSONException("Mixed identity batch");
        JSONObject o = identity(first, "submit", clientNonce);
        o.put("serverNonce", serverNonce);
        o.put("droppedCount", droppedCount);
        o.put("events", new JSONArray(encodeEvents(events)));
        return bounded(o.toString());
    }

    static String bounded(String text) throws JSONException {
        if (text == null || text.length() > MAX_FRAME_CHARS || text.getBytes(Redactor.UTF8).length > MAX_FRAME_BYTES) {
            throw new JSONException("Frame exceeds protocol bounds");
        }
        return text;
    }

    static JSONObject response(String json, String op, Incident identity, String clientNonce) throws JSONException {
        JSONObject o = new JSONObject(bounded(json));
        if (o.getInt("version") != VERSION || !op.equals(o.getString("op"))
            || !identity.sessionId.equals(o.getString("sessionId")) || !clientNonce.equals(o.getString("clientNonce"))) {
            throw new JSONException("Receipt identity mismatch");
        }
        // Hosts may repeat full identity; when they do, none of it may change.
        String[] keys = {"packageName", "buildId", "sourceRevision"};
        String[] values = {identity.packageName, identity.buildId, identity.sourceRevision};
        for (int i = 0; i < keys.length; i++) if (o.has(keys[i]) && !values[i].equals(o.getString(keys[i]))) {
            throw new JSONException("Receipt build mismatch");
        }
        return o;
    }

    static Collection<String> receipt(String json, List<Incident> submitted, String clientNonce, String serverNonce) throws JSONException {
        if (submitted.isEmpty()) throw new JSONException("No submitted events");
        JSONObject o = response(json, "receipt", submitted.get(0), clientNonce);
        if (!serverNonce.equals(o.getString("serverNonce"))) throw new JSONException("Receipt nonce mismatch");
        JSONArray ids = o.getJSONArray("ackEventIds");
        if (ids.length() > submitted.size()) throw new JSONException("Too many acknowledgments");
        Set<String> expected = new HashSet<String>();
        for (Incident event : submitted) expected.add(event.eventId);
        Set<String> seen = new HashSet<String>();
        List<String> accepted = new ArrayList<String>();
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            if (!expected.contains(id) || !seen.add(id)) throw new JSONException("Unsubmitted or duplicate acknowledgment");
            accepted.add(id);
        }
        return accepted;
    }
}
