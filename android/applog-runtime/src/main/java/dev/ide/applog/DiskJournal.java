package dev.ide.applog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** A process-isolated, checksummed snapshot journal, with a separate bounded synchronous fatal slot. */
final class DiskJournal implements Closeable {
    static final int FATAL_MAX_BYTES = 32768;
    private final File directory, journalFile, fatalFile;
    private final RandomAccessFile lockFile;
    private final FileLock processLock;
    private final Config config;
    private final ConsentState consent;
    private final ReentrantLock lock = new ReentrantLock(), fatalLock = new ReentrantLock();
    private final AtomicLong unpersistedDrops = new AtomicLong();
    private volatile State state;
    private volatile Record fatal;
    private volatile Grant fatalGrant;
    private volatile boolean failed, closed;
    final Grant current;

    DiskJournal(File directory, String packageName, Config config) throws IOException {
        this(directory, packageName, config, null);
    }

    DiskJournal(File directory, String packageName, Config config, ConsentState consent) throws IOException {
        if (!config.isCollectionAllowed()) throw new IllegalStateException("Collection requires explicit consent");
        this.directory = directory;
        this.config = config;
        this.consent = consent;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private journal");
        DurableFiles.syncDirectory(directory.getParentFile());
        journalFile = new File(directory, "journal.bin");
        fatalFile = new File(directory, "fatal.bin");
        lockFile = new RandomAccessFile(new File(directory, "process.lock"), "rw");
        FileLock acquired;
        try { acquired = lockFile.getChannel().tryLock(); }
        catch (OverlappingFileLockException e) { lockFile.close(); throw new IOException("Process journal already open", e); }
        if (acquired == null) { lockFile.close(); throw new IOException("Process journal already open"); }
        processLock = acquired;
        try {
            String stored = DurableFiles.read(journalFile, 4 * 1024 * 1024);
            state = stored == null ? new State() : State.parse(stored);
            boolean oldConsent = consent != null && !consent.generation.equals(state.consentGeneration);
            if (oldConsent) {
                // A new explicit consent generation must never resurrect pre-revocation data or grants.
                state = new State();
                state.consentGeneration = consent.generation;
                DurableFiles.replace(fatalFile, encodeFatal(null, null));
            }
            String crash = DurableFiles.read(fatalFile, FATAL_MAX_BYTES);
            if (crash != null) {
                JSONObject o = new JSONObject(crash);
                if (o.getInt("version") != 1) throw new JSONException("Unsupported fatal journal");
                if (o.has("record")) {
                    fatal = Record.parse(o.getJSONObject("record"));
                    fatalGrant = Grant.parse(o.getJSONObject("grant"));
                    if (!fatal.incident.sessionId.equals(fatalGrant.sessionId)) throw new JSONException("Fatal identity mismatch");
                }
            }
            Grant match = null;
            for (Grant grant : state.grants.values()) if (grant.matches(packageName, config)) match = grant;
            current = match != null ? match : new Grant(UUID.randomUUID().toString(), packageName, config);
            State initial = state.copy();
            initial.grants.put(current.sessionId, current);
            pruneGrants(initial);
            if (initial.grants.size() > config.maxEvents + 2) throw new IOException("Too many retained sessions");
            commit(initial);
            discardIncomplete(new File(directory, "journal.bin.tmp"));
            discardIncomplete(new File(directory, "fatal.bin.tmp"));
        } catch (JSONException | IllegalArgumentException e) {
            processLock.release(); lockFile.close();
            throw new IOException("Invalid journal; retained without modification", e);
        } catch (IOException | RuntimeException e) {
            processLock.release(); lockFile.close();
            throw e;
        }
    }

    private static void discardIncomplete(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Cannot remove incomplete replacement");
    }

    Incident incident(String type, String text) {
        return new Incident(UUID.randomUUID().toString(), current.sessionId, current.packageName,
            current.buildId, current.sourceRevision, System.currentTimeMillis(), type, text);
    }

    void registerDestination(String destination) throws IOException {
        validateDestination(destination);
        lock.lock();
        try {
            requireOpen();
            if (state.destinations.contains(destination)) return;
            if (state.destinations.size() >= 8) throw new IOException("Too many destinations");
            State next = state.copy();
            next.destinations.add(destination);
            next.destinationGenerations.put(destination, UUID.randomUUID().toString());
            commit(next); // Registration opts only FUTURE events into this destination.
        } finally { lock.unlock(); }
    }

