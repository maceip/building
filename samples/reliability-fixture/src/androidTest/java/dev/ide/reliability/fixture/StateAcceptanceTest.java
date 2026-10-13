package dev.ide.reliability.fixture;

import android.view.View;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Frozen assertions: compiled once and the SAME test APK is run against broken and fixed app APKs. */
@RunWith(AndroidJUnit4.class)
public final class StateAcceptanceTest {
    private static final String DRAFT = "Unsaved Oslo draft — selection survives";

    @Test public void draftAndSelectionSurviveRecreateAndBackground() {
        FixtureApplication app = (FixtureApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        app.store().seed();
        AtomicInteger saves = new AtomicInteger();
        try (ActivityScenario<FixtureActivity> scenario = ActivityScenario.launch(FixtureActivity.class)) {
            scenario.onActivity(activity -> {
                activity.editor().requestFocus(); activity.editor().setText(DRAFT);
                activity.editor().setSelection(8, 12); activity.selectedRecord().setChecked(true);
                assertEquals("Manual save must not happen while typing", FixtureStore.SAVED_BODY,
                        activity.store().recordBody(1));
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> saves.set(activity.savedStateCallbacks()));
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("Unsaved draft lost during real Activity recreation", DRAFT, activity.editor().getText().toString());
                assertEquals(8, activity.editor().getSelectionStart()); assertEquals(12, activity.editor().getSelectionEnd());
                assertTrue("Selected record must survive recreation", activity.selectedRecord().isChecked());
                FixtureStore.Draft persisted = activity.store().draft();
                assertEquals(DRAFT, persisted.body); assertEquals(8, persisted.start); assertEquals(12, persisted.end);
                assertEquals(2, persisted.selectedId);
                assertEquals(FixtureStore.SAVED_BODY, activity.store().recordBody(1));
            });
            assertTrue("Framework save-state callback should execute on background", saves.get() > 0);
            scenario.onActivity(activity -> ((View) activity.findViewById(FixtureActivity.SAVE_ID)).performClick());
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(DRAFT, activity.store().recordBody(2));
                assertEquals(DRAFT, activity.editor().getText().toString());
            });
        }
    }
}
