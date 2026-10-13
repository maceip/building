package dev.ide.applog.relay;

import dev.ide.applog.Incident;
import dev.ide.applog.IncidentJournal;
import dev.ide.applog.IncidentProtocol;
import dev.ide.applog.backup.BackupKey;
import dev.ide.applog.backup.BackupManifest;
import dev.ide.applog.backup.PortableBackup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RelayClientTest {
    @TempDir Path temp;
    private final TestClock clock = new TestClock();
    private final byte[] recipient = new byte[32];
    private RelayConfig config() {
        return RelayConfig.builder("dev.test").enabled(true).endpoint(URI.create("https://relay.example.test/v1"))
                .bounds(8192, 8, 65536, 86400000).retries(3, 1000, 4000).build();
    }

    @Test void disabledAndConsentlessConstructionNeverCreatesHttpAcquiresTokensOrReadsJournal() throws Exception {
        RelayConfig disabled = RelayConfig.builder("dev.test").build();
        AtomicInteger requests = new AtomicInteger();
        RelayTransport forbidden = (c, b, p, t, permission, maximum) -> { requests.incrementAndGet(); throw new AssertionError("outbound"); };
        RelayClient client = new RelayClient(disabled, new RelayConsent(), null, null, null, forbidden, clock);
        client.attachEvents(new DestinationJournal());
        assertEquals(0, client.capturePending(10));
        assertFalse(client.enqueueBackupUpload("backup1", temp.resolve("does-not-exist")));
        assertEquals(RelayClient.Outcome.DISABLED, client.flushOnce());
        assertThrows(IOException.class, () -> client.downloadBackup("backup1", temp.resolve("out")));
        assertEquals(0, requests.get());
        RelayConfig enabled = config(); RelayConsent noConsent = new RelayConsent();
        AtomicInteger tokens = new AtomicInteger();
        DiskRelayQueue queue = new DiskRelayQueue(temp, enabled, "fixture");
        RelayClient unconsented = new RelayClient(enabled, noConsent, (app, scope) -> { tokens.incrementAndGet(); throw new AssertionError(); },
                new RelayCrypto("fixture", recipient), queue, forbidden, clock);
        assertThrows(RelayConsent.ConsentRevokedException.class, () -> unconsented.attachEvents(new DestinationJournal()));
        assertEquals(0, unconsented.capturePending(10));
        assertEquals(RelayClient.Outcome.EMPTY, unconsented.flushOnce());
        assertEquals(0, requests.get()); assertEquals(0, tokens.get());
    }

    @Test void independentLocalAckCannotLoseRelayPendingAndQueueDurabilityPrecedesRelayJournalAck() throws Exception {
        Harness h = new Harness();
        Incident first = event(); h.journal.append(first);
        h.journal.localPending.remove(first); // Receiver's LOCAL acknowledgment must not remove relay cursor.
        h.journal.beforeAck = () -> {
            try { assertEquals(1, new DiskRelayQueue(temp, h.config, "fixture").size(clock.nowMillis())); }
            catch (IOException error) { throw new AssertionError(error); }
        };
        assertEquals(1, h.client.capturePending(16)); assertTrue(h.journal.remotePending.isEmpty());
        DiskRelayQueue reopened = new DiskRelayQueue(temp, h.config, "fixture");
        DiskRelayQueue.Pending stored = reopened.next(clock.nowMillis());
        assertArrayEquals(IncidentProtocol.encodeEvents(Collections.singletonList(first)).getBytes(StandardCharsets.UTF_8),
                h.receiver.crypto.decryptRequest(stored.batch, stored.encryptedPayload(), h.config.maxBatchBytes));
        try (java.util.stream.Stream<Path> files = Files.walk(temp)) {
            files.filter(p -> p.toString().endsWith(".rq")).forEach(p -> {
                try { assertFalse(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).contains("fixture event body")); }
                catch (IOException e) { throw new AssertionError(e); }
            });
        }
        assertEquals(RelayClient.Outcome.ACKNOWLEDGED, h.client.flushOnce()); assertEquals(0, reopened.size(clock.nowMillis()));
        assertEquals(Collections.singleton(first.eventId), h.receiver.committed);
        Incident second = event(); h.journal.append(second); h.client.capturePending(16); h.client.flushOnce();
        assertEquals(Collections.singletonList(second), h.journal.localPending, "remote ACK must not consume local cursor");
    }

    @Test void lostAckReconnectAndFreshClientReplaySameIdsWithBoundedBackoffDeduplicatesReceiver() throws Exception {
        Harness h = new Harness(); Incident event = event(); h.journal.append(event); h.client.capturePending(16);
        h.receiver.loseNextAck = true;
        assertEquals(RelayClient.Outcome.RETRY_SCHEDULED, h.client.flushOnce());
        assertEquals(1, h.receiver.requests); assertEquals(1, h.receiver.committed.size());
        assertEquals(RelayClient.Outcome.EMPTY, h.client.flushOnce()); assertEquals(1, h.receiver.requests);
        clock.now += 1000;
        RelayClient fresh = new RelayClient(h.config, h.consent, h.credentials,
                new RelayCrypto("fixture", recipient), new DiskRelayQueue(temp, h.config, "fixture"), h.receiver, clock);
        assertEquals(RelayClient.Outcome.ACKNOWLEDGED, fresh.flushOnce());
        assertEquals(2, h.receiver.requests); assertEquals(1, h.receiver.committed.size());
        assertEquals(1, h.receiver.uniqueCommits);
    }

    @Test void missingForgedWrongScopeAndReplayedReceiptsNeverDeleteUnacknowledgedQueue() throws Exception {
        Harness h = new Harness(); h.journal.append(event()); h.client.capturePending(16);
        h.receiver.invalidReceipt = true;
        assertEquals(RelayClient.Outcome.RETRY_SCHEDULED, h.client.flushOnce());
        assertEquals(1, h.queue.size(clock.nowMillis()));
        DiskRelayQueue.Pending pending = h.queue.next(clock.nowMillis() + 1000);
        RelayBatch other = new RelayBatch("dev.other", pending.batch.batchId, RelayScope.EVENTS, "", pending.batch.incidentIds);
        byte[] forged = h.receiver.crypto.receipt(other, pending.encryptedPayload(), new byte[0]);
        assertFalse(h.receiver.crypto.verifyReceipt(pending.batch, pending.encryptedPayload(), new byte[0], forged));
        byte[] valid = h.receiver.crypto.receipt(pending.batch, pending.encryptedPayload(), new byte[0]);
        byte[] changed = pending.encryptedPayload(); changed[changed.length - 1] ^= 1;
        assertFalse(h.receiver.crypto.verifyReceipt(pending.batch, changed, new byte[0], valid));
        assertThrows(IOException.class, () -> h.receiver.crypto.decryptRequest(other, pending.encryptedPayload(), 8192));
        clock.now += 1000; h.receiver.invalidReceipt = false;
        assertEquals(RelayClient.Outcome.ACKNOWLEDGED, h.client.flushOnce());
    }

    @Test void networkTlsAuthFailuresAreBoundedAndDoNotAckSourceDataAsDelivered() throws Exception {
        for (int status : new int[] { 401, 403, 503, -1 }) {
            Path parent = Files.createDirectory(temp.resolve("case" + status));
            RelayConfig cfg = config(); RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
            DiskRelayQueue queue = new DiskRelayQueue(parent, cfg, "fixture"); RelayCrypto crypto = new RelayCrypto("fixture", recipient);
            DestinationJournal journal = new DestinationJournal(); AtomicInteger requests = new AtomicInteger();
            RelayTransport transport = (c, b, payload, token, permit, max) -> {
                requests.incrementAndGet();
                if (status == -1) throw new javax.net.ssl.SSLHandshakeException("fixture TLS failure");
                return new RelayTransport.Response(status, null, null);
            };
            RelayClient client = new RelayClient(cfg, consent, credentials(), crypto, queue, transport, clock);
            client.attachEvents(journal); journal.append(event()); client.capturePending(16);
            assertEquals(RelayClient.Outcome.RETRY_SCHEDULED, client.flushOnce());
            clock.now += 1000; assertEquals(RelayClient.Outcome.RETRY_SCHEDULED, client.flushOnce());
            clock.now += 2000; assertEquals(RelayClient.Outcome.DROPPED, client.flushOnce());
            assertEquals(3, requests.get()); assertEquals(0, queue.size(clock.nowMillis())); assertEquals(1, queue.droppedCount());
        }
    }

    @Test void expiredLongLivedAndWrongAppTokensDoNotReachTransport() throws Exception {
        Harness h = new Harness(); h.journal.append(event()); h.client.capturePending(16);
        for (RelayCredentials.Token token : Arrays.asList(
                new RelayCredentials.Token("dev.test", RelayScope.EVENTS, clock.now, "expired".toCharArray()),
                new RelayCredentials.Token("dev.test", RelayScope.EVENTS, clock.now + 86400000, "long-lived".toCharArray()),
                new RelayCredentials.Token("dev.other", RelayScope.EVENTS, clock.now + 300000, "other-app".toCharArray()))) {
            RelayClient client = new RelayClient(h.config, h.consent, (a, s) -> token, new RelayCrypto("fixture", recipient), h.queue, h.receiver, clock);
            client.flushOnce(); clock.now += 4000;
            assertEquals(0, h.receiver.requests);
        }
    }

    @Test void revocationStopsQueuedAndSubsequentRequestsAndDisabledPeriodEventsNeverReappearAfterRegrant() throws Exception {
        Harness h = new Harness(); Incident before = event(); h.journal.append(before); h.client.capturePending(16);
        h.client.disableAndDelete(); assertEquals(0, h.queue.size(clock.nowMillis())); assertFalse(h.journal.registered);
        assertEquals(RelayClient.Outcome.DISABLED, h.client.flushOnce());
        Incident whileDisabled = event(); h.journal.append(whileDisabled);
        assertEquals(0, h.client.capturePending(16)); assertEquals(0, h.receiver.requests);
        h.consent.grant(RelayScope.EVENTS); h.client.attachEvents(h.journal);
        assertFalse(h.journal.registered, "disabled instance cannot be re-enabled with a wiped key");
        RelayClient regranted = new RelayClient(h.config, h.consent, h.credentials, new RelayCrypto("fixture", recipient), h.queue, h.receiver, clock);
        regranted.attachEvents(h.journal); Incident after = event(); h.journal.append(after);
        assertEquals(1, regranted.capturePending(16)); assertEquals(RelayClient.Outcome.ACKNOWLEDGED, regranted.flushOnce());
        assertEquals(Collections.singleton(after.eventId), h.receiver.committed);
        assertTrue(h.journal.localPending.contains(whileDisabled));
    }

    @Test void revocationDuringCredentialAcquisitionPreventsDispatch() throws Exception {
        Harness h = new Harness(); h.journal.append(event()); h.client.capturePending(16);
        RelayClient client = new RelayClient(h.config, h.consent, (app, scope) -> {
            h.consent.revokeAll(); return new RelayCredentials.Token(app, scope, clock.now + 300000, "fixture-short-token".toCharArray());
        }, new RelayCrypto("fixture", recipient), h.queue, h.receiver, clock);
        assertEquals(RelayClient.Outcome.NO_CONSENT, client.flushOnce()); assertEquals(0, h.receiver.requests);
    }

    @Test void explicitScopedBackupUploadDownloadUsesSameEndpointAndKeepsPreviousFileOnFailure() throws Exception {
        Harness h = new Harness(); Path archive = createBackup();
        assertFalse(h.client.enqueueBackupUpload("notes", archive));
        assertThrows(RelayConsent.ConsentRevokedException.class, () -> h.client.downloadBackup("notes", temp.resolve("download.bkp")));
        h.consent.grant(RelayScope.BACKUP_UPLOAD); assertTrue(h.client.enqueueBackupUpload("notes", archive));
        assertEquals(RelayClient.Outcome.ACKNOWLEDGED, h.client.flushOnce());
        assertArrayEquals(Files.readAllBytes(archive), h.receiver.archives.get("notes"));
        h.consent.grant(RelayScope.BACKUP_DOWNLOAD); Path destination = temp.resolve("download.bkp");
        Files.write(destination, "previous encrypted destination".getBytes(StandardCharsets.UTF_8)); byte[] previous = Files.readAllBytes(destination);
        h.receiver.invalidReceipt = true;
        assertThrows(IOException.class, () -> h.client.downloadBackup("notes", destination)); assertArrayEquals(previous, Files.readAllBytes(destination));
        h.receiver.invalidReceipt = false; h.client.downloadBackup("notes", destination);
        assertArrayEquals(Files.readAllBytes(archive), Files.readAllBytes(destination));
        assertTrue(h.receiver.scopes.containsAll(Arrays.asList(RelayScope.BACKUP_UPLOAD, RelayScope.BACKUP_DOWNLOAD)));
        assertThrows(IllegalArgumentException.class, () -> h.client.downloadBackup("https://other.example/stolen", destination));
        assertThrows(IllegalArgumentException.class, () -> h.client.downloadBackup("../other-app", destination));
    }

    @Test void fullQueueDoesNotAcknowledgeJournalAndExpiredOutboxDeletesWithoutNetwork() throws Exception {
        RelayConfig cfg = RelayConfig.builder("dev.test").enabled(true).endpoint(URI.create("https://relay.example.test"))
                .bounds(8192, 1, 16384, 1000).build();
        RelayConsent consent = new RelayConsent(); consent.grant(RelayScope.EVENTS);
        DiskRelayQueue queue = new DiskRelayQueue(temp, cfg, "fixture"); DestinationJournal journal = new DestinationJournal();
        ReferenceReceiver receiver = new ReferenceReceiver(new RelayCrypto("fixture", recipient));
        RelayClient client = new RelayClient(cfg, consent, credentials(), new RelayCrypto("fixture", recipient), queue, receiver, clock);
        client.attachEvents(journal); journal.append(event()); assertEquals(1, client.capturePending(16));
        Incident second = event(); journal.append(second); assertEquals(0, client.capturePending(16));
        assertEquals(Collections.singletonList(second), journal.remotePending);
        clock.now += 1001; assertEquals(0, queue.size(clock.nowMillis())); assertEquals(0, receiver.requests);
        assertEquals(1, client.capturePending(16)); assertEquals(RelayClient.Outcome.ACKNOWLEDGED, client.flushOnce());
    }

    @Test void interruptedJournalHandoffReusesDurableEncryptedBatchBeforeAcknowledging() throws Exception {
        Harness h = new Harness(); h.journal.append(event());
        h.journal.failNextAck = true;
        assertThrows(IOException.class, () -> h.client.capturePending(16));
        DiskRelayQueue.Pending before = h.queue.next(clock.nowMillis());
        assertEquals(1, h.journal.remotePending.size());
        assertEquals(1, h.client.capturePending(16));
        DiskRelayQueue.Pending after = h.queue.next(clock.nowMillis());
        assertEquals(before.batch.batchId, after.batch.batchId);
        assertArrayEquals(before.encryptedPayload(), after.encryptedPayload());
        assertEquals(1, h.queue.size(clock.nowMillis()));
        assertEquals(RelayClient.Outcome.ACKNOWLEDGED, h.client.flushOnce());
        assertEquals(1, h.receiver.uniqueCommits);
    }

    @Test void corruptQueueRecordIsDeletedWithinBoundsWithoutTransportOrPlaintextRecovery() throws Exception {
        Harness h = new Harness(); h.journal.append(event()); h.client.capturePending(16);
        Path record;
        try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
            record = paths.filter(path -> path.toString().endsWith(".rq")).findFirst().get();
        }
        byte[] bytes = Files.readAllBytes(record); bytes[bytes.length - 1] ^= 1; Files.write(record, bytes);
        assertEquals(RelayClient.Outcome.EMPTY, h.client.flushOnce());
        assertEquals(0, h.receiver.requests); assertEquals(0, h.queue.size(clock.nowMillis()));
        assertEquals(1, h.queue.droppedCount());
    }

    private final class Harness {
        final RelayConfig config = config();
        final RelayConsent consent = new RelayConsent();
        final RelayCredentials.Provider credentials = credentials();
        final DiskRelayQueue queue = new DiskRelayQueue(temp, config, "fixture");
        final ReferenceReceiver receiver = new ReferenceReceiver(new RelayCrypto("fixture", recipient));
        final DestinationJournal journal = new DestinationJournal();
        final RelayClient client;
        Harness() throws Exception {
            consent.grant(RelayScope.EVENTS);
            client = new RelayClient(config, consent, credentials, new RelayCrypto("fixture", recipient), queue, receiver, clock);
            client.attachEvents(journal);
        }
    }
    private RelayCredentials.Provider credentials() {
        return (app, scope) -> new RelayCredentials.Token(app, scope, clock.now + 300000, "fixture-short-token".toCharArray());
    }
    private static Incident event() {
        return new Incident(UUID.randomUUID().toString(), "12345678-1234-1234-1234-123456789012", "dev.test", "debug-1", "abc123",
                1234L, "nonfatal_java", "fixture event body");
    }
    private Path createBackup() throws Exception {
        BackupManifest manifest = new BackupManifest("dev.test", "notes", 1, Collections.singletonList(
                new BackupManifest.Entry("records/notes.json", BackupManifest.Type.USER_RECORDS)));
        Path archive = temp.resolve("local-backup.bkp");
        try (BackupKey key = BackupKey.passphrase("separately transferred backup passphrase".toCharArray())) {
            new PortableBackup(new PortableBackup.Limits(8, 1024, 1024, 2048, 8192, 100000)).exportArchive(archive, manifest, key,
                    PortableBackup.SnapshotWork::run, entry -> new ByteArrayInputStream("user notes".getBytes(StandardCharsets.UTF_8)), PortableBackup.Cancellation.NONE);
        }
        return archive;
    }
    private static final class TestClock implements RelayClient.Clock {
        long now = 1700000000000L;
        @Override public long nowMillis() { return now; }
    }
    /** Stub of ONLY the already-frozen SDK destination contract; local and relay ACKs stay independent. */
    private static final class DestinationJournal implements RelayClient.DestinationRegistry, IncidentJournal {
        final List<Incident> localPending = new ArrayList<Incident>();
        final List<Incident> remotePending = new ArrayList<Incident>();
        boolean registered, failNextAck;
        Runnable beforeAck;
        void append(Incident event) { localPending.add(event); if (registered) remotePending.add(event); }
        @Override public IncidentJournal registerDestination(String destinationId) {
            assertTrue(destinationId.startsWith("relay:")); registered = true; return this;
        }
        @Override public void unregisterDestination(String destinationId) { registered = false; remotePending.clear(); }
        @Override public List<Incident> snapshotPending(int maximum) throws IOException {
            if (!registered) throw new IOException("destination not registered");
            return new ArrayList<Incident>(remotePending.subList(0, Math.min(maximum, remotePending.size())));
        }
        @Override public void acknowledgeDurablyCommitted(Collection<String> ids) throws IOException {
            if (beforeAck != null) beforeAck.run();
            if (failNextAck) { failNextAck = false; throw new IOException("fixture interrupted handoff after queue fsync"); }
            remotePending.removeIf(event -> ids.contains(event.eventId));
        }
        @Override public long droppedCount() { return 0; }
    }
    /** No listening socket or deployment. Demonstrates scoped receiver/decrypt/dedup/receipt adapter contract. */
    private static final class ReferenceReceiver implements RelayTransport {
        final RelayCrypto crypto;
        final Set<String> committed = new HashSet<String>();
        final Map<String, byte[]> archives = new LinkedHashMap<String, byte[]>();
        final Set<RelayScope> scopes = new HashSet<RelayScope>();
        int requests, uniqueCommits;
        boolean loseNextAck, invalidReceipt;
        ReferenceReceiver(RelayCrypto crypto) { this.crypto = crypto; }
        @Override public Response post(RelayConfig config, RelayBatch batch, byte[] payload, RelayCredentials.Token token,
                                      RelayConsent.Permit permit, int maximum) throws IOException {
            permit.check(); requests++; scopes.add(batch.scope);
            assertEquals("https", config.endpoint.getScheme());
            assertEquals("relay.example.test", config.endpoint.getHost());
            assertTrue(config.endpoint.getPath().isEmpty() || config.endpoint.getPath().equals("/v1"));
            assertEquals("dev.test", batch.applicationId);
            byte[] decoded = crypto.decryptRequest(batch, payload, config.maxBatchBytes); byte[] response = new byte[0];
            if (batch.scope == RelayScope.EVENTS) {
                assertTrue(new String(decoded, StandardCharsets.UTF_8).startsWith("[{\"eventId\":"));
                for (String id : batch.incidentIds) if (committed.add(id)) uniqueCommits++;
            } else if (batch.scope == RelayScope.BACKUP_UPLOAD) archives.put(batch.resourceId, decoded);
            else {
                assertEquals(0, decoded.length);
                byte[] archive = archives.get(batch.resourceId);
                if (archive == null) return new Response(404, null, null);
                response = crypto.encryptResponse(batch, archive, config.maxBatchBytes);
            }
            if (loseNextAck) { loseNextAck = false; throw new IOException("fixture lost response after commit"); }
            return new Response(200, invalidReceipt ? new byte[32] : crypto.receipt(batch, payload, response), response);
        }
    }
}
