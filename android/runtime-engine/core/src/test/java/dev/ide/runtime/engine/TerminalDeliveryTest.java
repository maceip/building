// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import org.junit.Test;
import static org.junit.Assert.*;

/** Contract boundary complements the repeated hard-timeout test on an actual device. */
public class TerminalDeliveryTest {
    @Test public void acknowledgementWindowRemainsBounded() {
        assertTrue("Leave a chance for one-way Binder terminal reply to drain", Protocol.TERMINAL_ACK_MILLIS >= 100);
        assertTrue("Hard cancellation must not hold a runaway process indefinitely", Protocol.TERMINAL_ACK_MILLIS <= 500);
        assertTrue(Protocol.CANCEL_GRACE_MILLIS + Protocol.TERMINAL_ACK_MILLIS <= 2000);
        assertTrue(Protocol.REPLY_TIMEOUT_MILLIS > Protocol.CANCEL_GRACE_MILLIS + Protocol.TERMINAL_ACK_MILLIS);
    }
}
