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
    private final PortableBackup backup = new PortableBackup();

    public void export(final FixtureStore source, Path destination, BackupKey key) throws Exception {
        final Map<String, byte[]> snapshot = new LinkedHashMap<String, byte[]>();
        backup.exportArchive(destination, MANIFEST, key, work -> {
            synchronized (source) {
                source.getReadableDatabase().beginTransaction();
                try {
                    snapshot.putAll(source.selectedUserData());
                    work.run();
                    source.getReadableDatabase().setTransactionSuccessful();
                } finally { source.getReadableDatabase().endTransaction(); snapshot.clear(); }
            }
        }, entry -> new ByteArrayInputStream(snapshot.get(entry.name())), PortableBackup.Cancellation.NONE);
    }

    public void restore(final FixtureStore target, Path archive, Path staging, BackupKey key,
                        final boolean failDuringCommit) throws Exception {
        backup.restoreArchive(archive, staging, MANIFEST, key, (source, destination) -> new PortableBackup.Transaction() {
            private final Map<String, byte[]> staged = new LinkedHashMap<String, byte[]>();
            @Override public void stage(PortableBackup.VerifiedBackup verified, PortableBackup.Cancellation cancellation)
                    throws Exception {
                for (BackupManifest.Entry entry : MANIFEST.entries()) {
                    if (cancellation.isCancelled()) throw new java.io.InterruptedIOException("Cancelled fixture restore");
                    try (InputStream input = verified.openEntry(entry.name())) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024];
                        for (int n; (n = input.read(buffer)) != -1;) {
                            if (bytes.size() + n > 65536) throw new java.io.IOException("Fixture data exceeds domain limit");
                            bytes.write(buffer, 0, n);
                        }
                        staged.put(entry.name(), bytes.toByteArray());
                    }
                }
            }
            @Override public void commit() throws Exception { target.replaceSelectedUserData(staged, failDuringCommit); }
            @Override public void rollback() { staged.clear(); } // SQLite transaction already rolled back on exception.
            @Override public void close() { staged.clear(); }
        }, PortableBackup.Cancellation.NONE);
    }
}
