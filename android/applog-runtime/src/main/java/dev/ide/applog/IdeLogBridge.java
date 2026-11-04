package dev.ide.applog;

import android.content.Context;

/**
 * Compatibility entry point for previously injected providers. It no longer resolves an implicit service,
 * reads logcat, tees stdout by default, or forwards unpaired data. Configuration and consent are required.
 */
public final class IdeLogBridge {
    /** Legacy names remain source-compatible, but no legacy wire traffic is emitted. */
    @Deprecated public static final String SINK_ACTION = "dev.ide.applog.SINK";
    @Deprecated public static final String BINDER_DESCRIPTOR = "dev.ide.applog.IAppLogSink";
    @Deprecated public static final int TXN_SUBMIT = 1, PROTOCOL_VERSION = 1;
    private IdeLogBridge() {}
    public static void start(Context context) { Reliability.initializeConfigured(context); }
}
