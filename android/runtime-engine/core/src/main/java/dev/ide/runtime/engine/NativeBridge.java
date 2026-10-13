// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import java.io.File;
import java.nio.charset.StandardCharsets;

final class NativeBridge {
    private static boolean loaded;
    static synchronized void load(File nativeDirectory) {
        if (!loaded) {
            System.load(new File(nativeDirectory, "libengine_host.so").getAbsolutePath());
            loaded = true;
        }
    }
    static byte[][] utf8(String[] values) {
        byte[][] result = new byte[values.length][];
        for (int i = 0; i < values.length; i++) result[i] = values[i].getBytes(StandardCharsets.UTF_8);
        return result;
    }
    static native int run(byte[][] fields, byte[][] arguments, int stdin, int stdout, int stderr);
    static native void cancel();
    /** Nonblocking atomic pipe write, 0 means backpressure, -1 closed/error. Maximum 4096 bytes. */
    static native int writeInput(int descriptor, byte[] bytes);
}
