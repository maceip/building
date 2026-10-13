package dev.ide.reliability.fixture;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ide.applog.backup.BackupKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Two logical databases are a local correctness test, explicitly NOT cross-device backup evidence. */
@RunWith(AndroidJUnit4.class)
public final class BackupAcceptanceTest {
    // Synthetic fixtures only; never a user secret or a device-bound Android Keystore key.
    private static final char[] FIXTURE_PASSPHRASE = "disposable fixture transfer phrase".toCharArray();
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Path directory() throws Exception {
        return Files.createDirectories(context().getFilesDir().toPath().resolve("fixture-transfer"));
    }
    @Test public void twoLogicalDatasetsRestoreRecordsAttachmentsSettingsAndRollback() throws Exception {
        Path directory = directory(), archive = directory.resolve("logical.backup");
        try (FixtureStore source = new FixtureStore(context(), "backup-source.sqlite");
             FixtureStore target = new FixtureStore(context(), "backup-target.sqlite");
             BackupKey key = BackupKey.passphrase(FIXTURE_PASSPHRASE)) {
            source.seed(); target.seedDifferentDestination();
            new FixtureBackup().export(source, archive, key);
            byte[] encrypted = Files.readAllBytes(archive);
            assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains(FixtureStore.SAVED_BODY));
            try (BackupKey wrong = BackupKey.passphrase("wrong disposable transfer phrase".toCharArray())) {
                expectRestoreFailure(target, archive, directory, wrong, false);
            }
            assertDestinationUnchanged(target);
            Path corrupt = directory.resolve("corrupt.backup");
            byte[] tampered = encrypted.clone(); tampered[tampered.length - 1] ^= 1; Files.write(corrupt, tampered);
            expectRestoreFailure(target, corrupt, directory, key, false);
            assertDestinationUnchanged(target);
            expectRestoreFailure(target, archive, directory, key, true);
            assertDestinationUnchanged(target);
            new FixtureBackup().restore(target, archive, directory, key, false);
            assertTrue(target.hasSeedUserData());
            assertTrue(source.hasSeedUserData());
            assertArrayEquals(encrypted, Files.readAllBytes(archive));
        }
    }

    /** Called ONLY by the external harness on the source physical device. */
    @Test public void exportForPhysicalTransfer() throws Exception {
        requirePhysicalTransferGate("export");
        try (FixtureStore source = new FixtureStore(context(), "physical-transfer.sqlite");
             BackupKey key = BackupKey.passphrase(FIXTURE_PASSPHRASE)) {
            source.seed(); new FixtureBackup().export(source, directory().resolve("physical.backup"), key);
            assertTrue(Files.size(directory().resolve("physical.backup")) > 64);
        }
    }
    /** Called ONLY after the outside harness copied the source archive to a DISTINCT real device. */
    @Test public void importFromPhysicalTransfer() throws Exception {
        requirePhysicalTransferGate("import");
        Path archive = directory().resolve("physical.backup");
        assertTrue("Outside two-device transport did not provide archive", Files.isRegularFile(archive));
        try (FixtureStore target = new FixtureStore(context(), "physical-transfer.sqlite");
             BackupKey key = BackupKey.passphrase(FIXTURE_PASSPHRASE)) {
            target.seedDifferentDestination();
            new FixtureBackup().restore(target, archive, directory(), key, false);
            assertTrue(target.hasSeedUserData());
        }
    }
    private static void assertDestinationUnchanged(FixtureStore target) {
        assertEquals(1, target.recordCount());
        assertNull(target.recordBody(1));
        assertEquals("Existing destination record", target.recordBody(99));
        assertEquals("destination-only", target.setting("sort"));
        assertEquals("0.85", target.setting("fontScale"));
        assertArrayEquals("existing destination attachment".getBytes(StandardCharsets.UTF_8), target.attachment("notes.txt"));
    }

    private static void requirePhysicalTransferGate(String step) {
        String actual = InstrumentationRegistry.getArguments().getString("physicalTransfer");
        org.junit.Assume.assumeTrue("BLOCKED: outside installer must authorize distinct physical devices", step.equals(actual));
    }
    private void expectRestoreFailure(FixtureStore target, Path archive, Path staging, BackupKey key, boolean interrupt)
            throws Exception {
        try { new FixtureBackup().restore(target, archive, staging, key, interrupt); fail("Invalid restore must fail"); }
        catch (java.io.IOException | java.security.GeneralSecurityException expected) { /* target invariant checked by caller */ }
    }
}