    void unregisterDestination(String destination) throws IOException {
        validateDestination(destination);
        if ("local".equals(destination)) throw new IllegalArgumentException("Use revokeConsent for local capture");
        lock.lock(); fatalLock.lock();
        try {
            requireOpen();
            if (!state.destinations.contains(destination)) return;
            State next = state.copy();
            next.destinations.remove(destination);
            next.destinationGenerations.remove(destination);
            List<Record> retained = new ArrayList<Record>();
            for (Record record : next.records) {
                Record updated = record.without(destination);
                if (!updated.pending.isEmpty()) retained.add(updated);
            }
            next.records = retained;
            pruneGrants(next);
            // Fatal first: if interrupted, this is an incomplete revoke, never a persisted revoke with a
            // stale fatal cursor that could be exposed by a later re-registration of the same destination.
            if (fatal != null && fatal.pending.contains(destination)) {
                Record updated = fatal.without(destination);
                if (updated.pending.isEmpty()) updated = null;
                try { DurableFiles.replace(fatalFile, encodeFatal(updated, fatalGrant)); }
                catch (IOException e) { failed = true; throw e; }
                fatal = updated;
                if (updated == null) fatalGrant = null;
            }
            commit(next);
        } finally { fatalLock.unlock(); lock.unlock(); }
    }

    boolean hasDestination(String destination) { return state.destinations.contains(destination); }
    String destinationGeneration(String destination) { return state.destinationGenerations.get(destination); }

    static void validateDestination(String destination) {
        if (destination == null || !destination.matches("[A-Za-z0-9_.:\\-]{1,80}")) throw new IllegalArgumentException("Invalid destination");
    }

    Pending prepare(String type, String text) {
        // Capture before redaction/identity construction too: neither work may cross an opt-in boundary.
        Map<String, String> generations = state.destinationGenerations;
        return new Pending(incident(type, text), generations);
    }

    Pending prepare(Incident incident) {
        // State is immutable after publication. Capture at collection time, not when the disk writer runs.
        return new Pending(incident, state.destinationGenerations);
    }

    boolean append(Incident incident) throws IOException { return append(prepare(incident)); }

    boolean append(Pending prepared) throws IOException {
        lock.lock();
        try {
            requireOpen();
            Incident incident = prepared.incident;
            if (!incident.sessionId.equals(current.sessionId)) throw new IOException("Foreign incident");
            State next = state.copy();
            long pending = unpersistedDrops.get();
            next.dropped = saturatedAdd(next.dropped, pending);
            Set<String> eligible = new LinkedHashSet<String>();
            for (Map.Entry<String, String> entry : prepared.generations.entrySet()) {
                if (entry.getValue().equals(state.destinationGenerations.get(entry.getKey()))) eligible.add(entry.getKey());
            }
            if (eligible.isEmpty()) return false;
            Record record = new Record(incident, eligible);
            next.records.add(record);
            // Keep room for counters, future destination registrations, and one build grant on upgrade.
            boolean fits = next.records.size() <= config.maxEvents && encode(next).length <= config.maxJournalBytes - 2048;
            if (!fits) {
                next.records.remove(next.records.size() - 1);
                next.dropped = saturatedAdd(next.dropped, 1);
            }
            commit(next);
            unpersistedDrops.addAndGet(-pending);
            return fits;
        } finally { lock.unlock(); }
    }

    /** Never waits for ordinary disk/IPC work. At most one small fsync/atomic replacement, no stack payloads. */
    boolean appendFatal(Incident incident) { return appendFatal(prepare(incident)); }

