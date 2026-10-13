// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

final class Protocol {
    static final int VERSION = 1;
    static final int START = 1, POLL = 2, INPUT = 3, EOF = 4, STOP = 5, RETIRE = 6;
    static final int STATE = 10, INPUT_RESULT = 11, ERROR = 12;
    static final long CANCEL_GRACE_MILLIS = 1500, TERMINAL_ACK_MILLIS = 250, REPLY_TIMEOUT_MILLIS = 10000;
    private Protocol() {}
}
