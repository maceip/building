package dev.ide.reliability.fixture;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.ComponentName;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ide.applog.backup.BackupKey;
import dev.ide.applog.backup.PortableBackup;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real SQLite/Activity regressions. In-memory document transport is NOT a two-device or SAF UI claim. */
@RunWith(AndroidJUnit4.class)
public final class BackupDocumentTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Path staging() throws Exception { return Files.createTempDirectory(context().getCacheDir().toPath(), "document-test-"); }
    private BackupKey key() { return BackupKey.passphrase("disposable document test phrase".toCharArray()); }

    @Test public void documentRoundTripReplacesOnlySelectedDataAndPreservesDraftAndSecrets() throws Exception {
        Path staging = staging();
        try (FixtureStore source = new FixtureStore(context(), "document-source.sqlite");
             FixtureStore target = new FixtureStore(context(), "document-target.sqlite");
             BackupKey key = key()) {
            source.seed();
            source.putSetting("accountToken", "never-export-source-token");
            target.seedDifferentDestination();
            target.putSetting("accountToken", "keep-destination-token");
            target.saveDraft(new FixtureStore.Draft("Unselected destination draft", 2, 5, 99));
            ContentValues attachment = new ContentValues();
            attachment.put("name", "unselected.bin");
            attachment.put("content", "keep-private-attachment".getBytes(StandardCharsets.UTF_8));
            target.getWritableDatabase().insertWithOnConflict("attachments", null, attachment,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
            ByteArrayOutputStream document = new ByteArrayOutputStream();
            new FixtureBackup().exportDocument(source, () -> document, staging, key, PortableBackup.Cancellation.NONE);
            String ciphertext = new String(document.toByteArray(), StandardCharsets.ISO_8859_1);
            assertFalse(ciphertext.contains(FixtureStore.SAVED_BODY));
            assertFalse(ciphertext.contains("never-export-source-token"));
            FixtureBackup adapter = new FixtureBackup();
            adapter.restoreDocument(target, () -> new ByteArrayInputStream(document.toByteArray()), staging, key,
                    PortableBackup.Cancellation.NONE);
            assertTrue(adapter.restoreCommitted());
            assertTrue(target.hasSeedUserData());
            assertEquals("keep-destination-token", target.setting("accountToken"));
            assertArrayEquals("keep-private-attachment".getBytes(StandardCharsets.UTF_8), target.attachment("unselected.bin"));
            assertEquals("Unselected destination draft", target.draft().body);
            assertEquals(2, target.draft().start);
            assertEquals(5, target.draft().end);
            assertEquals(99, target.draft().selectedId);
            assertTrue(source.hasSeedUserData());
        } finally { assertEmptyAndDelete(staging); }
    }

    @Test public void emptyInstallationExportsARestorableSnapshotWithoutSeedingData() throws Exception {
        Path staging = staging();
        String sourceName = "empty-document-" + System.nanoTime() + ".sqlite";
        try (FixtureStore source = new FixtureStore(context(), sourceName);
             FixtureStore target = new FixtureStore(context(), "empty-document-target.sqlite");
             BackupKey key = key()) {
            assertEquals(0, source.recordCount());
            target.seedDifferentDestination();
            ByteArrayOutputStream document = new ByteArrayOutputStream();
            new FixtureBackup().exportDocument(source, () -> document, staging, key, PortableBackup.Cancellation.NONE);
            new FixtureBackup().restoreDocument(target, () -> new ByteArrayInputStream(document.toByteArray()), staging, key,
                    PortableBackup.Cancellation.NONE);
            assertEquals(0, target.recordCount());
            assertEquals("title", target.setting("sort"));
            assertEquals("1.0", target.setting("fontScale"));
            assertArrayEquals(new byte[0], target.attachment("notes.txt"));
        } finally { context().deleteDatabase(sourceName); assertEmptyAndDelete(staging); }
    }

    @Test public void documentCloseFailureWrongKeyAndCancellationPreserveExistingDatabase() throws Exception {
        Path staging = staging();
        try (FixtureStore source = new FixtureStore(context(), "bad-document-source.sqlite");
             FixtureStore target = new FixtureStore(context(), "bad-document-target.sqlite");
             BackupKey key = key()) {
            source.seed();
            target.seedDifferentDestination();
            ByteArrayOutputStream document = new ByteArrayOutputStream();
            new FixtureBackup().exportDocument(source, () -> document, staging, key, PortableBackup.Cancellation.NONE);
            FixtureBackup adapter = new FixtureBackup();
            try {
                adapter.restoreDocument(target, () -> new ByteArrayInputStream(document.toByteArray()) {
                    @Override public void close() throws IOException { throw new IOException("Document close failed"); }
                }, staging, key, PortableBackup.Cancellation.NONE);
                fail("Provider close failure must precede import");
            } catch (IOException expected) { assertFalse(adapter.restoreCommitted()); }
            try (BackupKey wrong = BackupKey.passphrase("wrong disposable document phrase".toCharArray())) {
                try {
                    adapter.restoreDocument(target, () -> new ByteArrayInputStream(document.toByteArray()), staging, wrong,
                            PortableBackup.Cancellation.NONE);
                    fail("Wrong key must not restore");
                } catch (IOException expected) { assertFalse(adapter.restoreCommitted()); }
            }
            try {
                adapter.restoreDocument(target, () -> new ByteArrayInputStream(document.toByteArray()), staging, key, () -> true);
                fail("Cancelled transfer must not restore");
            } catch (java.io.InterruptedIOException expected) { assertFalse(adapter.restoreCommitted()); }
            assertEquals(1, target.recordCount());
            assertEquals("Existing destination record", target.recordBody(99));
            assertEquals("destination-only", target.setting("sort"));
            assertEquals("0.85", target.setting("fontScale"));
            assertArrayEquals("existing destination attachment".getBytes(StandardCharsets.UTF_8), target.attachment("notes.txt"));
        } finally { assertEmptyAndDelete(staging); }
    }

    @Test public void backupScreenIsPrivateAndNeverRestoresSecretOrConsentViewState() throws Exception {
        ActivityInfo info = context().getPackageManager().getActivityInfo(
                new ComponentName(context(), FixtureBackupActivity.class), 0);
        assertFalse("Other apps must not remotely trigger backup/import", info.exported);
        try (ActivityScenario<FixtureBackupActivity> scenario = ActivityScenario.launch(FixtureBackupActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                EditText passphrase = activity.findViewById(FixtureBackupActivity.PASSPHRASE_ID);
                EditText confirmation = activity.findViewById(FixtureBackupActivity.CONFIRM_ID);
                assertFalse(passphrase.isSaveEnabled());
                assertFalse(confirmation.isSaveEnabled());
                assertFalse(activity.findViewById(FixtureBackupActivity.CONSENT_ID).isSaveEnabled());
                assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, passphrase.getImportantForAutofill());
                passphrase.setText("never-persist-this-passphrase");
                confirmation.setText("never-persist-this-passphrase");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("", ((EditText) activity.findViewById(FixtureBackupActivity.PASSPHRASE_ID)).getText().toString());
                assertEquals("", ((EditText) activity.findViewById(FixtureBackupActivity.CONFIRM_ID)).getText().toString());
                assertFalse(((android.widget.CheckBox) activity.findViewById(FixtureBackupActivity.CONSENT_ID)).isChecked());
            });
        }
    }

    private static void assertEmptyAndDelete(Path directory) throws Exception {
        try (java.util.stream.Stream<Path> children = Files.list(directory)) { assertEquals(0, children.count()); }
        Files.delete(directory);
    }
}