    boolean appendFatal(Pending prepared) {
        boolean acquired = false;
        try {
            acquired = fatalLock.tryLock(50, TimeUnit.MILLISECONDS);
            if (!acquired || closed || failed || !consentAllowed() || fatal != null) { noteDrop(); return false; }
            Set<String> eligible = new LinkedHashSet<String>();
            for (Map.Entry<String, String> entry : prepared.generations.entrySet()) {
                if (entry.getValue().equals(state.destinationGenerations.get(entry.getKey()))) eligible.add(entry.getKey());
            }
            if (eligible.isEmpty()) return false;
            Record next = new Record(prepared.incident, eligible);
            byte[] bytes = encodeFatal(next, current);
            if (bytes.length > FATAL_MAX_BYTES) { noteDrop(); return false; }
            DurableFiles.replace(fatalFile, bytes);
            fatalGrant = current;
            fatal = next;
            return true;
        } catch (Exception error) {
            noteDrop();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failed = true; // A post-rename fsync failure cannot safely be followed by another rewrite.
            return false;
        } finally { if (acquired) fatalLock.unlock(); }
    }

    List<Incident> snapshot(String destination, int limit) throws IOException {
        if (limit < 0 || limit > 2049) throw new IllegalArgumentException("Snapshot limit out of range");
        lock.lock();
        try {
            requireOpen();
            requireDestination(destination);
            List<Incident> result = new ArrayList<Incident>();
            Record crash = fatal;
            // A full routine queue must not indefinitely delay the reserved crash.
            if (limit > 0 && crash != null && crash.pending.contains(destination)) result.add(crash.incident);
            for (Record record : state.records) {
                if (result.size() >= limit) break;
                if (record.pending.contains(destination)) result.add(record.incident);
            }
            return Collections.unmodifiableList(result);
        } finally { lock.unlock(); }
    }

    Grant grant(String sessionId) {
        Grant crash = fatalGrant;
        return crash != null && crash.sessionId.equals(sessionId) ? crash : state.grants.get(sessionId);
    }

    void acknowledge(String destination, Collection<String> ids) throws IOException {
        if (ids == null || ids.size() > 2049) throw new IllegalArgumentException("Invalid acknowledgment size");
        Set<String> accepted = new LinkedHashSet<String>(ids);
        lock.lock();
        try {
            requireOpen();
            requireDestination(destination);
            State next = state.copy();
            List<Record> retained = new ArrayList<Record>();
            for (Record record : next.records) {
                Record updated = record;
                if (accepted.contains(record.incident.eventId)) updated = record.without(destination);
                if (!updated.pending.isEmpty()) retained.add(updated);
            }
            next.records = retained;
            pruneGrants(next);
            commit(next); // No in-memory removal until fsync + atomic replacement have succeeded.
            fatalLock.lock();
            try {
                if (fatal != null && accepted.contains(fatal.incident.eventId) && fatal.pending.contains(destination)) {
                    Record updated = fatal.without(destination);
                    if (updated.pending.isEmpty()) updated = null;
                    try { DurableFiles.replace(fatalFile, encodeFatal(updated, fatalGrant)); }
                    catch (IOException e) { failed = true; throw e; }
                    fatal = updated;
                    if (updated == null) fatalGrant = null;
                }
            } finally { fatalLock.unlock(); }
        } finally { lock.unlock(); }
    }

    void noteDrop() {
        long old;
        do { old = unpersistedDrops.get(); if (old == Long.MAX_VALUE) return; }
        while (!unpersistedDrops.compareAndSet(old, old + 1));
    }

    long droppedCount() { return saturatedAdd(state.dropped, unpersistedDrops.get()); }
    long exitWatermark() { return state.exitWatermark; }
    void exitWatermark(long timestamp) throws IOException {
        lock.lock();
        try {
            requireOpen();
            State next = state.copy();
            next.exitWatermark = Math.max(next.exitWatermark, timestamp);
            commit(next);
        } finally { lock.unlock(); }
    }

    void flushDrops() throws IOException {
        lock.lock();
        try {
            requireOpen();
            long pending = unpersistedDrops.get();
            if (pending == 0) return;
            State next = state.copy();
            next.dropped = saturatedAdd(next.dropped, pending);
            commit(next);
            unpersistedDrops.addAndGet(-pending);
        } finally { lock.unlock(); }
    }

    void purge() throws IOException {
        lock.lock(); fatalLock.lock();
        try {
            if (closed) throw new IOException("Journal closed");
            State empty = new State();
            if (consent != null) empty.consentGeneration = consent.generation;
            commit(empty);
            DurableFiles.replace(fatalFile, encodeFatal(null, null));
            fatal = null; fatalGrant = null;
            unpersistedDrops.set(0);
        } finally { fatalLock.unlock(); lock.unlock(); }
    }

