package dev.ide.reliability.fixture;

import dev.ide.applog.backup.BackupKey;
import dev.ide.applog.backup.BackupManifest;
import dev.ide.applog.backup.PortableBackup;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** App domain adapter only. Encryption, validation and archive format belong to the real SDK engine. */
public final class FixtureBackup {
    public static final BackupManifest MANIFEST = new BackupManifest("dev.ide.reliability.fixture", "user-notes", 1,
            Arrays.asList(new BackupManifest.Entry("records.json", BackupManifest.Type.USER_RECORDS),
                    new BackupManifest.Entry("preferences.json", BackupManifest.Type.USER_PREFERENCES),
                    new BackupManifest.Entry("attachments/notes.txt", BackupManifest.Type.USER_ATTACHMENT)));
    // The sample's JSON/attachment adapter is intentionally small; never export an archive it cannot restore.
    private final PortableBackup backup = new PortableBackup(new PortableBackup.Limits(
            3, 65536, 65536, 3L * 65536, 256L * 1024, 310000));
    private boolean restoreCommitted;

    /** One adapter per operation. Distinguishes a committed restore from a later staging-cleanup error. */
    public boolean restoreCommitted() { return restoreCommitted; }

    public void export(final FixtureStore source, Path destination, BackupKey key) throws Exception {
        final Map<String, byte[]> snapshot = new LinkedHashMap<String, byte[]>();
        backup.exportArchive(destination, MANIFEST, key, snapshot(source, snapshot),
                entry -> new ByteArrayInputStream(snapshot.get(entry.name())), PortableBackup.Cancellation.NONE);
    }

    /** Called only after a user chose a new document and confirmed their portable passphrase. */
    public void exportDocument(FixtureStore source, PortableBackup.ArchiveDestination destination, Path privateStaging,
                               BackupKey key, PortableBackup.Cancellation cancellation) throws Exception {
        final Map<String, byte[]> snapshot = new LinkedHashMap<String, byte[]>();
        backup.exportArchive(destination, privateStaging, MANIFEST, key, snapshot(source, snapshot),
                entry -> new ByteArrayInputStream(snapshot.get(entry.name())), cancellation);
    }

    private PortableBackup.Snapshot snapshot(final FixtureStore source, final Map<String, byte[]> snapshot) {
        return work -> {
            synchronized (source) {
                source.getReadableDatabase().beginTransaction();
                try {
                    snapshot.putAll(source.selectedUserData());
                    work.run();
                    source.getReadableDatabase().setTransactionSuccessful();
                } finally { source.getReadableDatabase().endTransaction(); clear(snapshot); }
            }
        };
    }

    public void restore(final FixtureStore target, Path archive, Path staging, BackupKey key,
                        final boolean failDuringCommit) throws Exception {
        backup.restoreArchive(archive, staging, MANIFEST, key, importer(target, failDuringCommit),
                PortableBackup.Cancellation.NONE);
    }

    /** The Activity obtains explicit replacement consent before entering this method. */
    public void restoreDocument(FixtureStore target, PortableBackup.ArchiveSource source, Path privateStaging,
                                BackupKey key, PortableBackup.Cancellation cancellation) throws Exception {
        backup.restoreArchive(source, privateStaging, MANIFEST, key, importer(target, false), cancellation);
    }

    private PortableBackup.Importer importer(final FixtureStore target, final boolean failDuringCommit) {
        restoreCommitted = false;
        return (source, destination) -> new PortableBackup.Transaction() {
            private final Map<String, byte[]> staged = new LinkedHashMap<String, byte[]>();
            @Override public void stage(PortableBackup.VerifiedBackup verified, PortableBackup.Cancellation cancellation)
                    throws Exception {
                for (BackupManifest.Entry entry : MANIFEST.entries()) {
                    PortableBackup.check(cancellation);
                    try (InputStream input = verified.openEntry(entry.name())) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        try {
                            for (int n; (n = input.read(buffer)) != -1;) {
                                PortableBackup.check(cancellation);
                                if (bytes.size() + n > 65536) throw new java.io.IOException("Fixture data exceeds domain limit");
                                bytes.write(buffer, 0, n);
                            }
                            staged.put(entry.name(), bytes.toByteArray());
                        } finally { Arrays.fill(buffer, (byte) 0); }
                    }
                }
            }
            @Override public void commit() throws Exception {
                target.replaceSelectedUserData(staged, failDuringCommit);
                restoreCommitted = true;
            }
            @Override public void rollback() { clear(staged); } // SQLite transaction already rolled back on exception.
            @Override public void close() { clear(staged); }
        };
    }

    private static void clear(Map<String, byte[]> data) {
        for (byte[] value : data.values()) Arrays.fill(value, (byte) 0);
        data.clear();
    }
}
