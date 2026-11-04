package dev.ide.applog.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import static org.junit.jupiter.api.Assertions.*;

/** Logical two-installation fixtures, NOT evidence of a real two-device/Android acceptance run. */
class PortableBackupTest {
    @TempDir Path temp;
    private final PortableBackup backup = new PortableBackup(new PortableBackup.Limits(8, 1024, 2048, 4096, 8192, 100000));
    private static final char[] PASSPHRASE = "separate user passphrase for fixture".toCharArray();

    @Test void portablePassphraseRestoresSelectedRecordsPreferencesAndAttachmentsAcrossFreshInstallations() throws Exception {
        final Map<String, byte[]> source = selectedData();
        BackupManifest manifest = manifest(1);
        Path archive = temp.resolve("portable.bkp");
        final AtomicBoolean snapshotHeld = new AtomicBoolean();
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            backup.exportArchive(archive, manifest, key, work -> {
                synchronized (source) { snapshotHeld.set(true); try { work.run(); } finally { snapshotHeld.set(false); } }
            }, entry -> {
                assertTrue(snapshotHeld.get(), "all reads must be inside the app-owned snapshot");
                assertNotEquals("credentials.json", entry.name());
                return new ByteArrayInputStream(source.get(entry.name()));
            }, PortableBackup.Cancellation.NONE);
        }
        byte[] ciphertext = Files.readAllBytes(archive);
        assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains("personal note"));
        assertFalse(new String(ciphertext, StandardCharsets.ISO_8859_1).contains("records/notes.json"));
        AppTransaction destination = new AppTransaction(Collections.singletonMap("previous", bytes("old")));
        // Fresh SDK/key/transaction objects emulate a second install; no AndroidKeyStore/device state is shared.
        try (BackupKey independentKey = BackupKey.passphrase(PASSPHRASE)) {
            backup.restoreArchive(archive, temp, manifest, independentKey, destination, PortableBackup.Cancellation.NONE);
        }
        assertEquals(1, destination.begins);
        assertEquals(1, destination.commits);
        assertFalse(destination.live.containsKey("previous"));
        for (Map.Entry<String, byte[]> entry : source.entrySet()) assertArrayEquals(entry.getValue(), destination.live.get(entry.getKey()));
        assertStagingRemoved();
    }

    @Test void recoveryCodeIsPortableAndRandomizedArchivesDoNotReuseSaltOrNonce() throws Exception {
        String recovery = BackupKey.newRecoveryCode(new SecureRandom());
        Path first = temp.resolve("a.bkp"), second = temp.resolve("b.bkp");
        try (BackupKey key = BackupKey.recoveryCode(recovery)) {
            export(first, key, selectedData()); export(second, key, selectedData());
        }
        assertFalse(Arrays.equals(Files.readAllBytes(first), Files.readAllBytes(second)));
        AppTransaction destination = new AppTransaction(Collections.<String, byte[]>emptyMap());
        try (BackupKey otherInstallation = BackupKey.recoveryCode(recovery)) {
            backup.restoreArchive(first, temp, manifest(1), otherInstallation, destination, PortableBackup.Cancellation.NONE);
        }
        assertEquals(3, destination.live.size());
        assertThrows(IllegalArgumentException.class, () -> BackupKey.recoveryCode("invalid"));
        BackupKey cleared = BackupKey.passphrase(PASSPHRASE); cleared.close();
        assertEquals("BackupKey[redacted]", cleared.toString());
        assertThrows(IllegalStateException.class, cleared::mode);
    }

    @Test void wrongKeyCorruptionAndTruncationNeverEvenBeginImporterAndPreserveDestination() throws Exception {
        Path original = temp.resolve("valid.bkp");
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) { export(original, key, selectedData()); }
        AppTransaction destination = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        try (BackupKey wrong = BackupKey.passphrase("a wholly different wrong passphrase".toCharArray())) {
            assertThrows(IOException.class, () -> backup.restoreArchive(original, temp, manifest(1), wrong, destination, PortableBackup.Cancellation.NONE));
        }
        byte[] data = Files.readAllBytes(original);
        data[data.length - 20] ^= 1;
        Path corrupt = temp.resolve("corrupt.bkp"); Files.write(corrupt, data);
        Path truncated = temp.resolve("truncated.bkp"); Files.write(truncated, Arrays.copyOf(data, data.length - 8));
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(IOException.class, () -> backup.restoreArchive(corrupt, temp, manifest(1), key, destination, PortableBackup.Cancellation.NONE));
            assertThrows(IOException.class, () -> backup.restoreArchive(truncated, temp, manifest(1), key, destination, PortableBackup.Cancellation.NONE));
        }
        assertEquals(0, destination.begins);
        assertArrayEquals(bytes("keep"), destination.live.get("previous"));
        assertStagingRemoved();
    }

    @Test void schemaMigrationIsStagedAndFailureCancellationAndCommitFailureRollback() throws Exception {
        Path archive = temp.resolve("old-schema.bkp");
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) { export(archive, key, selectedData()); }
        AppTransaction migrated = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            backup.restoreArchive(archive, temp, manifest(2), key, migrated, PortableBackup.Cancellation.NONE);
        }
        assertEquals(1, migrated.sourceVersion); assertEquals(2, migrated.targetVersion);
        assertArrayEquals(bytes("migrated personal note"), migrated.live.get("records/notes.json"));
        for (String failure : new String[] { "migration", "cancel", "commit" }) {
            final AppTransaction destination = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
            destination.failure = failure;
            AtomicBoolean cancelled = destination.cancelled;
            try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
                assertThrows(Exception.class, () -> backup.restoreArchive(archive, temp, manifest(2), key, destination, cancelled::get));
            }
            assertEquals(1, destination.rollbacks);
            assertEquals(0, destination.commits);
            assertArrayEquals(bytes("keep"), destination.live.get("previous"));
        }
        assertStagingRemoved();
    }

    @Test void interruptedExportAndOverLimitAttachmentDoNotReplacePreviousArchive() throws Exception {
        Path archive = temp.resolve("existing.bkp"); byte[] previous = bytes("previous destination remains"); Files.write(archive, previous);
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(InterruptedIOException.class, () -> backup.exportArchive(archive, manifest(1), key,
                    PortableBackup.SnapshotWork::run, entry -> new ByteArrayInputStream(new byte[16]), () -> true));
            assertArrayEquals(previous, Files.readAllBytes(archive));
            Map<String, byte[]> oversized = selectedData(); oversized.put("attachments/picture.bin", new byte[2049]);
            assertThrows(IOException.class, () -> export(archive, key, oversized));
            assertArrayEquals(previous, Files.readAllBytes(archive));
            assertThrows(IOException.class, () -> backup.exportArchive(archive, manifest(1), key,
                    work -> { }, entry -> new ByteArrayInputStream(new byte[0]), PortableBackup.Cancellation.NONE));
            assertArrayEquals(previous, Files.readAllBytes(archive));
        }
        assertStagingRemoved();
    }

    @Test void manifestRequiresExplicitSafeTypedWhitelistAndRejectsCredentialsAndWholeDirectories() {
        for (String name : new String[] { "../notes", "/notes", "a/../b", "a//b", "a\\b", ".hidden", "notes\u0000",
                "credentials.json", "auth/session.json", "signing.jks", "keys/private.pem", "tokens.json" }) {
            assertThrows(IllegalArgumentException.class, () -> new BackupManifest.Entry(name, BackupManifest.Type.USER_RECORDS), name);
        }
        assertThrows(IllegalArgumentException.class, () -> new BackupManifest("dev.test", "notes", 1, Collections.emptyList()));
        BackupManifest.Entry entry = new BackupManifest.Entry("records.json", BackupManifest.Type.USER_RECORDS);
        assertThrows(IllegalArgumentException.class, () -> new BackupManifest("dev.test", "notes", 1, Arrays.asList(entry, entry)));
    }

    @Test void unknownApplicationSelectionOrFutureVersionCannotEnterTransaction() throws Exception {
        Path archive = temp.resolve("valid.bkp");
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) { export(archive, key, selectedData()); }
        AppTransaction untouched = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        BackupManifest otherApp = new BackupManifest("dev.other", "notes", 1, manifest(1).entries());
        BackupManifest wrongSelection = new BackupManifest("dev.test", "notes", 1, Collections.singletonList(
                new BackupManifest.Entry("records/notes.json", BackupManifest.Type.USER_PREFERENCES)));
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(IOException.class, () -> backup.restoreArchive(archive, temp, otherApp, key, untouched, PortableBackup.Cancellation.NONE));
            assertThrows(IOException.class, () -> backup.restoreArchive(archive, temp, wrongSelection, key, untouched, PortableBackup.Cancellation.NONE));
        }
        assertEquals(0, untouched.begins);
    }

    @Test void authenticatedMaliciousArchiveStillCannotEscapeSelectionPathsCapsOrDigestChecks() throws Exception {
        byte[] recovery = new byte[32]; new SecureRandom().nextBytes(recovery);
        AppTransaction untouched = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        try (BackupKey key = BackupKey.recoveryKey(recovery)) {
            for (String kind : new String[] { "path", "count", "chunk", "hash", "trailer" }) {
                Path archive = temp.resolve("malicious-" + kind + ".bkp");
                Files.write(archive, encryptMalformed(key, kind));
                assertThrows(IOException.class, () -> backup.restoreArchive(archive, temp, manifest(1), key, untouched, PortableBackup.Cancellation.NONE), kind);
            }
        }
        assertEquals(0, untouched.begins); assertStagingRemoved();
        assertFalse(Files.exists(temp.resolve("escaped")));
    }

    @Test void streamedAttachmentsUseBoundedAuthenticatedRecordsAndRejectReorderOrMissingFinalRecord() throws Exception {
        PortableBackup bounded = new PortableBackup(new PortableBackup.Limits(8, 1024, 200000, 220000, 250000, 100000));
        Map<String, byte[]> data = selectedData(); byte[] attachment = new byte[120000]; new SecureRandom().nextBytes(attachment);
        data.put("attachments/picture.bin", attachment);
        Path archive = temp.resolve("streamed.bkp"); byte[] recovery = new byte[32]; new SecureRandom().nextBytes(recovery);
        try (BackupKey key = BackupKey.recoveryKey(recovery)) {
            bounded.exportArchive(archive, manifest(1), key, PortableBackup.SnapshotWork::run,
                    entry -> new ByteArrayInputStream(data.get(entry.name())), PortableBackup.Cancellation.NONE);
            byte[] bytes = Files.readAllBytes(archive);
            java.nio.ByteBuffer frame = java.nio.ByteBuffer.wrap(bytes); frame.position(41);
            int records = 0, end = 0;
            while (frame.hasRemaining()) {
                int start = frame.position(), length = frame.getInt(); assertTrue(length >= 0 && length <= 32768);
                frame.position(frame.position() + length + 16); records++;
                if (length == 0) end = start;
            }
            assertTrue(records >= 5); assertEquals(bytes.length - 20, end);
            AppTransaction restored = new AppTransaction(Collections.<String, byte[]>emptyMap());
            bounded.restoreArchive(archive, temp, manifest(1), key, restored, PortableBackup.Cancellation.NONE);
            assertArrayEquals(attachment, restored.live.get("attachments/picture.bin"));
            Path truncated = temp.resolve("missing-final.bkp"); Files.write(truncated, Arrays.copyOf(bytes, end));
            AppTransaction untouched = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
            assertThrows(IOException.class, () -> bounded.restoreArchive(truncated, temp, manifest(1), key, untouched, PortableBackup.Cancellation.NONE));
            int recordBytes = 4 + 32768 + 16;
            byte[] reordered = bytes.clone();
            System.arraycopy(bytes, 41 + recordBytes, reordered, 41, recordBytes);
            System.arraycopy(bytes, 41, reordered, 41 + recordBytes, recordBytes);
            Path swapped = temp.resolve("swapped.bkp"); Files.write(swapped, reordered);
            assertThrows(IOException.class, () -> bounded.restoreArchive(swapped, temp, manifest(1), key, untouched, PortableBackup.Cancellation.NONE));
            assertEquals(0, untouched.begins);
        }
        assertStagingRemoved();
    }

    @Test void documentExportClosesStreamAndReleasesSnapshotBeforeOpeningProvider() throws Exception {
        Path staging = Files.createDirectory(temp.resolve("documents"));
        Map<String, byte[]> data = selectedData();
        AtomicBoolean snapshotHeld = new AtomicBoolean(), closed = new AtomicBoolean();
        ByteArrayOutputStream document = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
        };
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            backup.exportArchive(() -> {
                assertFalse(snapshotHeld.get(), "slow document I/O must not hold the application snapshot");
                return document;
            }, staging, manifest(1), key, work -> {
                snapshotHeld.set(true);
                try { work.run(); } finally { snapshotHeld.set(false); }
            }, entry -> {
                assertTrue(snapshotHeld.get());
                return new ByteArrayInputStream(data.get(entry.name()));
            }, PortableBackup.Cancellation.NONE);
        }
        assertTrue(closed.get());
        byte[] archive = document.toByteArray();
        assertFalse(new String(archive, StandardCharsets.ISO_8859_1).contains("personal note"));
        AtomicBoolean inputClosed = new AtomicBoolean();
        AppTransaction destination = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            backup.restoreArchive(() -> new ByteArrayInputStream(archive) {
                @Override public void close() { inputClosed.set(true); }
            }, staging, manifest(1), key, (source, target) -> {
                assertTrue(inputClosed.get(), "provider close errors must occur before importer callbacks");
                return destination.begin(source, target);
            }, PortableBackup.Cancellation.NONE);
        }
        assertEquals(1, destination.commits);
        for (Map.Entry<String, byte[]> entry : data.entrySet())
            assertArrayEquals(entry.getValue(), destination.live.get(entry.getKey()));
        assertDirectoryEmpty(staging);
    }

    @Test void failedOrCancelledPrivateExportNeverOpensDocumentDestination() throws Exception {
        Path staging = Files.createDirectory(temp.resolve("documents"));
        AtomicBoolean opened = new AtomicBoolean();
        PortableBackup.ArchiveDestination provider = () -> { opened.set(true); return new ByteArrayOutputStream(); };
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(IOException.class, () -> backup.exportArchive(provider, staging, manifest(1), key,
                    PortableBackup.SnapshotWork::run, entry -> new ByteArrayInputStream(new byte[8193]),
                    PortableBackup.Cancellation.NONE));
            assertFalse(opened.get());
            assertThrows(InterruptedIOException.class, () -> backup.exportArchive(provider, staging, manifest(1), key,
                    PortableBackup.SnapshotWork::run, entry -> new ByteArrayInputStream(new byte[0]), () -> true));
            assertFalse(opened.get());
        }
        assertDirectoryEmpty(staging);
    }

    @Test void destinationWriteCloseAndOpenFailuresCleanPrivateArchive() throws Exception {
        Path staging = Files.createDirectory(temp.resolve("documents"));
        Map<String, byte[]> data = selectedData();
        for (String failure : new String[] { "open", "null", "write", "close" }) {
            AtomicBoolean closed = new AtomicBoolean();
            PortableBackup.ArchiveDestination provider = () -> {
                if (failure.equals("open")) throw new IOException("provider open failed");
                if (failure.equals("null")) return null;
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        if (failure.equals("write")) throw new IOException("provider write failed");
                    }
                    @Override public void close() throws IOException {
                        closed.set(true);
                        if (failure.equals("close")) throw new IOException("provider close failed");
                    }
                };
            };
            try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
                assertThrows(IOException.class, () -> backup.exportArchive(provider, staging, manifest(1), key,
                        PortableBackup.SnapshotWork::run, entry -> new ByteArrayInputStream(data.get(entry.name())),
                        PortableBackup.Cancellation.NONE), failure);
            }
            assertEquals(failure.equals("write") || failure.equals("close"), closed.get());
            assertDirectoryEmpty(staging);
        }
    }

    @Test void providerStreamLimitReadAndCloseFailuresNeverEnterImporter() throws Exception {
        Path archive = temp.resolve("valid.bkp"), staging = Files.createDirectory(temp.resolve("documents"));
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) { export(archive, key, selectedData()); }
        byte[] valid = Files.readAllBytes(archive);
        for (String failure : new String[] { "open", "null", "read", "close", "limit" }) {
            AtomicBoolean closed = new AtomicBoolean();
            AppTransaction untouched = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
            PortableBackup.ArchiveSource provider = () -> {
                if (failure.equals("open")) throw new IOException("provider open failed");
                if (failure.equals("null")) return null;
                InputStream stream = new ByteArrayInputStream(failure.equals("limit") ? new byte[8193] : valid);
                return new java.io.FilterInputStream(stream) {
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        if (failure.equals("read")) throw new IOException("provider read failed");
                        return super.read(b, off, len);
                    }
                    @Override public void close() throws IOException {
                        closed.set(true);
                        super.close();
                        if (failure.equals("close")) throw new IOException("provider close failed");
                    }
                };
            };
            try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
                assertThrows(IOException.class, () -> backup.restoreArchive(provider, staging, manifest(1), key,
                        untouched, PortableBackup.Cancellation.NONE), failure);
            }
            assertEquals(0, untouched.begins);
            assertEquals(!failure.equals("open") && !failure.equals("null"), closed.get());
            assertArrayEquals(bytes("keep"), untouched.live.get("previous"));
            assertDirectoryEmpty(staging);
        }
    }

    @Test void cancellationWhileReceivingDocumentClosesProviderWithoutImporterCallbacks() throws Exception {
        Path staging = Files.createDirectory(temp.resolve("documents"));
        AtomicBoolean cancelled = new AtomicBoolean(), closed = new AtomicBoolean();
        AppTransaction untouched = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        PortableBackup.ArchiveSource provider = () -> new ByteArrayInputStream(new byte[128]) {
            @Override public int read(byte[] b, int off, int length) {
                int count = super.read(b, off, length);
                cancelled.set(true);
                return count;
            }
            @Override public void close() { closed.set(true); }
        };
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(InterruptedIOException.class, () -> backup.restoreArchive(provider, staging, manifest(1), key,
                    untouched, cancelled::get));
        }
        assertTrue(closed.get());
        assertEquals(0, untouched.begins);
        assertDirectoryEmpty(staging);
    }

    @Test void documentTransferHandlesZeroProgressAndChecksCancellationAfterProviderWrite() throws Exception {
        Path archive = temp.resolve("valid.bkp"), staging = Files.createDirectory(temp.resolve("documents"));
        Map<String, byte[]> data = selectedData();
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) { export(archive, key, data); }
        AppTransaction destination = new AppTransaction(Collections.<String, byte[]>emptyMap());
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            backup.restoreArchive(() -> new ByteArrayInputStream(Files.readAllBytes(archive)) {
                @Override public int read(byte[] b, int off, int length) { return 0; }
            }, staging, manifest(1), key, destination, PortableBackup.Cancellation.NONE);
        }
        assertEquals(1, destination.commits);
        AtomicBoolean cancelled = new AtomicBoolean(), closed = new AtomicBoolean();
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(InterruptedIOException.class, () -> backup.exportArchive(() -> new ByteArrayOutputStream() {
                @Override public void write(byte[] b, int off, int length) {
                    super.write(b, off, length);
                    cancelled.set(true);
                }
                @Override public void close() { closed.set(true); }
            }, staging, manifest(1), key, PortableBackup.SnapshotWork::run,
                    entry -> new ByteArrayInputStream(data.get(entry.name())), cancelled::get));
        }
        assertTrue(closed.get());
        assertDirectoryEmpty(staging);
    }

    @Test void documentWrongKeyOrTamperingStillCannotEnterRestoreTransaction() throws Exception {
        Path archive = temp.resolve("valid.bkp"), staging = Files.createDirectory(temp.resolve("documents"));
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) { export(archive, key, selectedData()); }
        byte[] valid = Files.readAllBytes(archive);
        AppTransaction untouched = new AppTransaction(Collections.singletonMap("previous", bytes("keep")));
        try (BackupKey wrong = BackupKey.passphrase("a different document passphrase".toCharArray())) {
            assertThrows(IOException.class, () -> backup.restoreArchive(() -> new ByteArrayInputStream(valid), staging,
                    manifest(1), wrong, untouched, PortableBackup.Cancellation.NONE));
        }
        byte[] tampered = valid.clone();
        tampered[tampered.length - 1] ^= 1;
        try (BackupKey key = BackupKey.passphrase(PASSPHRASE)) {
            assertThrows(IOException.class, () -> backup.restoreArchive(() -> new ByteArrayInputStream(tampered), staging,
                    manifest(1), key, untouched, PortableBackup.Cancellation.NONE));
        }
        assertEquals(0, untouched.begins);
        assertArrayEquals(bytes("keep"), untouched.live.get("previous"));
        assertDirectoryEmpty(staging);
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(0, files.count()); }
    }

    private byte[] encryptMalformed(BackupKey key, String kind) throws Exception {
        ByteArrayOutputStream plain = new ByteArrayOutputStream(); DataOutputStream body = new DataOutputStream(plain);
        body.writeInt(0x424B5031); text(body, "dev.test"); text(body, "notes"); body.writeInt(1);
        body.writeInt(kind.equals("count") ? Integer.MAX_VALUE : 1);
        text(body, kind.equals("path") ? "../escaped" : "records/notes.json"); body.writeByte(2);
        body.writeInt(kind.equals("chunk") ? Integer.MAX_VALUE : 1); body.writeByte(42);
        body.writeInt(0); body.writeLong(1);
        body.write(kind.equals("hash") ? new byte[32] : java.security.MessageDigest.getInstance("SHA-256").digest(new byte[] { 42 }));
        body.writeInt(kind.equals("trailer") ? 0 : 0x454E4431);
        byte[] salt = new byte[16], nonce = new byte[12]; new SecureRandom().nextBytes(salt); new SecureRandom().nextBytes(nonce);
        ByteArrayOutputStream envelope = new ByteArrayOutputStream(); DataOutputStream header = new DataOutputStream(envelope);
        header.write(bytes("IDEBKP01")); header.writeByte(2); header.writeInt(0); header.write(salt); header.write(nonce);
        byte[] aad = envelope.toByteArray(); byte[] derived = key.derive(salt, 0);
        byte[] payload = plain.toByteArray();
        header.writeInt(payload.length);
        header.write(PortableBackup.recordCipher(Cipher.ENCRYPT_MODE, derived, nonce, aad, 0, payload.length).doFinal(payload));
        header.writeInt(0);
        header.write(PortableBackup.recordCipher(Cipher.ENCRYPT_MODE, derived, nonce, aad, 1, 0).doFinal(new byte[0]));
        Arrays.fill(derived, (byte) 0);
        return envelope.toByteArray();
    }
    private static void text(DataOutputStream output, String text) throws IOException { byte[] b = bytes(text); output.writeInt(b.length); output.write(b); }
    private void export(Path destination, BackupKey key, final Map<String, byte[]> source) throws Exception {
        backup.exportArchive(destination, manifest(1), key, PortableBackup.SnapshotWork::run,
                entry -> new ByteArrayInputStream(source.get(entry.name())), PortableBackup.Cancellation.NONE);
    }
    private static BackupManifest manifest(int version) {
        return new BackupManifest("dev.test", "notes", version, Arrays.asList(
                new BackupManifest.Entry("records/notes.json", BackupManifest.Type.USER_RECORDS),
                new BackupManifest.Entry("preferences/ui.json", BackupManifest.Type.USER_PREFERENCES),
                new BackupManifest.Entry("attachments/picture.bin", BackupManifest.Type.USER_ATTACHMENT)));
    }
    private static Map<String, byte[]> selectedData() {
        Map<String, byte[]> source = new LinkedHashMap<String, byte[]>();
        source.put("records/notes.json", bytes("personal note")); source.put("preferences/ui.json", bytes("dark mode"));
        source.put("attachments/picture.bin", new byte[] { 0, 1, 2, 3, -1 }); return source;
    }
    private void assertStagingRemoved() throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(temp)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".restore-") || p.getFileName().toString().startsWith(".backup-")));
        }
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static byte[] all(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[128]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); return out.toByteArray();
        }
    }
    /** Models a caller-owned database transaction with one atomic visible map swap, not a directory copy. */
    private static final class AppTransaction implements PortableBackup.Importer, PortableBackup.Transaction {
        Map<String, byte[]> live;
        Map<String, byte[]> staged;
        int begins, commits, rollbacks, sourceVersion, targetVersion;
        String failure = "";
        final AtomicBoolean cancelled = new AtomicBoolean();
        AppTransaction(Map<String, byte[]> previous) { live = new LinkedHashMap<String, byte[]>(previous); }
        @Override public PortableBackup.Transaction begin(BackupManifest source, BackupManifest target) {
            begins++; sourceVersion = source.schemaVersion(); targetVersion = target.schemaVersion(); return this;
        }
        @Override public void stage(PortableBackup.VerifiedBackup source, PortableBackup.Cancellation cancellation) throws Exception {
            staged = new LinkedHashMap<String, byte[]>();
            for (BackupManifest.Entry entry : source.manifest().entries()) {
                PortableBackup.check(cancellation); byte[] value = all(source.openEntry(entry.name()));
                assertEquals(value.length, source.length(entry.name())); staged.put(entry.name(), value);
            }
            if (targetVersion == 2) staged.put("records/notes.json", bytes("migrated personal note"));
            if (failure.equals("migration")) throw new IOException("fixture migration rejection");
            if (failure.equals("cancel")) cancelled.set(true);
        }
        @Override public void commit() throws Exception {
            if (failure.equals("commit")) throw new IOException("fixture transaction abort");
            synchronized (this) { live = staged; commits++; }
        }
        @Override public void rollback() { staged = null; rollbacks++; }
        @Override public void close() { staged = null; }
    }
}
