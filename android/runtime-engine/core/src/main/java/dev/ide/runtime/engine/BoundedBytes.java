// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import java.util.Arrays;

/** Fixed retained tail. Writers never block on the UI; totalBytes counts even discarded output. */
final class BoundedBytes {
    private final byte[] ring;
    private int end, size;
    private long total;
    BoundedBytes(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        ring = new byte[capacity];
    }
    synchronized void append(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        total += length;
        if (length >= ring.length) {
            System.arraycopy(bytes, offset + length - ring.length, ring, 0, ring.length);
            size = ring.length; end = 0; return;
        }
        int first = Math.min(length, ring.length - end);
        System.arraycopy(bytes, offset, ring, end, first);
        System.arraycopy(bytes, offset + first, ring, 0, length - first);
        end = (end + length) % ring.length;
        size = Math.min(ring.length, size + length);
    }
    synchronized byte[] snapshot() {
        byte[] result = new byte[size];
        int start = (end - size + ring.length) % ring.length;
        int first = Math.min(size, ring.length - start);
        System.arraycopy(ring, start, result, 0, first);
        System.arraycopy(ring, 0, result, first, size - first);
        return result;
    }
    synchronized long totalBytes() { return total; }
    synchronized boolean truncated() { return total > size; }
}