    @Override public void close() throws IOException {
        lock.lock(); fatalLock.lock();
        try {
            if (closed) return;
            closed = true;
            processLock.release();
            lockFile.close();
        } finally { fatalLock.unlock(); lock.unlock(); }
    }

    boolean consentAllowed() { return consent == null || consent.allowed(); }
    private void requireOpen() throws IOException {
        if (closed || failed || !consentAllowed()) throw new IOException("Journal closed, consent revoked, or awaiting restart after disk failure");
    }
    private void requireDestination(String destination) throws IOException {
        if (!state.destinations.contains(destination)) throw new IOException("Destination not registered with consent");
    }
    private void pruneGrants(State next) {
        Set<String> retained = new LinkedHashSet<String>();
        retained.add(current.sessionId);
        for (Record record : next.records) retained.add(record.incident.sessionId);
        next.grants.keySet().retainAll(retained);
    }
    private void commit(State next) throws IOException {
        byte[] data = encode(next);
        // An app reducing its configured bound cannot silently delete its old unacknowledged history.
        if (data.length > Math.max(config.maxJournalBytes, journalFile.length())) throw new IOException("Journal quota exceeded");
        try { DurableFiles.replace(journalFile, data); }
        catch (IOException e) { failed = true; throw e; }
        state = next;
    }
    private static byte[] encode(State state) throws IOException {
        try { return DurableFiles.envelope(state.json().toString()); }
        catch (JSONException e) { throw new IOException("Cannot encode journal", e); }
    }
    private static byte[] encodeFatal(Record record, Grant grant) throws IOException {
        try {
            JSONObject o = new JSONObject(); o.put("version", 1);
            if (record != null) { o.put("record", record.json()); o.put("grant", grant.json()); }
            return DurableFiles.envelope(o.toString());
        } catch (JSONException e) { throw new IOException("Cannot encode crash", e); }
    }
    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    static final class Pending {
        final Incident incident;
        final Map<String, String> generations;
        Pending(Incident incident, Map<String, String> generations) {
            this.incident = incident;
            this.generations = Collections.unmodifiableMap(new LinkedHashMap<String, String>(generations));
        }
    }

