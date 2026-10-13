package dev.ide.applog;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit-component, authenticated, at-least-once local transport. Never consumes before a valid receipt. */
final class BinderSender implements Closeable {
    private final Context context;
    private final DiskJournal store;
    private final ScheduledThreadPoolExecutor scheduler;
    private final ThreadPoolExecutor ipc;
    private final AtomicBoolean ipcBusy = new AtomicBoolean();
    private final Map<String, Long> blockedSessions = new LinkedHashMap<String, Long>();
    private volatile boolean closed;
    private volatile Binding activeBinding;
    private boolean scheduled;
    private long nextAttemptNanos;

    BinderSender(Context context, DiskJournal store) {
        this.context = context; this.store = store;
        scheduler = new ScheduledThreadPoolExecutor(1, threads("reliability-delivery"));
        scheduler.setKeepAliveTime(15, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);
        ipc = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1), threads("reliability-binder"));
    }

    synchronized void nudge() {
        if (closed || scheduled || ipcBusy.get()) return;
        scheduled = true;
        long delay = Math.max(0, nextAttemptNanos - System.nanoTime());
        try { scheduler.schedule(new Runnable() { @Override public void run() { attempt(0); } }, delay, TimeUnit.NANOSECONDS); }
        catch (RejectedExecutionException stopped) { scheduled = false; }
    }

    private void attempt(final int retry) {
        boolean more = false, failed = false;
        try {
            if (!closed) more = drain();
        } catch (Exception error) { failed = true; }
        synchronized (this) {
            if (closed) { scheduled = false; return; }
            if (failed && retry < 2 && !ipcBusy.get()) {
                try { scheduler.schedule(new Runnable() { @Override public void run() { attempt(retry + 1); } }, retry == 0 ? 2 : 8, TimeUnit.SECONDS); }
                catch (RejectedExecutionException stopped) { scheduled = false; }
            } else {
                scheduled = false;
                nextAttemptNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(failed ? 30 : more ? 2 : 0);
                // A successful bounded drain may leave more on disk. Continue at a paced rate, never spin.
                if (more && !failed) nudge();
            }
        }
    }

    private boolean drain() throws Exception {
        for (int pass = 0; pass < 4 && !closed; pass++) {
            List<Incident> pending = store.snapshot("local", 2049);
            Incident first = null;
            DiskJournal.Grant grant = null;
            for (Incident event : pending) {
                DiskJournal.Grant candidate = store.grant(event.sessionId);
                Long blockedUntil = blockedSessions.get(event.sessionId);
                if (candidate != null && candidate.paired() && (blockedUntil == null || System.nanoTime() >= blockedUntil)) { first = event; grant = candidate; break; }
            }
            if (first == null) return false; // No pairing: retain for an explicitly consented local export.
            String clientNonce = nonce();
            List<Incident> batch = new ArrayList<Incident>();
            for (Incident event : pending) {
                if (!first.sameIdentity(event)) continue;
                batch.add(event);
                try { IncidentProtocol.submit(batch, clientNonce, repeatZeroNonce(), store.droppedCount()); }
                catch (JSONException tooLarge) { batch.remove(batch.size() - 1); break; }
                if (batch.size() == IncidentProtocol.MAX_EVENTS) break;
            }
            if (batch.isEmpty()) throw new IOException("Event exceeds transport bounds");
            verifySigner(grant);
            Binding binding = new Binding(new ComponentName(grant.idePackage, grant.ideService));
            activeBinding = binding;
            try {
                if (closed) return false;
                Intent intent = new Intent(); intent.setComponent(binding.component);
                binding.bound = context.bindService(intent, binding, Context.BIND_AUTO_CREATE);
                if (!binding.bound || !binding.connected.await(3, TimeUnit.SECONDS) || binding.binder == null) throw new IOException("IDE unavailable");
                verifySigner(grant); // Package replacement during bind may not bypass a configured pin.
                String hello = transact(binding.binder, IncidentProtocol.hello(first, clientNonce, grant.pairingNonce));
                if (blockRejectedSession(hello, first.sessionId)) continue;
                JSONObject challenge = IncidentProtocol.response(hello, "hello", first, clientNonce);
                String serverNonce = challenge.getString("serverNonce");
                if (!serverNonce.matches("[A-Za-z0-9_\\-]{32,128}")) throw new IOException("Invalid IDE challenge");
                String frame = IncidentProtocol.submit(batch, clientNonce, serverNonce, store.droppedCount());
                String reply = transact(binding.binder, frame);
                if (blockRejectedSession(reply, first.sessionId)) continue;
                Collection<String> ack = IncidentProtocol.receipt(reply, batch, clientNonce, serverNonce);
                if (ack.isEmpty()) throw new IOException("No durable commit acknowledged");
                if (!closed) store.acknowledge("local", ack);
            } finally { binding.unbind(); activeBinding = null; }
        }
        return !closed && !store.snapshot("local", 1).isEmpty();
    }

    private boolean blockRejectedSession(String json, String sessionId) throws JSONException {
        JSONObject error = new JSONObject(IncidentProtocol.bounded(json));
        if (error.optInt("version", 0) != IncidentProtocol.VERSION || !"error".equals(error.optString("op", ""))) return false;
        String code = error.optString("code", "");
        if (!"UNAUTHORIZED".equals(code) && !"ID_CONFLICT".equals(code) && !"INVALID".equals(code)) return false;
        if (blockedSessions.size() >= 2049) blockedSessions.clear();
        blockedSessions.put(sessionId, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        return true; // Retain these records unchanged; another original build/session can still deliver.
    }

    /** Binder cannot forcibly cancel a remote native transaction. One isolated daemon slot bounds the damage. */
    private String transact(final IBinder binder, final String frame) throws Exception {
        IncidentProtocol.bounded(frame);
        if (closed || !store.consentAllowed() || !ipcBusy.compareAndSet(false, true)) throw new IOException("Binder worker occupied or consent revoked");
        Future<String> result;
        try {
            result = ipc.submit(new Callable<String>() { @Override public String call() throws Exception {
                Parcel request = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    if (closed || !store.consentAllowed()) throw new IOException("Consent revoked before IPC");
                    request.writeInterfaceToken(IncidentProtocol.BINDER_DESCRIPTOR);
                    request.writeString(frame);
                    if (!binder.transact(IncidentProtocol.TXN_SUBMIT, request, reply, 0)) throw new IOException("IDE protocol unavailable");
                    // Reject a large Parcel before readString allocates from it; UTF-8 bound is checked next.
                    if (reply.dataSize() > IncidentProtocol.MAX_FRAME_CHARS * 2 + 1024) throw new IOException("Oversized IDE receipt");
                    reply.readException();
                    return IncidentProtocol.bounded(reply.readString());
                } finally { request.recycle(); reply.recycle(); ipcBusy.set(false); }
            } });
        } catch (RuntimeException rejected) { ipcBusy.set(false); throw rejected; }
        // Do not cancel/recreate a stuck Binder worker: cancellation cannot stop Binder and would leak threads.
        return result.get(3, TimeUnit.SECONDS);
    }

    private void verifySigner(DiskJournal.Grant grant) throws Exception {
        if (grant.ideSignerSha256.isEmpty()) return;
        PackageInfo info = context.getPackageManager().getPackageInfo(grant.idePackage, PackageManager.GET_SIGNATURES);
        if (info.signatures == null || info.signatures.length != 1) throw new SecurityException("Unexpected IDE signing identity");
        for (Signature signature : info.signatures) {
            String hash = hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()));
            if (MessageDigest.isEqual(hash.getBytes(Redactor.UTF8), grant.ideSignerSha256.getBytes(Redactor.UTF8))) return;
        }
        throw new SecurityException("IDE signer mismatch");
    }

    private static String nonce() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return hex(bytes); }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }
    private static String repeatZeroNonce() { return "0000000000000000000000000000000000000000000000000000000000000000"; }
    private static ThreadFactory threads(final String name) { return new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }
    }; }

    @Override public synchronized void close() {
        closed = true;
        scheduler.shutdownNow(); ipc.shutdownNow();
        Binding binding = activeBinding;
        if (binding != null) binding.unbind();
    }

    private final class Binding implements ServiceConnection {
        final ComponentName component;
        final CountDownLatch connected = new CountDownLatch(1);
        volatile IBinder binder;
        volatile boolean bound;
        Binding(ComponentName component) { this.component = component; }
        @Override public void onServiceConnected(ComponentName name, IBinder value) {
            if (!closed && component.equals(name)) binder = value;
            connected.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { binder = null; connected.countDown(); }
        synchronized void unbind() {
            if (!bound) return;
            bound = false;
            try { context.unbindService(this); } catch (RuntimeException ignored) {}
        }
    }
}
