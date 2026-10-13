package dev.ide.reliability.fixture;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class FixtureFunctionalTest {
    @Test public void editorStartsAndSavedRecordRoundTrips() {
        FixtureApplication app = (FixtureApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        app.store().seed();
        try (ActivityScenario<FixtureActivity> scenario = ActivityScenario.launch(FixtureActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.editor().isShown());
                assertEquals(FixtureStore.SAVED_BODY, activity.editor().getText().toString());
                activity.editor().setText("Explicitly saved ordinary edit"); activity.editor().setSelection(4, 9);
                activity.findViewById(FixtureActivity.SAVE_ID).performClick();
                assertEquals("Explicitly saved ordinary edit", activity.store().recordBody(1));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("Explicitly saved ordinary edit", activity.editor().getText().toString());
                assertEquals(4, activity.editor().getSelectionStart()); assertEquals(9, activity.editor().getSelectionEnd());
                assertEquals(2, activity.store().recordCount());
                assertArrayEquals(FixtureStore.ATTACHMENT, activity.store().attachment("notes.txt"));
                assertEquals("title", activity.store().setting("sort"));
            });
        }
    }
}
