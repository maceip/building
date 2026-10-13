package dev.ide.applog.relay;

import dev.ide.applog.Incident;
import dev.ide.applog.IncidentJournal;
import dev.ide.applog.IncidentProtocol;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Explicit, synchronous, bounded relay adapter. Call off the app main thread from your own scheduler.
 * Construction/disabled operations never acquire credentials, open HTTP, inspect SDK events, or register a
 * destination. Local Binder delivery and this encrypted, persistent remote outbox have independent ACKs.
 */
public final class RelayClient {
    public interface Clock { long nowMillis(); }
    /** Implement using Reliability.registerDestination(id); Reliability.journal(id). Never pass journal(local). */
    public interface DestinationRegistry {
        IncidentJournal registerDestination(String destinationId) throws IOException;
        /** Stop future appends AND drop this destination's old cursors, preserving all other destinations. */
        void unregisterDestination(String destinationId) throws IOException;
    }
    public enum Outcome { DISABLED, NO_CONSENT, EMPTY, ACKNOWLEDGED, RETRY_SCHEDULED, DROPPED }
    private final RelayConfig config;
    private final RelayConsent consent;
    private final RelayCredentials.Provider credentials;
    private final RelayCrypto crypto;
    private final DiskRelayQueue queue;
    private final RelayTransport transport;
    private final Clock clock;
    private final String destinationId;
    private IncidentJournal eventJournal;
    private DestinationRegistry registry;
    private volatile RelayCredentials.Token activeToken;
    private boolean permanentlyDisabled;

