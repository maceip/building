// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import android.os.Bundle;
import java.nio.charset.StandardCharsets;

/** Immutable bounded snapshot. CRASHED means state was lost; restart is always explicit. */
public final class EngineSnapshot {
    public enum Phase { CONNECTING, PREPARING, RUNNING, STOPPING, FINISHED, CANCELLED, TIMED_OUT, FAILED, CRASHED }
    public final String sessionId;
    public final Phase phase;
    public final String stdout, stderr, error;
    public final long stdoutBytes, stderrBytes;
    public final boolean stdoutTruncated, stderrTruncated, acceptsInput;
    public final Integer exitCode;
    public final int processId;

    EngineSnapshot(String sessionId, Phase phase, String stdout, String stderr, String error,
                   long stdoutBytes, long stderrBytes, boolean stdoutTruncated, boolean stderrTruncated,
                   boolean acceptsInput, Integer exitCode, int processId) {
        this.sessionId = sessionId; this.phase = phase; this.stdout = stdout; this.stderr = stderr;
        this.error = error; this.stdoutBytes = stdoutBytes; this.stderrBytes = stderrBytes;
        this.stdoutTruncated = stdoutTruncated; this.stderrTruncated = stderrTruncated;
        this.acceptsInput = acceptsInput; this.exitCode = exitCode; this.processId = processId;
    }
    public boolean isTerminal() {
        return phase == Phase.FINISHED || phase == Phase.CANCELLED || phase == Phase.TIMED_OUT
                || phase == Phase.FAILED || phase == Phase.CRASHED;
    }
    static EngineSnapshot local(String id, Phase phase, String error, EngineSnapshot previous) {
        return new EngineSnapshot(id, phase, previous == null ? "" : previous.stdout,
                previous == null ? "" : previous.stderr, error, previous == null ? 0 : previous.stdoutBytes,
                previous == null ? 0 : previous.stderrBytes, previous != null && previous.stdoutTruncated,
                previous != null && previous.stderrTruncated, false, null, previous == null ? 0 : previous.processId);
    }
    static EngineSnapshot decode(Bundle b) {
        byte[] out = b.getByteArray("stdout"), err = b.getByteArray("stderr");
        if (out == null || err == null || out.length > 262144 || err.length > 262144)
            throw new IllegalArgumentException("Invalid runtime response");
        return new EngineSnapshot(b.getString("id"), Phase.valueOf(b.getString("phase")),
                new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8), b.getString("error", ""),
                b.getLong("stdoutBytes"), b.getLong("stderrBytes"), b.getBoolean("stdoutTruncated"),
                b.getBoolean("stderrTruncated"), b.getBoolean("acceptsInput"),
                b.containsKey("exit") ? b.getInt("exit") : null, b.getInt("pid"));
    }
}
