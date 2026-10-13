package dev.ide.reliability.fixture;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ide.applog.Incident;
import dev.ide.applog.IncidentProtocol;
import dev.ide.applog.Reliability;
import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SdkCaptureAcceptanceTest {
    @Test public void realLifecycleAndLocalExportPreserveDurablePendingIdentity() throws Exception {
        assertTrue(Reliability.initializationFailure(), Reliability.isInitialized());
        FixtureApplication app = (FixtureApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        app.store().seed();
        try (ActivityScenario<FixtureActivity> scenario = ActivityScenario.launch(FixtureActivity.class)) {
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.recreate();
        }
        Reliability.recordBreadcrumb("fixture-durable-marker");
        assertTrue(Reliability.flush());
        Set<String> before = new HashSet<>(), transitions = new HashSet<>();
        for (Incident event : Reliability.journal("fixture-observer").snapshotPending(256)) {
            before.add(event.eventId);
            if (IncidentProtocol.LIFECYCLE.equals(event.type) && event.redactedText.contains("FixtureActivity"))
                transitions.add(event.redactedText.split(" ")[0]);
        }
        assertTrue(transitions.toString(), transitions.contains("created"));
        assertTrue(transitions.toString(), transitions.contains("resumed"));
        assertTrue(transitions.toString(), transitions.contains("paused"));
        assertTrue(transitions.toString(), transitions.contains("stopped"));
        assertTrue(transitions.toString(), transitions.contains("destroyed"));
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        Reliability.exportPending(exported, true);
        // The IDE can durably receive and acknowledge the local cursor at any moment. The observer
        // cursor remains independent, so exporting a now-empty local cursor is legitimate.
        assertTrue(exported.size() > 0);
        Set<String> after = new HashSet<>();
        for (Incident event : Reliability.journal("fixture-observer").snapshotPending(256)) after.add(event.eventId);
        assertTrue("Export must never acknowledge undelivered pending data", after.containsAll(before));
        try {
            Reliability.exportPending(new ByteArrayOutputStream(), false);
            fail("Separate export consent must be required");
        } catch (SecurityException expected) { /* collection consent is not sharing consent */ }
    }
}
