// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.acceptance;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import dev.ide.runtime.engine.Engine;
import dev.ide.runtime.engine.EngineClient;
import dev.ide.runtime.engine.EngineRequest;
import dev.ide.runtime.engine.EngineSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Standard same-UID instrumentation acceptance; no production test hooks or keyguard interaction. */
public final class EngineInstrumentation extends Instrumentation {
    private final Object monitor = new Object();
    private volatile EngineSnapshot state;
    private volatile Throwable callbackFailure;
    private volatile int inputAck = Integer.MIN_VALUE;
    private EngineClient client;
    private Engine engine;
    private int passed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            engine = getTargetContext().getPackageName().endsWith(".node") ? Engine.NODE : Engine.PYTHON;
            main(new Runnable() { @Override public void run() {
                client = new EngineClient(getTargetContext(), new EngineClient.Listener() {
                    @Override public void onState(EngineSnapshot snapshot) {
                        synchronized (monitor) { state = snapshot; monitor.notifyAll(); }
                    }
                    @Override public void onInputAccepted(int count) {
                        synchronized (monitor) { inputAck = count; monitor.notifyAll(); }
                    }
                });
            }});
            EngineSnapshot first = execute("compute", 15000);
            require(first.phase == EngineSnapshot.Phase.FINISHED && Integer.valueOf(0).equals(first.exitCode), describe(first));
            require(first.processId > 0 && first.processId != Process.myPid(), "Runtime must have a different PID");
            for (String expected : new String[] {"CALC=338350", "PERSIST=", "UTF8=你好 café", "ASYNC=ok", "COMPLETE"})
                require(first.stdout.contains(expected), "Missing " + expected + "\n" + describe(first));
            require(first.stderr.contains("stderr-from-real-"), "Native stderr not received");
            mark("real native calculation, unicode, async and stdio", first);
            int counter = counter(first.stdout);
            EngineSnapshot second = execute("compute", 15000);
            require(second.phase == EngineSnapshot.Phase.FINISHED && counter(second.stdout) == counter + 1, describe(second));
            require(first.processId != second.processId, "Single-use interpreter was not replaced");
            mark("new runtime PID and private file persistence", second);

            begin("echo", 15000);
            await(new Condition() { public boolean done() { return state != null && (state.stdout.contains("READY_FOR_INPUT") || state.isTerminal()); } }, 20000);
            require(state.acceptsInput && !state.isTerminal(), describe(state));
            inputAck = Integer.MIN_VALUE;
            main(new Runnable() { @Override public void run() {
                require(client.writeInput("instrumentation-你好\n".getBytes(StandardCharsets.UTF_8)), "Input not accepted for queuing");
            }});
            await(new Condition() { public boolean done() { return inputAck != Integer.MIN_VALUE; } }, 5000);
            require(inputAck > 0, "Input failed: " + inputAck);
            main(new Runnable() { @Override public void run() { client.closeInput(); }});
            terminal(20000);
            require(state.phase == EngineSnapshot.Phase.FINISHED && state.stdout.contains("ECHO=instrumentation-你好") && state.stdout.contains("EOF_COMPLETE"), describe(state));
            mark("nonblocking stdin, unicode echo and EOF", state);

            EngineSnapshot flood = execute("flood", 20000);
            require(flood.phase == EngineSnapshot.Phase.FINISHED && flood.stdout.contains("FLOOD_COMPLETE"), describe(flood));
            require(flood.stdoutBytes > 2_000_000 && flood.stderrBytes > 2_000_000, "Flood was not executed");
            require(flood.stdoutTruncated && flood.stderrTruncated && flood.stdout.length() <= 8192 && flood.stderr.length() <= 8192, "Output memory cap failed");
            mark("multi-megabyte output drained into bounded buffers", flood);

            EngineSnapshot exception = execute("exception", 15000);
            require(exception.phase == EngineSnapshot.Phase.FINISHED && exception.exitCode != null && exception.exitCode != 0, describe(exception));
            require(exception.stderr.contains("intentional-script-error"), "Exception traceback missing");
            mark("real script exception preserves native stderr and exit", exception);
            if (engine == Engine.NODE) {
                EngineSnapshot async = execute("async_exception", 15000);
                require(async.phase == EngineSnapshot.Phase.FINISHED && async.exitCode != null && async.exitCode != 0
                        && async.stderr.contains("intentional-async-error"), describe(async));
                mark("real asynchronous exception reaches bounded stderr", async);
                EngineSnapshot esm = execute("esm.mjs", 15000);
                require(esm.phase == EngineSnapshot.Phase.FINISHED && Integer.valueOf(0).equals(esm.exitCode)
                        && esm.stdout.contains("ESM="), describe(esm));
                mark("native ESM loader and top-level await", esm);
                EngineSnapshot exit = execute("exit", 15000);
                require(exit.phase == EngineSnapshot.Phase.FINISHED && Integer.valueOf(7).equals(exit.exitCode)
                        && exit.stdout.contains("BEFORE_EXIT"), describe(exit));
                mark("process.exit is returned without killing service callback", exit);
                EngineSnapshot sealed = execute("capabilities", 15000);
                require(sealed.phase == EngineSnapshot.Phase.FINISHED && Integer.valueOf(0).equals(sealed.exitCode)
                        && sealed.stdout.contains("SPAWN_DENIED") && sealed.stdout.contains("ADDON_DENIED")
                        && sealed.stdout.contains("WORKER_DENIED"), describe(sealed));
                mark("native-addon, child-process and worker capability boundaries", sealed);
            }

