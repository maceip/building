// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import java.util.UUID;

/** Generated-app runtime client. Own for one Activity/ViewModel lifetime, close on its end.
 * All methods/callbacks run on the main thread. Output is bounded and polled, not a Binder flood.
 * A run is NEVER replayed after process loss; call start again explicitly to recover.
 */
public final class EngineClient implements AutoCloseable {
    public interface Listener {
        void onState(EngineSnapshot state);
        /** 0 is pipe backpressure (retry later), -1 means closed. No unbounded input queue. */
        default void onInputAccepted(int bytes) {}
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger callback = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) { receive(message); }
    });
    private Messenger service;
    private ServiceConnection connection;
    private IBinder.DeathRecipient death;
    private EngineRequest request;
    private EngineSnapshot state;
    private String id;
    private boolean closed, inputPending, pollPending;
    private long lastReply;
    private int retiredPid;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (closed || state == null || state.isTerminal()) return;
            if (SystemClock.elapsedRealtime() - lastReply > Protocol.REPLY_TIMEOUT_MILLIS) {
                fail("Runtime stopped responding; close and start again", true); return;
            }
            if (service != null && !pollPending) {
                pollPending = true; send(Protocol.POLL, new Bundle());
            }
            main.postDelayed(this, 250);
        }
    };

    public EngineClient(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("Context and listener required");
        this.context = context.getApplicationContext(); this.listener = listener;
    }
    public EngineSnapshot snapshot() { checkThread(); return state; }

    public void start(EngineRequest request) {
        checkThread();
        if (closed) throw new IllegalStateException("Client closed");
        if (request == null) throw new IllegalArgumentException("Request required");
        if (state != null && !state.isTerminal()) throw new IllegalStateException("Session already running");
        release();
        this.request = request; id = UUID.randomUUID().toString();
        inputPending = false; pollPending = false; lastReply = SystemClock.elapsedRealtime();
        publish(EngineSnapshot.local(id, EngineSnapshot.Phase.CONNECTING, "", null));
        // Wait briefly if our retired single-use process is still exiting; do not send START to it again.
        bindWhenRetired(SystemClock.elapsedRealtime() + 4000);
        main.postDelayed(poll, 250);
    }
    private void bindWhenRetired(final long limit) {
        if (closed || state == null || state.isTerminal()) return;
        if (retiredPid > 0 && new java.io.File("/proc/" + retiredPid).exists()) {
            if (SystemClock.elapsedRealtime() >= limit) { fail("Previous runtime has not retired", false); return; }
            main.postDelayed(new Runnable() { @Override public void run() { bindWhenRetired(limit); } }, 50);
            return;
        }
        retiredPid = 0;
        final String generation = id;
        connection = new ServiceConnection() {
            private boolean connectedOnce;
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (!generation.equals(id) || closed) return;
                // BIND_AUTO_CREATE can reconnect after process death. Never send a
                // second START on that implicit reconnect: scripts may have side effects.
                if (connectedOnce) {
                    if (state != null && !state.isTerminal()) fail("Runtime reconnected after process loss; start again explicitly", true);
                    else release();
                    return;
                }
                connectedOnce = true;
                service = new Messenger(binder);
                death = new IBinder.DeathRecipient() {
                    @Override public void binderDied() { main.post(new Runnable() {
                        @Override public void run() {
                            if (generation.equals(id)) disconnected(generation, "Runtime process died; execution was not replayed");
                        }
                    }); }
                };
                try { binder.linkToDeath(death, 0); }
                catch (RemoteException gone) { fail("Runtime died during connection", true); return; }
                Bundle data = new Bundle(); data.putBundle("request", EngineClient.this.request.encode());
                send(Protocol.START, data);
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (generation.equals(id)) disconnected(generation, "Runtime process disconnected; restart explicitly");
            }
            @Override public void onBindingDied(ComponentName name) {
                if (generation.equals(id)) disconnected(generation, "Runtime binding died");
            }
            @Override public void onNullBinding(ComponentName name) {
                if (generation.equals(id)) fail("Runtime service refused binding", false);
            }
        };
        try {
            if (!context.bindService(new Intent(context, RuntimeService.class), connection, Context.BIND_AUTO_CREATE))
                fail("Runtime service is not packaged in this app", false);
        } catch (RuntimeException error) { fail("Cannot bind runtime: " + error.getMessage(), false); }
    }

    /** Returns false without enqueueing when full, busy, or disconnected; acknowledgements report actual bytes. */
    public boolean writeInput(byte[] bytes) {
        checkThread();
        if (bytes == null || bytes.length == 0 || bytes.length > 4096) throw new IllegalArgumentException("Input chunk must be 1..4096 bytes");
        if (closed || inputPending || state == null || !state.acceptsInput || service == null) return false;
        inputPending = true;
        Bundle data = new Bundle(); data.putByteArray("bytes", bytes.clone()); send(Protocol.INPUT, data); return true;
    }
    public void closeInput() { checkThread(); if (service != null) send(Protocol.EOF, new Bundle()); }
    public void stop() {
        checkThread();
        if (state == null || state.isTerminal()) return;
        if (service == null) {
            publish(EngineSnapshot.local(id, EngineSnapshot.Phase.CANCELLED, "Cancelled before binding", state));
            release();
        } else send(Protocol.STOP, new Bundle());
    }
    @Override public void close() {
        checkThread();
        if (closed) return;
        if (service != null) send(Protocol.RETIRE, new Bundle());
        closed = true; main.removeCallbacksAndMessages(null); release();
    }

    private void receive(Message message) {
        if (closed || message.arg1 != Protocol.VERSION || id == null) return;
        Bundle data = message.getData();
        if (!id.equals(data.getString("id"))) return;
        lastReply = SystemClock.elapsedRealtime();
        switch (message.what) {
            case Protocol.STATE:
                pollPending = false;
                if (state != null && state.isTerminal()) return;
                try {
                    EngineSnapshot incoming = EngineSnapshot.decode(data);
                    if (incoming.isTerminal()) {
                        // Store before acknowledgement: a simultaneous Binder death
                        // must not overwrite a received terminal outcome as CRASHED.
                        state = incoming;
                        retiredPid = incoming.processId;
                        send(Protocol.RETIRE, new Bundle());
                        release();
                        main.removeCallbacks(poll);
                    }
                    publish(incoming);
                } catch (RuntimeException malformed) { fail("Malformed runtime response", true); }
                break;
            case Protocol.INPUT_RESULT:
                inputPending = false; listener.onInputAccepted(data.getInt("accepted", -1)); break;
            case Protocol.ERROR: fail(data.getString("error", "Runtime request failed"), false); break;
            default: fail("Unsupported runtime response", true);
        }
    }
    private void send(int what, Bundle data) {
        if (service == null) return;
        Message message = Message.obtain(null, what); message.arg1 = Protocol.VERSION; message.replyTo = callback;
        data.putString("id", id); message.setData(data);
        try { service.send(message); }
        catch (RemoteException gone) { disconnected(id, "Runtime connection lost; execution was not replayed"); }
    }
    private void disconnected(final String generation, final String detail) {
        // Binder death/ServiceConnection callbacks are not ordered with Messenger
        // replies. Permit an already-sent terminal state to drain; never infer an
        // outcome from a deadline or automatically replay execution.
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!closed && generation.equals(id) && state != null && !state.isTerminal()) fail(detail, true);
            }
        }, Protocol.TERMINAL_ACK_MILLIS);
    }
    private void fail(String detail, boolean crashed) {
        if (state == null || state.isTerminal()) { release(); return; }
        EngineSnapshot failure = EngineSnapshot.local(id,
                crashed ? EngineSnapshot.Phase.CRASHED : EngineSnapshot.Phase.FAILED, detail, state);
        if (state.processId > 0) retiredPid = state.processId;
        release(); main.removeCallbacks(poll); publish(failure);
    }
    private void release() {
        if (service != null && death != null) service.getBinder().unlinkToDeath(death, 0);
        if (connection != null) {
            try { context.unbindService(connection); } catch (IllegalArgumentException ignored) {}
        }
        connection = null; service = null; death = null; inputPending = false; pollPending = false;
    }
    private void publish(EngineSnapshot snapshot) { state = snapshot; listener.onState(snapshot); }
    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("EngineClient is main-thread confined");
    }
}
