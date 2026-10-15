// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine.reliability;

import dev.ide.runtime.engine.Engine;
import dev.ide.runtime.engine.EngineClient;
import dev.ide.runtime.engine.EngineSnapshot;
import dev.ide.runtime.engine.EngineSnapshotFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReliabilityEngineListenerTest {
    private static class Recorder implements ReliabilityEngineListener.Sink {
        int probes; boolean enabled = true;
        final List<String> breadcrumbs = new ArrayList<String>();
        final List<ReliabilityEngineListener.EngineFailure> errors = new ArrayList<ReliabilityEngineListener.EngineFailure>();
        public boolean enabled() { probes++; return enabled; }
        public void breadcrumb(String value) { breadcrumbs.add(value); }
        public void exception(ReliabilityEngineListener.EngineFailure value) { errors.add(value); }
    }
    private static class Delegate implements EngineClient.Listener {
        int calls, input;
        public void onState(EngineSnapshot value) { calls++; }
        public void onInputAccepted(int bytes) { input = bytes; }
    }
    @Test public void disabledAdapterMakesZeroSdkCallsAndDoesNotReplayOnConsent() {
        Recorder sdk = new Recorder(); Delegate delegate = new Delegate();
        ReliabilityEngineListener listener = new ReliabilityEngineListener(Engine.PYTHON, delegate, false, sdk);
        EngineSnapshot value = state("private-id", EngineSnapshot.Phase.CRASHED, null);
        listener.onState(value); listener.setCollectionEnabled(true); listener.onState(value);
        assertEquals(0, sdk.probes); assertTrue(sdk.errors.isEmpty()); assertEquals(2, delegate.calls);
    }
    @Test public void recordsOnlySanitizedTypedTerminalFailureOnce() {
        Recorder sdk = new Recorder(); Delegate delegate = new Delegate();
        ReliabilityEngineListener listener = new ReliabilityEngineListener(Engine.NODE, delegate, true, sdk);
        EngineSnapshot value = state("token-as-session", EngineSnapshot.Phase.FINISHED, 83);
        listener.onState(value); listener.onState(value); listener.onInputAccepted(42);
        assertEquals(1, sdk.errors.size()); assertEquals(1, sdk.breadcrumbs.size()); assertEquals(42, delegate.input);
        assertEquals(ReliabilityEngineListener.FailureKind.SCRIPT_EXIT, sdk.errors.get(0).kind);
        assertEquals(EngineSnapshot.Phase.FINISHED, sdk.errors.get(0).stage);
        assertEquals("app_engine engine=node stage=FINISHED kind=SCRIPT_EXIT", sdk.breadcrumbs.get(0));
        String event = sdk.breadcrumbs.get(0) + sdk.errors.get(0).getMessage();
        for (String forbidden : new String[] {"token", "private", "stderr", "stdout", "secret", "1234", "83"}) assertFalse(event, event.contains(forbidden));
        assertEquals(0, sdk.errors.get(0).getStackTrace().length); assertNull(sdk.errors.get(0).getCause());
    }
    @Test public void sdkRevocationAndNormalCancellationAreRespected() {
        Recorder sdk = new Recorder(); Delegate delegate = new Delegate();
        ReliabilityEngineListener listener = new ReliabilityEngineListener(Engine.PYTHON, delegate, true, sdk);
        listener.onState(state("ok", EngineSnapshot.Phase.FINISHED, 0));
        listener.onState(state("cancel", EngineSnapshot.Phase.CANCELLED, 130));
        listener.onState(state("running", EngineSnapshot.Phase.RUNNING, null));
        assertEquals(0, sdk.probes);
        sdk.enabled = false; listener.onState(state("revoked", EngineSnapshot.Phase.TIMED_OUT, null));
        sdk.enabled = true; listener.onState(state("revoked", EngineSnapshot.Phase.TIMED_OUT, null));
        assertTrue(sdk.errors.isEmpty());
        listener.onState(state("new", EngineSnapshot.Phase.TIMED_OUT, null));
        assertEquals(ReliabilityEngineListener.FailureKind.TIMEOUT, sdk.errors.get(0).kind);
    }
    @Test public void recordingFailureNeverSuppressesUserCallbacks() {
        Delegate delegate = new Delegate();
        ReliabilityEngineListener listener = new ReliabilityEngineListener(Engine.NODE, delegate, true, new ReliabilityEngineListener.Sink() {
            public boolean enabled() { return true; }
            public void breadcrumb(String value) { throw new IllegalStateException("SDK unavailable"); }
            public void exception(ReliabilityEngineListener.EngineFailure value) { fail("Should not be called"); }
        });
        listener.onState(state("id", EngineSnapshot.Phase.CRASHED, null)); assertEquals(1, delegate.calls);
    }
    @Test public void lifetimeDedupIsStrictlyBoundedWithoutReacceptingOldSessions() {
        Recorder sdk = new Recorder(); Delegate delegate = new Delegate();
        ReliabilityEngineListener listener = new ReliabilityEngineListener(Engine.PYTHON, delegate, true, sdk);
        for (int i = 0; i < 300; i++) listener.onState(state("s" + i, EngineSnapshot.Phase.FAILED, null));
        listener.onState(state("s0", EngineSnapshot.Phase.FAILED, null));
        assertEquals(256, sdk.errors.size()); assertEquals(301, delegate.calls);
    }
    @Test public void actualSdkFacadeIsInertUntilSeparatelyInitialized() {
        Delegate delegate = new Delegate();
        new ReliabilityEngineListener(Engine.PYTHON, delegate).onState(state("default", EngineSnapshot.Phase.CRASHED, null));
        new ReliabilityEngineListener(Engine.PYTHON, delegate, true).onState(state("enabled-but-sdk-uninitialized", EngineSnapshot.Phase.CRASHED, null));
        assertEquals(2, delegate.calls);
    }
    private static EngineSnapshot state(String id, EngineSnapshot.Phase phase, Integer exit) {
        return EngineSnapshotFixtures.create(id, phase, exit);
    }
}
