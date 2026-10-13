package dev.ide.reliability.fixture;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The outside installer runs seed on one installed APK, replaces it WITHOUT clearing data, then verifies. */
@RunWith(AndroidJUnit4.class)
public final class MigrationAcceptanceTest {
    private FixtureStore store() {
        return ((FixtureApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext()).store();
    }
    @Test public void seedBeforePackageReplacement() {
        store().seed(); store().saveDraft(new FixtureStore.Draft("Migration unsaved draft", 3, 9, 2));
        store().checkpointDisk();
        assertTrue(store().hasSeedUserData());
    }
    @Test public void userDataSurvivesPackageReplacement() {
        assertEquals("Installer must provide real old data, never seed during verification", 2, store().recordCount());
        assertTrue("SQLite migration lost records, attachments, or user settings", store().hasSeedUserData());
        FixtureStore.Draft draft = store().draft();
        assertEquals("Migration unsaved draft", draft.body);
        assertEquals(3, draft.start); assertEquals(9, draft.end); assertEquals(2, draft.selectedId);
    }
}