    public RelayClient(RelayConfig config, RelayConsent consent, RelayCredentials.Provider credentials,
                       RelayCrypto crypto, DiskRelayQueue queue) {
        this(config, consent, credentials, crypto, queue, new HttpsRelayTransport(), new Clock() {
            @Override public long nowMillis() { return System.currentTimeMillis(); }
        });
    }
    public RelayClient(RelayConfig config, RelayConsent consent, RelayCredentials.Provider credentials,
                       RelayCrypto crypto, DiskRelayQueue queue, RelayTransport transport, Clock clock) {
        if (config == null || consent == null || transport == null || clock == null)
            throw new IllegalArgumentException("Relay configuration/consent/transport/clock required");
        if (config.enabled && (credentials == null || crypto == null || queue == null))
            throw new IllegalArgumentException("Enabled relay requires scoped credentials, recipient key and durable queue");
        this.config = config; this.consent = consent; this.credentials = credentials; this.crypto = crypto;
        this.queue = queue; this.transport = transport; this.clock = clock;
        try {
            String identity = (config.endpoint == null ? "disabled" : config.endpoint.toASCIIString()) + "\n"
                    + config.applicationId + "\n" + (crypto == null ? "disabled" : crypto.keyId());
            destinationId = "relay:" + RelayCrypto.hex(RelayCrypto.sha256(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    public String destinationId() { return destinationId; }

    /** Explicit opt-in registration of FUTURE SDK events only; never exports a pre-consent local backlog. */
    public synchronized void attachEvents(DestinationRegistry sdk) throws IOException {
        if (!config.enabled || permanentlyDisabled) return;
        RelayConsent.Permit permit = consent.permit(RelayScope.EVENTS);
        if (sdk == null) throw new IllegalArgumentException("SDK destination registry required");
        permit.check();
        IncidentJournal journal = sdk.registerDestination(destinationId);
        try {
            permit.check();
            if (journal == null) throw new IOException("SDK destination journal unavailable");
            registry = sdk;
            eventJournal = journal;
        } catch (IOException failure) {
            try { sdk.unregisterDestination(destinationId); } catch (IOException unregister) { failure.addSuppressed(unregister); }
            throw failure;
        }
    }

    /**
     * Copies exact canonical root SDK event bytes into a fsynced encrypted outbox BEFORE its destination ACK.
     * A crash between these operations may replay IDs; the receiver must deduplicate incident IDs too.
     * Queue full/oversized events are never silently ACKed. Does not make a network request.
     */
    public synchronized int capturePending(int maximumEvents) throws IOException {
        if (!config.enabled || permanentlyDisabled || !consent.allows(RelayScope.EVENTS)) return 0;
        RelayConsent.Permit permit = consent.permit(RelayScope.EVENTS);
        if (eventJournal == null) throw new IOException("Explicit relay destination registration required");
        if (maximumEvents < 1 || maximumEvents > 1024) throw new IllegalArgumentException("Invalid event snapshot bound");
        List<Incident> events = new ArrayList<Incident>(eventJournal.snapshotPending(maximumEvents));
        if (events.isEmpty()) return 0;
        byte[] payload = null;
        try {
            while (!events.isEmpty()) {
                payload = IncidentProtocol.encodeEvents(events).getBytes(StandardCharsets.UTF_8);
                if (payload.length <= config.maxBatchBytes) break;
                Arrays.fill(payload, (byte) 0); payload = null;
                events.remove(events.size() - 1);
            }
            if (events.isEmpty()) throw new IOException("One event exceeds the configured relay batch cap");
            List<String> ids = new ArrayList<String>();
            for (Incident event : events) {
                if (!config.applicationId.equals(event.packageName)) throw new IOException("SDK event application mismatch");
                ids.add(event.eventId);
            }
            String batchId = "events-" + RelayCrypto.hex(RelayCrypto.sha256(payload));
            RelayBatch batch = new RelayBatch(config.applicationId, batchId, RelayScope.EVENTS, "", ids);
            byte[] envelope = crypto.encryptRequest(batch, payload, config.maxBatchBytes);
            permit.check();
            if (!queue.enqueue(batch, envelope, clock.nowMillis())) return 0;
            permit.check();
            eventJournal.acknowledgeDurablyCommitted(ids); // This destination only; remote ACK is independent.
            return ids.size();
        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
    }

    /** Explicit upload of a PortableBackup archive, never a filesystem tree. Returns false if disabled/full. */
    public synchronized boolean enqueueBackupUpload(String backupId, Path encryptedArchive) throws IOException {
        if (!config.enabled || permanentlyDisabled || !consent.allows(RelayScope.BACKUP_UPLOAD)) return false;
        RelayConsent.Permit permit = consent.permit(RelayScope.BACKUP_UPLOAD);
        RelayBatch.identifier(backupId, 128);
        byte[] archive = readArchive(encryptedArchive, permit);
        try {
            requireArchiveHeader(archive);
            String batchId = "backup-" + RelayCrypto.hex(RelayCrypto.sha256(concatId(backupId, archive)));
            RelayBatch batch = new RelayBatch(config.applicationId, batchId, RelayScope.BACKUP_UPLOAD, backupId,
                    Collections.<String>emptyList());
            byte[] envelope = crypto.encryptRequest(batch, archive, config.maxBatchBytes);
            permit.check();
            return queue.enqueue(batch, envelope, clock.nowMillis());
        } finally { Arrays.fill(archive, (byte) 0); }
    }

    /** One request maximum. Retry scheduling is durable, bounded exponential backoff; no hidden timers. */
    public synchronized Outcome flushOnce() throws IOException {
        if (!config.enabled || permanentlyDisabled) return Outcome.DISABLED;
        long now = clock.nowMillis();
        DiskRelayQueue.Pending pending = queue.next(now);
        if (pending == null) return Outcome.EMPTY;
        if (!consent.allows(pending.batch.scope)) return Outcome.NO_CONSENT;
        RelayConsent.Permit permit = consent.permit(pending.batch.scope);
        byte[] envelope = pending.encryptedPayload();
        try (RelayCredentials.Token token = acquire(pending.batch.scope, permit, now)) {
            permit.check();
            RelayTransport.Response response = transport.post(config, pending.batch, envelope, token, permit, 0);
            permit.check();
            byte[] body = response.body();
            if (response.status == 200 && body.length == 0
                    && crypto.verifyReceipt(pending.batch, envelope, body, response.receipt())) {
                queue.acknowledge(pending);
                return Outcome.ACKNOWLEDGED;
            }
            if (response.status >= 300 && response.status < 400
                    || response.status >= 400 && response.status < 500 && response.status != 401
                    && response.status != 403 && response.status != 408 && response.status != 429) {
                queue.discard(pending);
                return Outcome.DROPPED;
            }
            return retry(pending, now);
        } catch (RelayConsent.ConsentRevokedException revoked) { return Outcome.NO_CONSENT; }
        catch (IOException failure) { return retry(pending, now); }
        finally { activeToken = null; }
    }

    /**
     * Explicit authorized download through the SAME endpoint; the resource ID cannot encode a URL/path.
     * Creates only an encrypted archive. PortableBackup.restoreArchive must validate its user key/schema
     * before any live-data restore. A failed transfer/auth/receipt leaves an existing destination untouched.
     */
    public synchronized void downloadBackup(String backupId, Path encryptedDestination) throws IOException {
        if (!config.enabled || permanentlyDisabled) throw new IOException("Relay is disabled");
        RelayBatch.identifier(backupId, 128);
        RelayConsent.Permit permit = consent.permit(RelayScope.BACKUP_DOWNLOAD);
        RelayBatch batch = new RelayBatch(config.applicationId, "download-" + UUID.randomUUID().toString(),
                RelayScope.BACKUP_DOWNLOAD, backupId, Collections.<String>emptyList());
        byte[] request = crypto.encryptRequest(batch, new byte[0], config.maxBatchBytes);
        long now = clock.nowMillis();
        try (RelayCredentials.Token token = acquire(batch.scope, permit, now)) {
            permit.check();
            RelayTransport.Response response = transport.post(config, batch, request, token, permit, config.maxBatchBytes + 128);
            permit.check();
            byte[] body = response.body();
            if (response.status != 200 || body.length > config.maxBatchBytes + 128
                    || !crypto.verifyReceipt(batch, request, body, response.receipt()))
                throw new IOException("Authorized backup download failed");
            byte[] archive = crypto.decryptResponse(batch, body, config.maxBatchBytes);
            try {
                requireArchiveHeader(archive);
                installDownload(encryptedDestination, archive, permit);
            } finally { Arrays.fill(archive, (byte) 0); }
        } finally { activeToken = null; }
    }
    /**
     * Full revoke/disable: disconnect in-flight I/O, clear credentials/key/outbox and unregister SDK cursor.
     * Cannot retract bytes already received by an endpoint. Regrant requires a NEW client/key/registration;
     * disabled-period events must never be uploaded. A cleanup failure is surfaced, never treated as success.
     */
    public void disableAndDelete() throws IOException {
        consent.revokeAll();
        RelayCredentials.Token token = activeToken;
        if (token != null) token.close();
        synchronized (this) {
            permanentlyDisabled = true;
            IOException failure = null;
            if (registry != null) {
                try { registry.unregisterDestination(destinationId); }
                catch (IOException unregister) { failure = unregister; }
            }
            eventJournal = null;
            if (queue != null) {
                try { queue.clear(); }
                catch (IOException cleanup) { if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup); }
            }
            if (crypto != null) crypto.close();
            if (failure != null) throw failure;
            registry = null;
        }
    }
    /** Prefer disableAndDelete for a full withdrawal, including root SDK destination removal. */
    public void revokeAll(boolean deleteLocalQueue) throws IOException {
        if (deleteLocalQueue) { disableAndDelete(); return; }
        consent.revokeAll();
        RelayCredentials.Token token = activeToken;
        if (token != null) token.close();
        synchronized (this) {
            if (registry != null) registry.unregisterDestination(destinationId);
            registry = null;
            eventJournal = null;
        }
    }
    private RelayCredentials.Token acquire(RelayScope scope, RelayConsent.Permit permit, long now) throws IOException {
        permit.check();
        RelayCredentials.Token token = credentials.acquire(config.applicationId, scope);
        if (token == null) throw new IOException("Scoped token unavailable");
        try {
            permit.check(); token.validate(config.applicationId, scope, now);
            activeToken = token;
            return token;
        }
        catch (IOException failure) { token.close(); throw failure; }
    }
    private Outcome retry(DiskRelayQueue.Pending pending, long now) throws IOException {
        if (!consent.allows(pending.batch.scope)) return Outcome.NO_CONSENT;
        long delay = config.initialBackoffMillis;
        for (int i = 0; i < pending.attempts && delay < config.maxBackoffMillis; i++)
            delay = Math.min(config.maxBackoffMillis, delay * 2);
        queue.retry(pending, now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay);
        return pending.attempts + 1 >= config.maxAttempts ? Outcome.DROPPED : Outcome.RETRY_SCHEDULED;
    }
    private byte[] readArchive(Path archive, RelayConsent.Permit permit) throws IOException {
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.size(archive) > config.maxBatchBytes)
            throw new IOException("Backup archive exceeds relay cap or is not regular file");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) {
                permit.check();
                if (count == 0) throw new IOException("Archive stream made no progress");
                if (count > config.maxBatchBytes - bytes.size()) throw new IOException("Backup archive exceeds relay cap");
                bytes.write(buffer, 0, count);
            }
        }
        return bytes.toByteArray();
    }
    private static byte[] concatId(String id, byte[] archive) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(id.length() + archive.length + 4);
        java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
        RelayBatch.text(out, id); out.write(archive); return buffer.toByteArray();
    }
    private static void requireArchiveHeader(byte[] bytes) throws IOException {
        byte[] magic = "IDEBKP01".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < 57) throw new IOException("Only encrypted PortableBackup archives may be transferred");
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i])
            throw new IOException("Only encrypted PortableBackup archives may be transferred");
        // Full integrity/user-key validation belongs to PortableBackup; relay never receives that key.
    }
    private static void installDownload(Path destination, byte[] bytes, RelayConsent.Permit permit) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)
                || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private regular-file destination required");
        Path staging = Files.createTempFile(parent, ".relay-download-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException unavailable) { /* Caller-supplied private ACL. */ }
            try (FileOutputStream output = new FileOutputStream(staging.toFile())) {
                for (int offset = 0; offset < bytes.length; offset += 16384) {
                    permit.check(); output.write(bytes, offset, Math.min(16384, bytes.length - offset));
                }
                output.getFD().sync();
            }
            permit.check();
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staging); }
    }
}
