package dev.ide.reliability.fixture;

import android.app.Application;
import dev.ide.applog.Config;
import dev.ide.applog.Reliability;

/** Only in the test-only debug APK; the SDK is initialized in the fixture's own Linux UID. */
public final class FixtureApplication extends Application {
    private FixtureStore store;

    @Override public void onCreate() {
        super.onCreate();
        Reliability.initialize(this, new Config.Builder()
                .enabled(true).consentGranted(true)
                .buildId("fixture-" + BuildConfig.FLAVOR + "-" + BuildConfig.VERSION_CODE)
                .sourceRevision("fixture-state-v1")
                .captureLifecycle(true).captureCrashes(true).captureNetwork(true)
                .capturePreviousExitInfo(true).teeStdout(false)
                .ideComponent(BuildConfig.IDE_PACKAGE, BuildConfig.IDE_SERVICE)
                .pairingNonce(BuildConfig.PAIRING_NONCE).ideSignerSha256(BuildConfig.IDE_SIGNER_SHA256)
                .maxEvents(256).build());
        if (!Reliability.isInitialized()) throw new IllegalStateException("Fixture SDK: " + Reliability.initializationFailure());
        try {
            // Separate, explicitly consented test observer cursor. IDE delivery may acknowledge local
            // events concurrently; these bytes remain available for assertions across process death.
            Reliability.registerDestination("fixture-observer");
        } catch (java.io.IOException error) { throw new IllegalStateException("Fixture observer registration failed", error); }
        store = new FixtureStore(this, "fixture.sqlite");
    }

    public FixtureStore store() { return store; }
}
