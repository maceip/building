package com.termux.terminal;

/**
 * Locally narrowed emulator-only client. The upstream Android TerminalSession (which
 * reflects into FileDescriptor) is intentionally NOT bundled; our PTY transport is JNI.
 * See upstream.lock.json and NOTICE.txt for the pinned source and local adaptations.
 */
public interface TerminalSessionClient {
    default void onTerminalCursorStateChange(boolean state) {}
    default Integer getTerminalCursorStyle() { return null; }
    default void logError(String tag, String message) {}
    default void logWarn(String tag, String message) {}
    default void logInfo(String tag, String message) {}
    default void logDebug(String tag, String message) {}
    default void logVerbose(String tag, String message) {}
    default void logStackTraceWithMessage(String tag, String message, Exception e) {}
    default void logStackTrace(String tag, Exception e) {}
}
