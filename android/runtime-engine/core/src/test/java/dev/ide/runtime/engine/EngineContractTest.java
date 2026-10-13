// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class EngineContractTest {
    @Test public void sealedKindsAndNodeQualification() {
        assertEquals(2, Engine.values().length);
        assertThrows(IllegalArgumentException.class, () -> Engine.fromId("npm"));
        assertThrows(IllegalArgumentException.class, () -> request(Engine.NODE, "main.js", false));
        assertEquals(Engine.NODE, request(Engine.NODE, "main.mjs", true).engine);
        assertEquals(Engine.PYTHON, request(Engine.PYTHON, "app/main.py", false).engine);
        assertThrows(IllegalArgumentException.class, () -> request(Engine.PYTHON, "main.js", false));
    }
    @Test public void requestBoundsPreventBinderAndCliAbuse() {
        assertThrows(IllegalArgumentException.class, () -> new EngineRequest(Engine.PYTHON, "x.py", Collections.emptyList(), 99, 8192, false));
        assertThrows(IllegalArgumentException.class, () -> new EngineRequest(Engine.PYTHON, "x.py", Collections.emptyList(), 86400001, 8192, false));
        assertThrows(IllegalArgumentException.class, () -> new EngineRequest(Engine.PYTHON, "x.py", Collections.emptyList(), 1000, 262145, false));
        assertThrows(IllegalArgumentException.class, () -> new EngineRequest(Engine.PYTHON, "x.py", Arrays.asList("x\0y"), 1000, 8192, false));
        assertThrows(IllegalArgumentException.class, () -> new EngineRequest(Engine.PYTHON, "x.py", Collections.nCopies(129, "a"), 1000, 8192, false));
        char[] chars = new char[11000]; Arrays.fill(chars, '你');
        assertThrows(IllegalArgumentException.class, () -> new EngineRequest(Engine.PYTHON, "x.py", Arrays.asList(new String(chars)), 1000, 8192, false));
        // Flags are ordinary script argv entries, never injected before the selected entry.
        assertEquals("--eval", new EngineRequest(Engine.PYTHON, "x.py", Arrays.asList("--eval"), 1000, 8192, false).arguments.get(0));
    }
    @Test public void pathsRejectTraversalAmbiguityAndSymlinkEscape() throws Exception {
        for (String bad : new String[] {"", "/x.py", "../x.py", "a/../../b.py", "a//b.py", "a/./b.py", "a\\b.py", "a:b.py", "a\nb.py"}) {
            assertThrows("Accepted " + bad, IllegalArgumentException.class, () -> Paths.relative(bad));
        }
        File root = Files.createTempDirectory("engine-path-").toFile();
        File outside = Files.createTempDirectory("engine-outside-").toFile();
        try {
            Files.createSymbolicLink(new File(root, "linked").toPath(), outside.toPath());
            assertThrows(java.io.IOException.class, () -> Paths.inside(root, "linked/entry.py"));
            assertEquals(new File(root, "ok.py").getCanonicalFile(), Paths.inside(root, "ok.py").getCanonicalFile());
        } finally { Files.deleteIfExists(new File(root, "linked").toPath()); root.delete(); outside.delete(); }
    }
    @Test public void boundedRingDrainsMegabytesWithoutGrowing() {
        BoundedBytes buffer = new BoundedBytes(16);
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        buffer.append(bytes, 0, 10); buffer.append(bytes, 0, 10);
        assertEquals("4567890123456789", new String(buffer.snapshot(), StandardCharsets.UTF_8));
        assertEquals(20, buffer.totalBytes()); assertTrue(buffer.truncated());
        byte[] flood = new byte[2 * 1024 * 1024]; Arrays.fill(flood, (byte)'x');
        buffer.append(flood, 0, flood.length);
        assertEquals(16, buffer.snapshot().length); assertEquals(20 + flood.length, buffer.totalBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.append(bytes, -1, 3));
    }
    @Test public void callerCannotMutateArgumentsAfterValidation() {
        java.util.ArrayList<String> args = new java.util.ArrayList<String>(); args.add("a");
        EngineRequest request = new EngineRequest(Engine.PYTHON, "x.py", args, 1000, 8192, false);
        args.clear(); assertEquals(1, request.arguments.size());
        assertThrows(UnsupportedOperationException.class, () -> request.arguments.add("b"));
    }
    private EngineRequest request(Engine engine, String entry, boolean experimental) {
        return new EngineRequest(engine, entry, Collections.emptyList(), 1000, 8192, experimental);
    }
}
