// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine.reliability;

import dev.ide.applog.Reliability;
import dev.ide.runtime.engine.Engine;
import dev.ide.runtime.engine.EngineClient;
import dev.ide.runtime.engine.EngineSnapshot;
import java.util.HashSet;
import java.util.Set;

/** Explicit-consent typed SDK adapter, not automatic capture or a transport.
 *
 * Use new ReliabilityEngineListener(engine, delegate, true) only after the user
 * opts in AND initializes Reliability with its own consent configuration. The
 * default constructor is disabled. Existing SDK revocation/destination/network
 * consent still governs persistence and delivery. This adapter adds no hooks,
 * initializes nothing, requests no permissions, and never exports script text,
 * paths, argv, PID, session IDs, stdout/stderr, exception messages or tokens.
 *
 * One instance belongs to one EngineClient. After 256 unique terminal sessions it
 * stops recording until the host deliberately replaces it: bounded memory and no
 * duplicate re-admission of old terminal events.
 */
public final class ReliabilityEngineListener implements EngineClient.Listener {
    public enum FailureKind { SCRIPT_EXIT, RUNTIME_FAILURE, RUNTIME_CRASH, TIMEOUT }

    /** Safe fixed enum-only nonfatal event; no application stack, raw text or cause. */
    public static final class EngineFailure extends RuntimeException {
        public final Engine engine;
        public final FailureKind kind;
        private EngineFailure(Engine engine, FailureKind kind) {
            super("engine=" + engine.id + " kind=" + kind.name(), null, false, false);
            this.engine = engine; this.kind = kind;
        }
    }

    interface Sink {
        boolean enabled();
        void breadcrumb(String value);
        void exception(EngineFailure value);
    }
    private static final class SdkSink implements Sink {
        @Override public boolean enabled() { return Reliability.isInitialized(); }
        @Override public void breadcrumb(String value) { Reliability.recordBreadcrumb(value); }
        @Override public void exception(EngineFailure value) { Reliability.recordException(value); }
    }
    private final Engine engine;
    private final EngineClient.Listener delegate;
    private final Sink sink;
    private final Set<String> terminal = new HashSet<String>();
    private boolean optedIn;
    private static final int MAX_SESSIONS = 256;

    public ReliabilityEngineListener(Engine engine, EngineClient.Listener delegate) {
        this(engine, delegate, false);
    }
    public ReliabilityEngineListener(Engine engine, EngineClient.Listener delegate, boolean optedIn) {
        this(engine, delegate, optedIn, new SdkSink());
    }
    ReliabilityEngineListener(Engine engine, EngineClient.Listener delegate, boolean optedIn, Sink sink) {
        if (engine == null || delegate == null || sink == null) throw new IllegalArgumentException("Engine, delegate and SDK sink required");
        this.engine = engine; this.delegate = delegate; this.optedIn = optedIn; this.sink = sink;
    }
    /** Future terminal callbacks only; events observed before consent are never replayed. */
    public synchronized void setCollectionEnabled(boolean allowed) { optedIn = allowed; }

    @Override public void onState(EngineSnapshot state) {
        try { record(state); }
        catch (RuntimeException | LinkageError unavailable) {
            // Optional diagnostics must not block the app's engine lifecycle callback.
        }
        finally { delegate.onState(state); }
    }
    @Override public void onInputAccepted(int bytes) { delegate.onInputAccepted(bytes); }

    private synchronized void record(EngineSnapshot state) {
        if (state == null || !state.isTerminal() || state.sessionId == null || state.sessionId.length() > 128) return;
        if (terminal.contains(state.sessionId) || terminal.size() >= MAX_SESSIONS) return;
        // Mark even disabled and normal sessions: later consent cannot replay them.
        terminal.add(state.sessionId);
        if (!optedIn) return;
        FailureKind kind;
        switch (state.phase) {
            case FINISHED:
                if (state.exitCode == null || state.exitCode == 0) return;
                kind = FailureKind.SCRIPT_EXIT; break;
            case FAILED: kind = FailureKind.RUNTIME_FAILURE; break;
            case CRASHED: kind = FailureKind.RUNTIME_CRASH; break;
            case TIMED_OUT: kind = FailureKind.TIMEOUT; break;
            default: return; // User cancellation and normal lifecycle are not incidents.
        }
        if (!sink.enabled()) return;
        // Structured fixed fields only; no raw numeric exit status which user scripts could encode data into.
        sink.breadcrumb("app_engine engine=" + engine.id + " phase=" + state.phase.name() + " kind=" + kind.name());
        sink.exception(new EngineFailure(engine, kind));
    }
}