            // Repeat the hard-cancel path: a one-way Binder terminal reply must
            // win over process death every time, not merely on one lucky run.
            for (int attempt = 0; attempt < 3; attempt++) {
                EngineSnapshot timeout = execute("loop", 1000);
                require(timeout.phase == EngineSnapshot.Phase.TIMED_OUT, describe(timeout));
                mark("infinite loop bounded timeout #" + (attempt + 1), timeout);
            }
            begin("echo", 15000);
            await(new Condition() { public boolean done() { return state != null && (state.stdout.contains("READY_FOR_INPUT") || state.isTerminal()); } }, 20000);
            require(!state.isTerminal(), describe(state));
            main(new Runnable() { @Override public void run() { client.stop(); }});
            terminal(5000);
            require(state.phase == EngineSnapshot.Phase.CANCELLED, describe(state));
            mark("explicit cancellation while blocked on stdin", state);

            EngineSnapshot crash;
            if (engine == Engine.NODE) {
                EngineSnapshot unsupported = execute("crash", 10000);
                require(unsupported.phase == EngineSnapshot.Phase.FINISHED && Integer.valueOf(1).equals(unsupported.exitCode)
                        && unsupported.stderr.contains("ERR_WORKER_UNSUPPORTED_OPERATION"), describe(unsupported));
                mark("process.abort explicitly unsupported in non-owning Node environment", unsupported);
                begin("echo", 15000);
                await(new Condition() { public boolean done() { return state != null && (state.stdout.contains("READY_FOR_INPUT") || state.isTerminal()); } }, 20000);
                require(!state.isTerminal(), describe(state));
                final int pid = state.processId;
                require(pid > 0 && pid != Process.myPid(), "Refuse to signal instrumentation/client process");
                require(getTargetContext().getApplicationInfo().uid == Process.myUid(), "Instrumentation must share the target app UID");
                android.app.ActivityManager manager = (android.app.ActivityManager)getTargetContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
                boolean owned = false;
                if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
                    if (process.pid == pid && process.uid == Process.myUid()
                            && (getTargetContext().getPackageName() + ":runtime").equals(process.processName)) owned = true;
                }
                require(owned, "Refuse to signal unless public ActivityManager verifies exact runtime PID, UID and name");
                // The standard same-UID test harness supplies the crash stimulus;
                // no production debug action/exported component/native API is added.
                Process.sendSignal(pid, 6); // SIGABRT, not ordinary service cancellation.
                terminal(10000); crash = state;
                require(crash.phase == EngineSnapshot.Phase.CRASHED, describe(crash));
                mark("same-UID test SIGABRT contained in verified Node runtime PID", crash);
            } else {
                crash = execute("crash", 10000);
                require(crash.phase == EngineSnapshot.Phase.CRASHED, describe(crash));
                mark("Python os.abort contained in runtime process", crash);
            }
            EngineSnapshot recovered = execute("compute", 15000);
            require(recovered.phase == EngineSnapshot.Phase.FINISHED && recovered.stdout.contains("COMPLETE"), describe(recovered));
            require(counter(recovered.stdout) == counter + 2, "Persistence changed across abort/recovery");
            mark("explicit post-crash restart runs offline and preserves data", recovered);
            result.putString("stream", "PASS " + passed + " actual " + engine.id + " Android embedding assertions; no UI/keyguard interaction\n");
            result.putInt("passed", passed);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL after " + passed + " checks: " + failure + "\n" + (state == null ? "no state" : describe(state)) + "\n");
            result.putString("shortMsg", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (client != null) main(new Runnable() { @Override public void run() { client.close(); }});
        }
    }
    private EngineSnapshot execute(String entry, long timeout) throws Exception {
        begin(entry, timeout); terminal(timeout + 12000); return state;
    }
    private void begin(final String entry, final long timeout) {
        state = null;
        main(new Runnable() { @Override public void run() {
            client.start(new EngineRequest(engine, entry.contains(".") ? entry : entry + (engine == Engine.PYTHON ? ".py" : ".js"),
                    Collections.<String>emptyList(), timeout, 8192, engine == Engine.NODE));
        }});
    }
    private void terminal(long timeout) throws Exception {
        await(new Condition() { public boolean done() { return state != null && state.isTerminal(); } }, timeout);
    }
    private interface Condition { boolean done(); }
    private void await(Condition condition, long millis) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + millis;
        synchronized (monitor) {
            while (!condition.done()) {
                if (callbackFailure != null) throw new AssertionError("Main-thread failure", callbackFailure);
                long left = end - android.os.SystemClock.elapsedRealtime();
                if (left <= 0) throw new AssertionError("Acceptance wait timed out: " + (state == null ? "no state" : describe(state)));
                monitor.wait(left);
            }
        }
    }
    private void main(final Runnable action) {
        runOnMainSync(new Runnable() { @Override public void run() {
            try { action.run(); }
            catch (Throwable failure) { synchronized (monitor) { callbackFailure = failure; monitor.notifyAll(); } }
        }});
        if (callbackFailure != null) throw new AssertionError("Main-thread callback failed", callbackFailure);
    }
    private void mark(String test, EngineSnapshot snapshot) {
        passed++;
        Bundle event = new Bundle(); event.putString("stream", "PASS " + test + " pid=" + snapshot.processId + " exit=" + snapshot.exitCode + "\n");
        event.putString("test", test); event.putInt("current", passed); sendStatus(0, event);
    }
    private static void require(boolean condition, String failure) { if (!condition) throw new AssertionError(failure); }
    private static int counter(String output) {
        for (String line : output.split("\n")) if (line.startsWith("PERSIST=")) return Integer.parseInt(line.substring(8));
        throw new AssertionError("No persistence result");
    }
    private static String describe(EngineSnapshot state) {
        return state.phase + " pid=" + state.processId + " exit=" + state.exitCode + " error=" + state.error
                + "\nstdout=" + state.stdout + "\nstderr=" + state.stderr;
    }
}
