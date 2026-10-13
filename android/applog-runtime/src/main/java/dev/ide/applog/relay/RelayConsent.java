package dev.ide.applog.relay;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Separate from local logging consent. Revocation invalidates captured request permits. No auto-grant. */
public final class RelayConsent {
    private final EnumSet<RelayScope> allowed = EnumSet.noneOf(RelayScope.class);
    private final List<Registration> active = new ArrayList<Registration>();
    private long generation;

    /** Caller must obtain informed, separate opt-in for each scope. Does not start network work. */
    public synchronized void grant(RelayScope scope) {
        if (scope == null) throw new IllegalArgumentException("Scope required");
        allowed.add(scope);
    }
    public void revoke(RelayScope scope) {
        List<Registration> cancelling;
        synchronized (this) { allowed.remove(scope); generation++; cancelling = new ArrayList<Registration>(active); active.clear(); }
        for (Registration registration : cancelling) registration.cancel();
    }
    public void revokeAll() {
        List<Registration> cancelling;
        synchronized (this) { allowed.clear(); generation++; cancelling = new ArrayList<Registration>(active); active.clear(); }
        for (Registration registration : cancelling) registration.cancel();
    }
    public synchronized boolean allows(RelayScope scope) { return allowed.contains(scope); }
    synchronized Permit permit(RelayScope scope) throws IOException {
        if (!allowed.contains(scope)) throw new ConsentRevokedException();
        return new Permit(this, scope, generation);
    }
    private synchronized void check(Permit permit) throws IOException {
        if (permit.generation != generation || !allowed.contains(permit.scope)) throw new ConsentRevokedException();
    }
    public static final class Permit {
        private final RelayConsent owner;
        private final RelayScope scope;
        private final long generation;
        private Permit(RelayConsent owner, RelayScope scope, long generation) {
            this.owner = owner; this.scope = scope; this.generation = generation;
        }
        /** Transport MUST call immediately before opening/sending and between bounded I/O chunks. */
        public void check() throws IOException { owner.check(this); }
        /**
         * Linearizes dispatch with revocation: once revoke returns, no captured permit starts a request.
         * Revocation can wait for a bounded connection handshake already starting; it cannot unsend bytes.
         */
        public <T> T beginRequest(RequestStart<T> start) throws IOException {
            synchronized (owner) { owner.check(this); return start.start(); }
        }
        /** Disconnect/clear active request resources when consent is revoked; returned handle unregisters. */
        public Closeable onRevoke(Runnable cancellation) throws IOException {
            synchronized (owner) {
                owner.check(this);
                Registration registration = new Registration(owner, cancellation);
                owner.active.add(registration);
                return registration;
            }
        }
    }
    public interface RequestStart<T> {
        T start() throws IOException;
    }
    private static final class Registration implements Closeable {
        private final RelayConsent owner;
        private Runnable cancellation;
        Registration(RelayConsent owner, Runnable cancellation) { this.owner = owner; this.cancellation = cancellation; }
        void cancel() {
            Runnable action;
            synchronized (owner) { action = cancellation; cancellation = null; }
            if (action != null) { try { action.run(); } catch (RuntimeException ignored) { /* Best effort; permits still invalid. */ } }
        }
        @Override public void close() {
            synchronized (owner) { cancellation = null; owner.active.remove(this); }
        }
    }
    public static final class ConsentRevokedException extends IOException {
        private static final long serialVersionUID = 1L;
        public ConsentRevokedException() { super("Relay consent is not active"); }
    }
}
