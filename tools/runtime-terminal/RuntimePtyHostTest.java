package dev.ide.android.workbench.terminal;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Real local PTY tests, not a ProcessBuilder pipe or a mocked terminal. No Android/device actions. */
public class RuntimePtyHostTest {
    private RuntimePty start(String script) throws Exception {
        Map<String, String> environment = new HashMap<>();
        environment.put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        environment.put("HOME", System.getProperty("user.home"));
        environment.put("TERM", "xterm-256color");
        return RuntimePty.start(Arrays.asList("/bin/sh", "-c", script), "/tmp", environment, 80, 24);
    }
    private String until(RuntimePty child, String marker) throws Exception {
        StringBuilder text = new StringBuilder();
        byte[] buffer = new byte[16384];
        long deadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < deadline && !text.toString().contains(marker)) {
            int count = child.read(buffer);
            if (count > 0) text.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            else if (count < 0) break;
        }
        assertTrue("Missing " + marker + ": " + text, text.toString().contains(marker));
        return text.toString();
    }
    private void send(RuntimePty child, String input) throws Exception {
        byte[] data = input.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (offset < data.length && System.nanoTime() < deadline) {
            offset += child.write(data, offset, data.length - offset);
            if (offset < data.length) Thread.sleep(5);
        }
        assertEquals(data.length, offset);
    }
    private int exit(RuntimePty child) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        int result;
        byte[] drain = new byte[16384];
        while ((result = child.peekExit()) == RuntimePty.RUNNING && System.nanoTime() < deadline) {
            child.read(drain); // Darwin can wait for final tty output to drain before reporting exit.
            Thread.sleep(10);
        }
        if (result == RuntimePty.RUNNING) {
            Process diagnostic = new ProcessBuilder("/bin/ps", "-p", Integer.toString(child.getPid()), "-o", "pid,ppid,pgid,stat,command").inheritIO().start();
            diagnostic.waitFor();
        }
        assertNotEquals("PTY child did not exit", RuntimePty.RUNNING, result);
        return result;
    }

    @Test public void stdinOutErrAreTtysAndKernelLineDisciplineAcceptsInput() throws Exception {
        try (RuntimePty child = start("[ -t 0 ] && [ -t 1 ] && [ -t 2 ] || exit 91; printf 'TTY-READY\\n'; read value; printf 'GOT:%s\\n' \"$value\"")) {
            until(child, "TTY-READY");
            send(child, "hello\r");
            until(child, "GOT:hello");
            assertEquals(0, exit(child));
        }
    }
    @Test public void resizedDimensionsReachTheSlaveAndSigwinchJob() throws Exception {
        try (RuntimePty child = start("stty size; printf 'SIZE-READY\\n'; read value; stty size; printf 'SIZE-DONE\\n'")) {
            assertTrue(until(child, "SIZE-READY").contains("24 80"));
            child.resize(103, 31);
            send(child, "\r");
            assertTrue(until(child, "SIZE-DONE").contains("31 103"));
        }
    }
    @Test public void ctrlCIsATerminalSignalAndNotLiteralPipeText() throws Exception {
        try (RuntimePty child = start("printf 'INT-READY\\n'; exec /bin/sleep 30")) {
            until(child, "INT-READY");
            send(child, "\u0003");
            assertEquals(130, exit(child));
        }
    }
    @Test public void ctrlDProducesCanonicalEof() throws Exception {
        try (RuntimePty child = start("printf 'EOF-READY\\n'; cat; printf 'EOF-DONE\\n'")) {
            until(child, "EOF-READY");
            send(child, "\u0004");
            until(child, "EOF-DONE");
            assertEquals(0, exit(child));
        }
    }
    @Test public void stopKillsLeaderAndDescendantEvenWhenTermIsIgnored() throws Exception {
        Path marker = Files.createTempFile("pty-descendant-", ".pid");
        try (RuntimePty child = start("trap '' TERM; (trap '' TERM; exec sleep 30) & printf '%s' $! > '" + marker + "'; printf 'STOP-READY\\n'; wait")) {
            until(child, "STOP-READY");
            int descendant = Integer.parseInt(Files.readString(marker));
            child.signalSession(RuntimePty.SIGTERM);
            Thread.sleep(80);
            assertEquals(RuntimePty.RUNNING, child.peekExit());
            child.signalSession(RuntimePty.SIGKILL);
            assertEquals(137, exit(child));
            // A zombie is already dead and cannot hold files/listeners, but may await host init reaping.
            long deadline = System.nanoTime() + 3_000_000_000L;
            boolean live;
            do {
                Process ps = new ProcessBuilder("/bin/ps", "-p", Integer.toString(descendant), "-o", "stat=").start();
                String state = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                ps.waitFor();
                live = !state.isEmpty() && !state.startsWith("Z");
                if (live) Thread.sleep(20);
            } while (live && System.nanoTime() < deadline);
            assertFalse("Stopped terminal left a live descendant", live);
        } finally { Files.deleteIfExists(marker); }
    }
    @Test public void launchFailuresAreSynchronousAndNulIsRejected() throws Exception {
        assertThrows(IOException.class, () -> RuntimePty.start(Arrays.asList("/missing/runtime-pty-executable"), "/tmp", Map.of(), 80, 24));
        assertThrows(IllegalArgumentException.class, () -> RuntimePty.start(Arrays.asList("/bin/sh", "bad\0arg"), "/tmp", Map.of(), 80, 24));
        assertThrows(IOException.class, () -> RuntimePty.start(Arrays.asList("/bin/sh"), "/missing/runtime-directory", Map.of(), 80, 24));
    }
}
