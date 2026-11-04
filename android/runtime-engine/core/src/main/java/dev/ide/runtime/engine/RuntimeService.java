// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bound, same-UID crash containment. One interpreter lifetime per :runtime process.
 * No restart-on-crash and no persistent background/foreground-service claim. */
public final class RuntimeService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message message) { receive(message); }
    });
    private Messenger owner;
    private IBinder.DeathRecipient ownerDeath;
    private String id;
    private EngineSnapshot.Phase phase = EngineSnapshot.Phase.PREPARING;
    private BoundedBytes stdout = new BoundedBytes(1024), stderr = new BoundedBytes(1024);
    private String error = "";
    private Integer exit;
    private ParcelFileDescriptor inputWriter;
    private Future<?> work;
    private volatile boolean bridgeLoaded;
    private boolean stopping, timedOut, finished;
    private long lastPoll;
    private final Runnable deadline = new Runnable() { @Override public void run() { stop(true); } };
    private final Runnable retire = new Runnable() { @Override public void run() { retireProcess(); } };
    private final Runnable lease = new Runnable() {
        @Override public void run() {
            if (!finished && id != null) {
                if (SystemClock.elapsedRealtime() - lastPoll > 15000) stop(false);
                else main.postDelayed(this, 5000);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        // Fail before loading native code if a consumer incorrectly removed android:process.
        String expected = getPackageName() + ":runtime";
        try (InputStream input = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] name = new byte[512]; int length = input.read(name); int end = 0;
            while (end < length && name[end] != 0) end++;
            if (!expected.equals(new String(name, 0, end, java.nio.charset.StandardCharsets.UTF_8)))
                throw new IllegalStateException("RuntimeService must run in the private :runtime process");
        } catch (IOException invalid) { throw new IllegalStateException("Cannot verify runtime process", invalid); }
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { stopSelf(startId); return START_NOT_STICKY; }
    @Override public boolean onUnbind(Intent intent) { if (id != null) retireProcess(); return false; }

    private void receive(Message message) {
        // Non-exported component plus UID enforcement. No cross-app Binder API is offered.
        if (message.sendingUid != Process.myUid() || message.arg1 != Protocol.VERSION || message.replyTo == null) return;
        try {
            Bundle data = message.getData();
            if (message.what == Protocol.START) {
                if (id != null) { replyError(message.replyTo, data.getString("id"), "Runtime process already used; close and reconnect"); return; }
                String candidate = data.getString("id");
                UUID.fromString(candidate);
                EngineRequest request = EngineRequest.decode(data.getBundle("request"));
                owner = message.replyTo;
                ownerDeath = new IBinder.DeathRecipient() { @Override public void binderDied() { main.post(retire); } };
                owner.getBinder().linkToDeath(ownerDeath, 0);
                id = candidate; lastPoll = SystemClock.elapsedRealtime();
                stdout = new BoundedBytes(request.outputLimitBytes); stderr = new BoundedBytes(request.outputLimitBytes);
                main.postDelayed(deadline, request.timeoutMillis);
                main.postDelayed(lease, 5000);
                sendState();
                work = executor.submit(new Runnable() { @Override public void run() { execute(request); } });
                return;
            }
            if (id == null || !id.equals(data.getString("id")) || !owner.getBinder().equals(message.replyTo.getBinder())) return;
            switch (message.what) {
                case Protocol.POLL: lastPoll = SystemClock.elapsedRealtime(); sendState(); break;
                case Protocol.STOP: stop(false); sendState(); break;
                case Protocol.RETIRE: retireProcess(); break;
                case Protocol.EOF: closeInput(); sendState(); break;
                case Protocol.INPUT:
                    byte[] bytes = data.getByteArray("bytes");
                    int count = -1;
                    if (bytes != null && bytes.length > 0 && bytes.length <= 4096 && inputWriter != null && !stopping && bridgeLoaded)
                        count = NativeBridge.writeInput(inputWriter.getFd(), bytes);
                    Bundle result = new Bundle(); result.putString("id", id); result.putInt("accepted", count);
                    reply(owner, Protocol.INPUT_RESULT, result); break;
                default: replyError(owner, id, "Unsupported engine protocol operation");
            }
        } catch (Exception invalid) {
            replyError(message.replyTo, message.getData().getString("id"), clean(invalid));
        }
    }

    private void execute(EngineRequest request) {
        ParcelFileDescriptor[] in = null, out = null, err = null;
        Thread outReader = null, errReader = null;
        int code = 127; String failure = "";
        try {
            EngineAssets.Prepared prepared = EngineAssets.prepare(this, request);
            if (Thread.currentThread().isInterrupted()) throw new IOException("Preparation cancelled");
            NativeBridge.load(prepared.nativeLibs); bridgeLoaded = true;
            in = ParcelFileDescriptor.createPipe(); out = ParcelFileDescriptor.createPipe(); err = ParcelFileDescriptor.createPipe();
            outReader = drain(out[0], stdout, "engine-stdout");
            errReader = drain(err[0], stderr, "engine-stderr");
            final ParcelFileDescriptor writer = in[1];
            main.post(new Runnable() {
                @Override public void run() {
                    if (stopping) close(writer);
                    else { inputWriter = writer; phase = EngineSnapshot.Phase.RUNNING; sendState(); }
                }
            });
            String[] fields = {request.engine.id, prepared.nativeLibs.getAbsolutePath(), prepared.home.getAbsolutePath(),
                    prepared.app.getAbsolutePath(), prepared.work.getAbsolutePath(), prepared.entry.getAbsolutePath()};
            // detachFd transfers ownership to native run(), which closes all three in every path.
            int fdIn = in[0].detachFd(), fdOut = out[1].detachFd(), fdErr = err[1].detachFd();
            code = NativeBridge.run(NativeBridge.utf8(fields),
                    NativeBridge.utf8(request.arguments.toArray(new String[0])), fdIn, fdOut, fdErr);
        } catch (Throwable failed) {
            failure = clean(failed);
        } finally {
            if (in != null) close(in[0]);
            if (out != null) close(out[1]);
            if (err != null) close(err[1]);
            join(outReader); join(errReader);
            final int completedCode = code; final String completedError = failure;
            main.post(new Runnable() { @Override public void run() { complete(completedCode, completedError); } });
        }
    }
    private Thread drain(final ParcelFileDescriptor fd, final BoundedBytes buffer, String name) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                    byte[] bytes = new byte[8192]; int count;
                    while ((count = input.read(bytes)) != -1) buffer.append(bytes, 0, count);
                } catch (IOException ignored) { /* Descriptor closure is expected on cancellation. */ }
            }
        }, name);
        thread.setDaemon(true); thread.start(); return thread;
    }
    private static void join(Thread thread) {
        if (thread != null) try { thread.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private void stop(boolean timeout) {
        if (finished || stopping || id == null) return;
        stopping = true; timedOut = timeout; phase = EngineSnapshot.Phase.STOPPING;
        closeInput();
        sendState(); // Publish the accepted cancellation before touching native execution.
        // Interrupt asset preparation; native execution is cancelled through its sealed ABI.
        if (work != null) work.cancel(true);
        if (bridgeLoaded) {
            Thread cancel = new Thread(new Runnable() { @Override public void run() { NativeBridge.cancel(); } }, "engine-cancel");
            cancel.setDaemon(true); cancel.start();
        }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!finished) {
                    finished = true;
                    phase = timedOut ? EngineSnapshot.Phase.TIMED_OUT : EngineSnapshot.Phase.CANCELLED;
                    error = "Runtime exceeded cancellation grace; process retiring";
                    main.removeCallbacks(deadline); main.removeCallbacks(lease);
                    sendState();
                    // Messenger is one-way. Killing immediately can let Binder death
                    // overtake the terminal state on the client. RETIRE is its explicit
                    // acknowledgement; a 250ms cap still guarantees bounded termination
                    // if the client is stalled or dead while native code keeps running.
                    main.postDelayed(retire, Protocol.TERMINAL_ACK_MILLIS);
                }
            }
        }, Protocol.CANCEL_GRACE_MILLIS);
    }
    private void complete(int code, String failure) {
        if (finished) return;
        finished = true; exit = code; error = failure;
        phase = stopping ? (timedOut ? EngineSnapshot.Phase.TIMED_OUT : EngineSnapshot.Phase.CANCELLED)
                : failure.length() != 0 ? EngineSnapshot.Phase.FAILED : EngineSnapshot.Phase.FINISHED;
        closeInput(); main.removeCallbacks(deadline); main.removeCallbacks(lease);
        sendState();
        // Client sends RETIRE after consuming terminal output. Lost clients cannot leave a warm interpreter.
        main.postDelayed(retire, 3000);
    }
    private void sendState() {
        if (owner == null) return;
        Bundle data = new Bundle(); data.putString("id", id); data.putString("phase", phase.name());
        data.putByteArray("stdout", stdout.snapshot()); data.putByteArray("stderr", stderr.snapshot());
        data.putLong("stdoutBytes", stdout.totalBytes()); data.putLong("stderrBytes", stderr.totalBytes());
        data.putBoolean("stdoutTruncated", stdout.truncated()); data.putBoolean("stderrTruncated", stderr.truncated());
        data.putBoolean("acceptsInput", inputWriter != null && !stopping && !finished);
        data.putString("error", error); data.putInt("pid", Process.myPid());
        if (exit != null) data.putInt("exit", exit);
        reply(owner, Protocol.STATE, data);
    }
    private void reply(Messenger recipient, int what, Bundle data) {
        try { Message reply = Message.obtain(null, what); reply.arg1 = Protocol.VERSION; reply.setData(data); recipient.send(reply); }
        catch (RemoteException gone) { main.post(retire); }
    }
    private void replyError(Messenger recipient, String session, String reason) {
        if (recipient == null) return;
        Bundle data = new Bundle(); data.putString("id", session); data.putString("error", reason);
        reply(recipient, Protocol.ERROR, data);
    }
    private void closeInput() { close(inputWriter); inputWriter = null; }
    private static void close(ParcelFileDescriptor descriptor) {
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
    }
    private static String clean(Throwable error) {
        String text = error.getMessage();
        if (text == null) text = error.getClass().getSimpleName();
        return text.length() > 1000 ? text.substring(0, 1000) : text;
    }
    private void retireProcess() {
        closeInput();
        if (owner != null && ownerDeath != null) owner.getBinder().unlinkToDeath(ownerDeath, 0);
        executor.shutdownNow(); stopSelf();
        // Deliberate only in the manifest-isolated :runtime process, never an arbitrary PID from IPC.
        Process.killProcess(Process.myPid());
    }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null); executor.shutdownNow(); closeInput();
        super.onDestroy();
        if (id != null) Process.killProcess(Process.myPid());
    }
}
