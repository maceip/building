package dev.ide.applog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiskJournalTest {
    @TempDir File temp;
    static Config config(String build) {
        return new Config.Builder().enabled(true).consentGranted(true).buildId(build).sourceRevision("revision-" + build)
            .ideComponent("dev.ide.android", "dev.ide.android.IncidentSinkService")
            .pairingNonce("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
            .maxEvents(4).maxJournalBytes(32768).build();
    }
    File directory() { return new File(temp, "process"); }

    @Test void survivesRestartAndKeepsStableIdsUntilDurableAck() throws Exception {
        String eventId, session;
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            Incident event = store.incident("breadcrumb", "screen opened");
            eventId = event.eventId; session = event.sessionId;
            assertTrue(store.append(event));
            List<Incident> snapshot = store.snapshot("local", 16);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
            assertEquals(eventId, store.snapshot("local", 16).get(0).eventId);
        }
        try (DiskJournal reopened = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertEquals(session, reopened.current.sessionId);
            assertEquals(eventId, reopened.snapshot("local", 16).get(0).eventId);
            reopened.acknowledge("local", Collections.singleton("unknown-id"));
            assertEquals(1, reopened.snapshot("local", 16).size());
            reopened.acknowledge("local", Collections.singleton(eventId));
            assertTrue(reopened.snapshot("local", 16).isEmpty());
        }
        try (DiskJournal reopened = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertTrue(reopened.snapshot("local", 16).isEmpty());
        }
    }

    @Test void fullQueueNeverEvictsUnacknowledgedAndFatalHasReservedSpace() throws Exception {
        String first, fatal;
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            first = store.incident("breadcrumb", "first").eventId;
            Incident event = new Incident(first, store.current.sessionId, "sample.app", "v1", "revision-v1", 10, "breadcrumb", "first");
            assertTrue(store.append(event));
            for (int i = 0; i < 3; i++) assertTrue(store.append(store.incident("lifecycle", "resumed")));
            assertFalse(store.append(store.incident("breadcrumb", "overflow")));
            assertEquals(1, store.droppedCount());
            Incident crash = store.incident("fatal_java", "java.lang.IllegalStateException");
            fatal = crash.eventId;
            assertTrue(store.appendFatal(crash));
            assertFalse(store.appendFatal(store.incident("fatal_java", "second crash")));
            store.flushDrops();
            assertEquals(2, store.droppedCount());
            assertEquals(fatal, store.snapshot("local", 16).get(0).eventId);
            assertEquals(first, store.snapshot("local", 16).get(1).eventId);
            assertTrue(new File(directory(), "journal.bin").length() <= 32768);
            assertTrue(new File(directory(), "fatal.bin").length() <= DiskJournal.FATAL_MAX_BYTES);
        }
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertEquals(5, store.snapshot("local", 16).size());
            assertEquals(2, store.droppedCount());
            assertEquals(fatal, store.snapshot("local", 16).get(0).eventId);
            store.acknowledge("local", Arrays.asList(first, fatal));
            assertEquals(3, store.snapshot("local", 16).size());
            assertTrue(store.appendFatal(store.incident("fatal_java", "new crash")));
        }
    }

    @Test void incompleteReplacementCannotDestroyCommittedJournalOrResurrectAck() throws Exception {
        String id;
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            Incident event = store.incident("breadcrumb", "retained"); id = event.eventId; store.append(event);
        }
        Files.write(new File(directory(), "journal.bin.tmp").toPath(), new byte[] { 1, 2, 3 });
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertEquals(id, store.snapshot("local", 16).get(0).eventId);
            assertFalse(new File(directory(), "journal.bin.tmp").exists());
            store.acknowledge("local", Collections.singleton(id));
        }
        Files.write(new File(directory(), "journal.bin.tmp").toPath(), "stale uncommitted copy".getBytes(Redactor.UTF8));
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertTrue(store.snapshot("local", 16).isEmpty());
        }
    }

    @Test void corruptCommittedSnapshotFailsClosedWithoutDeletingEvidence() throws Exception {
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) { store.append(store.incident("breadcrumb", "keep")); }
        File file = new File(directory(), "journal.bin");
        byte[] bytes = Files.readAllBytes(file.toPath());
        byte[] truncated = Arrays.copyOf(bytes, bytes.length - 9);
        Files.write(file.toPath(), truncated);
        assertThrows(IOException.class, () -> new DiskJournal(directory(), "sample.app", config("v1")));
        assertArrayEquals(truncated, Files.readAllBytes(file.toPath()));
    }

    @Test void failedAckReplacementLeavesPendingOnRestart() throws Exception {
        String id;
        DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"));
        Incident event = store.incident("breadcrumb", "keep until committed"); id = event.eventId; store.append(event);
        File block = new File(directory(), "journal.bin.tmp"); assertTrue(block.mkdir());
        Files.write(new File(block, "block").toPath(), new byte[] {1});
        assertThrows(IOException.class, () -> store.acknowledge("local", Collections.singleton(id)));
        store.close();
        Files.delete(new File(block, "block").toPath()); Files.delete(block.toPath());
        try (DiskJournal reopened = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertEquals(id, reopened.snapshot("local", 16).get(0).eventId);
        }
    }

    @Test void destinationsHaveIndependentAcksAndNoRetroactiveOptIn() throws Exception {
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            Incident before = store.incident("breadcrumb", "before relay"); store.append(before);
            store.registerDestination("relay:fixture");
            assertTrue(store.snapshot("relay:fixture", 16).isEmpty());
            Incident both = store.incident("breadcrumb", "both"); store.append(both);
            store.acknowledge("local", Arrays.asList(before.eventId, both.eventId));
            assertTrue(store.snapshot("local", 16).isEmpty());
            assertEquals(both.eventId, store.snapshot("relay:fixture", 16).get(0).eventId);
        }
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            assertEquals(1, store.snapshot("relay:fixture", 16).size());
            store.acknowledge("relay:fixture", Collections.singleton(store.snapshot("relay:fixture", 1).get(0).eventId));
            assertTrue(store.snapshot("relay:fixture", 16).isEmpty());
        }
    }

    @Test void queuedEventsCannotEnrollInALaterConsentGeneration() throws Exception {
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            DiskJournal.Pending beforeRegistration = store.prepare(store.incident("breadcrumb", "before register"));
            store.registerDestination("relay:fixture");
            store.append(beforeRegistration);
            assertTrue(store.snapshot("relay:fixture", 16).isEmpty());
            DiskJournal.Pending beforeRevoke = store.prepare(store.incident("breadcrumb", "queued before revoke"));
            store.unregisterDestination("relay:fixture");
            DiskJournal.Pending whileRevoked = store.prepare(store.incident("breadcrumb", "queued while revoked"));
            store.registerDestination("relay:fixture");
            store.append(beforeRevoke);
            store.append(whileRevoked);
            assertTrue(store.snapshot("relay:fixture", 16).isEmpty());
            store.append(store.incident("breadcrumb", "after regrant"));
            assertEquals(1, store.snapshot("relay:fixture", 16).size());
            assertEquals(4, store.snapshot("local", 16).size());
        }
    }

    @Test void destinationRevokeClearsOnlyItsPendingAndRegrantStartsFresh() throws Exception {
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            store.registerDestination("relay:fixture");
            Incident old = store.incident("breadcrumb", "old"); store.append(old);
            store.appendFatal(store.incident("fatal_java", "crash"));
            store.unregisterDestination("relay:fixture");
            assertEquals(2, store.snapshot("local", 16).size());
            assertThrows(IOException.class, () -> store.snapshot("relay:fixture", 16));
            store.append(store.incident("breadcrumb", "revoked period"));
            store.registerDestination("relay:fixture");
            assertTrue(store.snapshot("relay:fixture", 16).isEmpty());
            store.append(store.incident("breadcrumb", "new consent"));
            assertEquals(1, store.snapshot("relay:fixture", 16).size());
        }
    }

    @Test void updateRetainsOriginalBuildSessionAndPairingGrant() throws Exception {
        Incident old;
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            old = store.incident("breadcrumb", "old build"); store.append(old);
        }
        Config next = new Config.Builder().enabled(true).consentGranted(true).buildId("v2").sourceRevision("revision-v2")
            .ideComponent("dev.ide.android", "dev.ide.android.IncidentSinkService")
            .pairingNonce("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff").build();
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", next)) {
            Incident fresh = store.incident("breadcrumb", "new build"); store.append(fresh);
            assertNotEquals(old.sessionId, fresh.sessionId);
            assertEquals("v1", store.snapshot("local", 16).get(0).buildId);
            assertEquals(config("v1").pairingNonce, store.grant(old.sessionId).pairingNonce);
            assertEquals(next.pairingNonce, store.grant(fresh.sessionId).pairingNonce);
            store.acknowledge("local", Collections.singleton(old.eventId));
            assertNull(store.grant(old.sessionId));
        }
    }

    @Test void processLocksRejectConcurrentOwnerButOtherProcessIsIndependent() throws Exception {
        try (DiskJournal first = new DiskJournal(directory(), "sample.app", config("v1"));
             DiskJournal other = new DiskJournal(new File(temp, "otherProcess"), "sample.app", config("v1"))) {
            assertThrows(IOException.class, () -> new DiskJournal(directory(), "sample.app", config("v1")));
            first.append(first.incident("breadcrumb", "one"));
            assertTrue(other.snapshot("local", 16).isEmpty());
            assertNotEquals(first.current.sessionId, other.current.sessionId);
        }
    }

    @Test void persistedBytesAndPublicCodecHaveNoPayloadCredentialsOrPairingGrant() throws Exception {
        try (DiskJournal store = new DiskJournal(directory(), "sample.app", config("v1"))) {
            store.append(store.incident("breadcrumb", "url https://alice:secret@host/path?token=abc email person@example.com\nAuthorization: Bearer top-secret\npassword=hunter2"));
            String disk = new String(Files.readAllBytes(new File(directory(), "journal.bin").toPath()), Redactor.UTF8);
            assertFalse(disk.contains("hunter2")); assertFalse(disk.contains("top-secret")); assertFalse(disk.contains("alice:secret"));
            assertFalse(disk.contains("person@example.com"));
            String exported = IncidentProtocol.encodeEvents(store.snapshot("local", 16));
            assertFalse(exported.contains(config("v1").pairingNonce)); assertFalse(exported.contains("ideService"));
            assertFalse(exported.contains("hunter2"));
        }
    }
}
