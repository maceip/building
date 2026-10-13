package dev.ide.reliability.fixture;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.ide.applog.Incident;
import dev.ide.applog.IncidentProtocol;
import dev.ide.applog.Reliability;
import dev.ide.reliability.test.HttpFaultServer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NetworkAcceptanceTest {
    @Test public void realClientFaultsNeverCommitFalseSuccessAndSdkCapturesRequestPairs() throws Exception {
        FixtureApplication app = (FixtureApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        assertTrue("SDK initialization: " + Reliability.initializationFailure(), Reliability.isInitialized());
        HttpFaultServer.Fault[] faults = { HttpFaultServer.Fault.READ_TIMEOUT, HttpFaultServer.Fault.UNAUTHORIZED,
                HttpFaultServer.Fault.RATE_LIMITED, HttpFaultServer.Fault.SERVER_ERROR,
                HttpFaultServer.Fault.MALFORMED_JSON, HttpFaultServer.Fault.PARTIAL_BODY, HttpFaultServer.Fault.OK };
        FixtureHttpClient.Outcome[] outcomes = { FixtureHttpClient.Outcome.TIMEOUT, FixtureHttpClient.Outcome.UNAUTHORIZED,
                FixtureHttpClient.Outcome.RATE_LIMITED, FixtureHttpClient.Outcome.SERVER_ERROR,
                FixtureHttpClient.Outcome.MALFORMED, FixtureHttpClient.Outcome.PARTIAL, FixtureHttpClient.Outcome.SAVED };
        int[] statuses = { 0, 401, 429, 500, 200, 200, 200 };
        for (int i = 0; i < faults.length; i++) {
            app.store().seed();
            app.store().putSetting("lastSync", "not-saved");
            Set<String> before = ids(Reliability.journal("fixture-observer").snapshotPending(256));
            try (HttpFaultServer server = HttpFaultServer.start(app, faults[i])) {
                FixtureHttpClient.Result result = new FixtureHttpClient().sync(app.store(), server.url());
                assertEquals(faults[i].name(), outcomes[i], result.outcome);
                assertEquals(statuses[i], result.status);
                assertEquals("One observed real socket request", 1, server.observedRequests());
                assertNull(server.serverFailure());
                assertTrue("Unrelated local user data changed", app.store().hasSeedUserData());
                assertEquals(i == faults.length - 1 ? "saved" : "not-saved", app.store().setting("lastSync"));
                assertTrue("SDK disk barrier failed", Reliability.flush());
                List<Incident> starts = new ArrayList<>(), ends = new ArrayList<>();
                for (Incident event : Reliability.journal("fixture-observer").snapshotPending(256)) {
                    if (before.contains(event.eventId)) continue;
                    if (IncidentProtocol.NETWORK_START.equals(event.type)) starts.add(event);
                    if (IncidentProtocol.NETWORK_END.equals(event.type)) ends.add(event);
                }
                assertEquals("Actual adapter must create one start, not a test breadcrumb", 1, starts.size());
                assertEquals("Actual adapter must terminate its request once", 1, ends.size());
                assertEquals(requestId(starts.get(0)), requestId(ends.get(0)));
                assertEquals("dev.ide.reliability.fixture", ends.get(0).packageName);
                assertTrue(ends.get(0).redactedText, ends.get(0).redactedText.contains("status=" + statuses[i]));
                if (faults[i] == HttpFaultServer.Fault.READ_TIMEOUT)
                    assertTrue(ends.get(0).redactedText, ends.get(0).redactedText.contains("error=timeout"));
                if (faults[i] == HttpFaultServer.Fault.PARTIAL_BODY)
                    assertTrue("Real stream failure must reach SDK completion metadata: " + ends.get(0).redactedText,
                            ends.get(0).redactedText.contains("error=io"));
                // Domain JSON/partial-body outcomes come from the real caller, not fabricated network
                // counts. The metadata adapter must not ingest URL path, request headers or JSON body.
                for (Incident event : starts) assertFalse(event.redactedText.contains(server.url().getPath()));
                for (Incident event : ends) assertFalse(event.redactedText.contains("\"saved\""));
            }
        }
    }
    private static Set<String> ids(List<Incident> events) {
        Set<String> ids = new HashSet<>(); for (Incident event : events) ids.add(event.eventId); return ids;
    }
    private static String requestId(Incident event) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile("(?:^| )request: ([0-9a-f]{16})(?: |$)")
                .matcher(event.redactedText);
        assertTrue("Missing real request identity (redaction placeholders do not count): " + event.redactedText,
                match.find());
        return match.group(1);
    }
}
