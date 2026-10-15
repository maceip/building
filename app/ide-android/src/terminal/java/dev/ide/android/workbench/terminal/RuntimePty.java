package dev.ide.android.workbench.terminal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** A real controlling PTY, owned by one I/O worker. Never accesses hidden FileDescriptor fields. */
public final class RuntimePty implements Closeable {
    public static final int RUNNING = -1000;
    public static final int SIGTERM = 15;
    public static final int SIGKILL = 9;
    static { System.loadLibrary("runtime-pty"); }

    private int fd;
    private final int pid;
    private boolean reaped;

    public static RuntimePty start(List<String> command, String directory, Map<String, String> environment,
                                   int columns, int rows) throws IOException {
        if (command.isEmpty()) throw new IllegalArgumentException("Missing terminal executable");
        byte[][] argv = command.stream().map(RuntimePty::bytes).toArray(byte[][]::new);
        byte[][] env = environment.entrySet().stream().map(entry -> {
            if (entry.getKey().isEmpty() || entry.getKey().contains("=")) throw new IllegalArgumentException("Invalid environment name");
            return bytes(entry.getKey() + "=" + entry.getValue());
        }).toArray(byte[][]::new);
        int[] result = nativeSpawn(argv, bytes(directory), env, columns, rows);
        return new RuntimePty(result[0], result[1]);
    }

    private RuntimePty(int fd, int pid) { this.fd = fd; this.pid = pid; }
    private static byte[] bytes(String value) {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in terminal argument");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public int getPid() { return pid; }
    /** False on older kernels/Android releases where other job groups cannot be targeted safely. */
    public static boolean supportsSessionCleanup() { return nativeSupportsSessionCleanup(); }
    /** Returns -1 for EOF, 0 after a bounded poll, otherwise the number of raw terminal bytes. */
    public synchronized int read(byte[] output) throws IOException { return fd < 0 ? -1 : nativeRead(fd, output); }
    /** Nonblocking, partial writes are normal; callers retain unsent bytes. */
    public synchronized int write(byte[] input, int offset, int length) throws IOException {
        if (fd < 0 || reaped) throw new IOException("Terminal has closed");
        return nativeWrite(fd, input, offset, length);
    }
    public synchronized void resize(int columns, int rows) throws IOException {
        if (fd >= 0 && !reaped) nativeResize(fd, columns, rows);
    }
    public synchronized boolean signalSession(int signal) {
        return !reaped && fd >= 0 && nativeSignalSession(fd, pid, signal);
    }
    public synchronized int peekExit() throws IOException { return nativePeekExit(pid); }
    public synchronized int reap() throws IOException {
        if (reaped) throw new IOException("Terminal already reaped");
        int exit = nativeReap(pid);
        if (exit != RUNNING) reaped = true;
        return exit;
    }

    /** Call on I/O, never the UI thread. Close is bounded even when a child ignores SIGTERM. */
    @Override public synchronized void close() throws IOException {
        if (fd < 0) return;
        try {
            if (!reaped) {
                nativeSignalSession(fd, pid, SIGKILL);
                long deadline = System.nanoTime() + 2_000_000_000L;
                byte[] drain = new byte[16384];
                while (nativePeekExit(pid) == RUNNING && System.nanoTime() < deadline) {
                    // Normal session shutdown renders output before close. Emergency cleanup still
                    // drains the tty: some kernels defer exit until their final tty output is read.
                    nativeRead(fd, drain);
                    try { wait(10); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
                int exit = nativeReap(pid);
                reaped = exit != RUNNING;
                if (!reaped) throw new IOException("Terminal did not exit after SIGKILL");
            }
        } finally { nativeClose(fd); fd = -1; }
    }

    private static native int[] nativeSpawn(byte[][] command, byte[] directory, byte[][] environment, int columns, int rows) throws IOException;
    private static native int nativeRead(int fd, byte[] output) throws IOException;
    private static native int nativeWrite(int fd, byte[] input, int offset, int length) throws IOException;
    private static native void nativeResize(int fd, int columns, int rows) throws IOException;
    private static native boolean nativeSupportsSessionCleanup();
    private static native boolean nativeSignalSession(int fd, int pid, int signal);
    private static native int nativePeekExit(int pid) throws IOException;
    private static native int nativeReap(int pid) throws IOException;
    private static native void nativeClose(int fd);
}