    static final class Grant {
        final String sessionId, packageName, buildId, sourceRevision, idePackage, ideService, pairingNonce, ideSignerSha256;
        Grant(String session, String pkg, Config c) {
            sessionId = session; packageName = pkg; buildId = c.buildId; sourceRevision = c.sourceRevision;
            idePackage = c.idePackage; ideService = c.ideService; pairingNonce = c.pairingNonce; ideSignerSha256 = c.ideSignerSha256;
        }
        private Grant(JSONObject o) throws JSONException {
            sessionId = o.getString("sessionId"); packageName = o.getString("packageName");
            buildId = o.getString("buildId"); sourceRevision = o.getString("sourceRevision");
            idePackage = o.getString("idePackage"); ideService = o.getString("ideService");
            pairingNonce = o.getString("pairingNonce"); ideSignerSha256 = o.getString("ideSignerSha256");
        }
        boolean paired() { return !idePackage.isEmpty() && !ideService.isEmpty() && pairingNonce.matches("[0-9a-f]{64}"); }
        boolean matches(String pkg, Config c) {
            return packageName.equals(pkg) && buildId.equals(c.buildId) && sourceRevision.equals(c.sourceRevision)
                && idePackage.equals(c.idePackage) && ideService.equals(c.ideService) && pairingNonce.equals(c.pairingNonce)
                && ideSignerSha256.equals(c.ideSignerSha256);
        }
        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("sessionId", sessionId); o.put("packageName", packageName); o.put("buildId", buildId);
            o.put("sourceRevision", sourceRevision); o.put("idePackage", idePackage); o.put("ideService", ideService);
            o.put("pairingNonce", pairingNonce); o.put("ideSignerSha256", ideSignerSha256);
            return o;
        }
        static Grant parse(JSONObject o) throws JSONException { return new Grant(o); }
    }

    private static final class Record {
        final Incident incident;
        final Set<String> pending;
        Record(Incident incident, Collection<String> pending) {
            this.incident = incident;
            this.pending = Collections.unmodifiableSet(new LinkedHashSet<String>(pending));
        }
        Record without(String destination) {
            Set<String> remaining = new LinkedHashSet<String>(pending); remaining.remove(destination);
            return new Record(incident, remaining);
        }
        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject(); o.put("incident", new JSONObject(incident.toJson()));
            o.put("pending", new JSONArray(pending)); return o;
        }
        static Record parse(JSONObject o) throws JSONException {
            Set<String> pending = new LinkedHashSet<String>();
            JSONArray destinations = o.getJSONArray("pending");
            if (destinations.length() > 8) throw new JSONException("Too many destinations");
            for (int i = 0; i < destinations.length(); i++) { String d = destinations.getString(i); validateDestination(d); pending.add(d); }
            return new Record(Incident.fromObject(o.getJSONObject("incident")), pending);
        }
    }

    private static final class State {
        long dropped, exitWatermark;
        String consentGeneration = "";
        List<Record> records = new ArrayList<Record>();
        Set<String> destinations = new LinkedHashSet<String>(Collections.singleton("local"));
        Map<String, Grant> grants = new LinkedHashMap<String, Grant>();
        Map<String, String> destinationGenerations = new LinkedHashMap<String, String>();
        State() { destinationGenerations.put("local", UUID.randomUUID().toString()); }
        State copy() {
            State s = new State(); s.dropped = dropped; s.exitWatermark = exitWatermark; s.consentGeneration = consentGeneration;
            s.records = new ArrayList<Record>(records); s.destinations = new LinkedHashSet<String>(destinations);
            s.grants = new LinkedHashMap<String, Grant>(grants);
            s.destinationGenerations = new LinkedHashMap<String, String>(destinationGenerations); return s;
        }
        JSONObject json() throws JSONException {
            JSONObject o = new JSONObject(); o.put("version", 1); o.put("droppedCount", dropped); o.put("exitWatermark", exitWatermark);
            o.put("consentGeneration", consentGeneration);
            o.put("destinations", new JSONArray(destinations));
            o.put("destinationGenerations", new JSONObject(destinationGenerations));
            JSONArray sessions = new JSONArray(); for (Grant grant : grants.values()) sessions.put(grant.json()); o.put("grants", sessions);
            JSONArray events = new JSONArray(); for (Record record : records) events.put(record.json()); o.put("records", events);
            return o;
        }
        static State parse(String value) throws JSONException {
            JSONObject o = new JSONObject(value);
            if (o.getInt("version") != 1) throw new JSONException("Unsupported journal version");
            State s = new State(); s.dropped = o.getLong("droppedCount"); s.exitWatermark = o.getLong("exitWatermark");
            s.consentGeneration = o.optString("consentGeneration", "");
            if (s.dropped < 0 || s.exitWatermark < 0) throw new JSONException("Invalid counters");
            JSONArray ds = o.getJSONArray("destinations");
            if (ds.length() > 8) throw new JSONException("Too many destinations");
            for (int i = 0; i < ds.length(); i++) { String d = ds.getString(i); validateDestination(d); s.destinations.add(d); }
            JSONObject generations = o.optJSONObject("destinationGenerations");
            for (String destination : s.destinations) {
                String generation = generations == null ? UUID.randomUUID().toString() : generations.getString(destination);
                s.destinationGenerations.put(destination, generation);
            }
            JSONArray sessions = o.getJSONArray("grants");
            if (sessions.length() > 2050) throw new JSONException("Too many sessions");
            for (int i = 0; i < sessions.length(); i++) { Grant grant = Grant.parse(sessions.getJSONObject(i)); s.grants.put(grant.sessionId, grant); }
            JSONArray events = o.getJSONArray("records");
            if (events.length() > 2048) throw new JSONException("Too many events");
            Set<String> ids = new LinkedHashSet<String>();
            for (int i = 0; i < events.length(); i++) {
                Record record = Record.parse(events.getJSONObject(i));
                Grant grant = s.grants.get(record.incident.sessionId);
                if (grant == null || !s.destinations.containsAll(record.pending) || !ids.add(record.incident.eventId)
                    || !grant.packageName.equals(record.incident.packageName) || !grant.buildId.equals(record.incident.buildId)
                    || !grant.sourceRevision.equals(record.incident.sourceRevision)) throw new JSONException("Invalid record identity");
                s.records.add(record);
            }
            return s;
        }
    }
}
