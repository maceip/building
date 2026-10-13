package dev.ide.applog;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.RejectedExecutionException;

/** Native, local-first Android SDK. API 26+, Java 8, no runtime dependencies beyond the platform. */
public final class Reliability {
    private static volatile RuntimeState runtime;
    private static volatile boolean consentRevoked;
    private static volatile String initializationFailure = "not_initialized";
    private static volatile ConsentState lastConsent;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<Runnable>();
    private Reliability() {}

    enum InitializationStage { APPLICATION_CONTEXT, PROCESS_IDENTITY, PRIVATE_STORAGE, CONSENT_STORE, JOURNAL_OPEN, RUNTIME_CREATE, HOOK_INSTALL, CONFIGURATION }

    /** Bounded support diagnostic, deliberately excluding messages, stack traces, paths, and configuration. */
    static String failureCode(InitializationStage stage, Throwable failure) {
        String operation = "NONE";
        Throwable root = failure;
        for (int i = 0; i < 8 && root != null; i++) {
            if (root instanceof DurableFiles.StorageException) operation = ((DurableFiles.StorageException) root).operation.name();
            Throwable cause = root.getCause();
            if (cause == null || cause == root) break;
            root = cause;
        }
        String name = root == null ? "Unknown" : root.getClass().getName();
        if (!(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.") || name.startsWith("org.json."))
            || name.length() > 160 || !name.matches("[A-Za-z0-9_.$]+")) name = "OtherException";
        return "initialization_failed:" + stage.name() + ":" + operation + ":" + name;
    }

    /**
     * Call with Application, normally from Application.onCreate(). The injected provider uses only the
     * explicitly named metadata asset. Disabled/unconsented configuration installs no hooks and stores nothing.
     */
    public static synchronized void initialize(Context context, Config config) {
        if (context == null || config == null) throw new IllegalArgumentException("Context and Config required");
        if (!config.isCollectionAllowed()) {
            if (runtime != null) revokeConsent();
            initializationFailure = "disabled_or_unconsented";
            return;
        }
        if (runtime != null) { runtime.sender.nudge(); return; }
        consentRevoked = false;
        RuntimeState opened = null;
        DiskJournal store = null;
        InitializationStage stage = InitializationStage.APPLICATION_CONTEXT;
        try {
            Context app = context.getApplicationContext();
            if (app == null) app = context;
            stage = InitializationStage.PROCESS_IDENTITY;
            String process = processName(app);
            stage = InitializationStage.PRIVATE_STORAGE;
            File root = storageRoot(app);
            stage = InitializationStage.CONSENT_STORE;
            ConsentState consent = ConsentState.open(root, false);
            lastConsent = consent;
            if (consent == null) { consentRevoked = true; initializationFailure = "consent_revoked"; return; }
            stage = InitializationStage.JOURNAL_OPEN;
            store = new DiskJournal(new File(root, digest(process)), app.getPackageName(), config, consent);
            stage = InitializationStage.RUNTIME_CREATE;
            opened = new RuntimeState(app, config, store, consent);
            runtime = opened;
            stage = InitializationStage.HOOK_INSTALL;
            opened.install();
            initializationFailure = "";
        } catch (Exception | LinkageError failure) {
            runtime = null;
            try {
                if (opened != null) opened.close(false);
                else if (store != null) store.close();
            } catch (Exception | LinkageError ignored) { /* Preserve the original initialization failure. */ }
            initializationFailure = failureCode(stage, failure);
        }
    }

    /** Only invoke following an explicit user action. Packaged initialization NEVER clears a revocation. */
    public static synchronized void grantConsent(Context context, Config config) throws IOException {
        if (context == null || config == null || !config.isCollectionAllowed()) throw new IllegalArgumentException("Consented enabled config required");
        RuntimeState old = runtime; runtime = null; listeners.clear();
        if (old != null) old.close(false);
        try { lastConsent = ConsentState.open(storageRoot(context), true); }
        catch (Exception e) { throw new IOException("Cannot persist consent", e); }
        initialize(context, config);
    }

    private static File storageRoot(Context context) throws Exception {
        File noBackup = (File) Context.class.getMethod("getNoBackupFilesDir").invoke(context);
        if (noBackup == null) throw new IOException("No private no-backup directory");
        File root = new File(noBackup, "ide-reliability");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create private SDK directory");
        DurableFiles.syncDirectory(noBackup);
        return root;
    }

    /** Fail-closed metadata initialization, used by the legacy provider. Assets are packaged config, not secrets. */
    public static void initializeConfigured(Context context) {
        if (context == null) return;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
            if (info.metaData == null) return;
            String asset = info.metaData.getString(Config.ASSET_METADATA_KEY);
            if (asset == null || !asset.matches("[A-Za-z0-9_.\\-]{1,100}\\.json")) return;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = context.getAssets().open(asset)) {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 16384) return;
                    bytes.write(buffer, 0, n);
                }
            }
            initialize(context, Config.fromJson(new String(bytes.toByteArray(), Redactor.UTF8)));
        } catch (Exception | LinkageError error) { initializationFailure = failureCode(InitializationStage.CONFIGURATION, error); }
    }

    public static boolean isInitialized() { RuntimeState r = runtime; return r != null && !consentRevoked && r.consent.allowed(); }
    /** Fixed stage/operation plus platform exception class only; never messages, credentials, or file paths. */
    public static String initializationFailure() { return initializationFailure; }

    /** Use short developer-defined labels, not user data. Redaction is a guardrail, not a PII classifier. */
    public static void recordBreadcrumb(String text) { record(IncidentProtocol.BREADCRUMB, text); }
    public static void recordException(Throwable error) {
        if (runtime == null || consentRevoked || error == null) return;
        try { record(IncidentProtocol.NONFATAL_JAVA, Redactor.exception(error)); } catch (Throwable ignored) {}
    }
    static boolean networkEnabled() { RuntimeState r = runtime; return r != null && !consentRevoked && r.config.captureNetwork; }
    static void recordNetwork(String type, String text) { if (networkEnabled()) record(type, text); }
    private static void record(String type, String text) {
        RuntimeState r = runtime;
        if (r == null || consentRevoked) return;
        try { r.enqueue(r.store.prepare(type, text)); } catch (Throwable ignored) { r.store.noteDrop(); }
    }

    /** Explicit consent for a future-event destination. Existing events are deliberately not opted in. */
    public static void registerDestination(String destinationId) throws IOException { active().store.registerDestination(destinationId); }
    /** Revoke only this destination's future collection and retained cursors; local/other destination data stays. */
    public static void unregisterDestination(String destinationId) throws IOException { active().store.unregisterDestination(destinationId); }
    public static IncidentJournal journal() { return journal("local"); }
    public static IncidentJournal journal(final String destinationId) {
        final RuntimeState owner = active();
        DiskJournal.validateDestination(destinationId);
        if (!owner.store.hasDestination(destinationId)) throw new IllegalStateException("Register destination with explicit consent first");
        final String generation = owner.store.destinationGeneration(destinationId);
        return new IncidentJournal() {
            private void check() {
                if (active() != owner || !generation.equals(owner.store.destinationGeneration(destinationId))) throw new IllegalStateException("Stale journal consent handle");
            }
            @Override public List<Incident> snapshotPending(int limit) throws IOException { check(); return owner.store.snapshot(destinationId, limit); }
            @Override public void acknowledgeDurablyCommitted(Collection<String> ids) throws IOException { check(); owner.store.acknowledge(destinationId, ids); }
            @Override public long droppedCount() { check(); return owner.store.droppedCount(); }
        };
    }

    /** A bounded local disk-queue barrier, never a Binder/HTTP flush. Prefer calling off the main thread. */
    public static boolean flush() { RuntimeState r = runtime; return r != null && !consentRevoked && r.flush(); }

    /** Non-destructive, explicitly consented event-only JSON export. Caller owns the output and sharing UI. */
    public static int exportPending(OutputStream output, boolean userConsented) throws IOException {
        if (!userConsented) throw new SecurityException("Export needs separate user consent");
        if (output == null) throw new IllegalArgumentException("Output required");
        RuntimeState r = active();
        r.flush();
        List<Incident> events = r.store.snapshot("local", 2049);
        byte[] bytes = IncidentProtocol.encodeEvents(events).getBytes(Redactor.UTF8);
        if (active() != r) throw new SecurityException("Consent revoked");
        output.write(bytes); output.flush();
        return events.size(); // Export NEVER acknowledges or consumes records.
    }

    /** Best-effort background nudges; adapters must also snapshot on startup, not rely on a live process. */
    public static void addListener(Runnable listener) {
        active();
        if (listener == null) throw new IllegalArgumentException("Listener required");
        if (listeners.size() >= 8) throw new IllegalStateException("Too many listeners");
        listeners.addIfAbsent(listener);
    }
    public static void removeListener(Runnable listener) { listeners.remove(listener); }

    /** Stop capture and transport immediately, then synchronously purge this process's retained private data. */
    public static synchronized void revokeConsent() {
        consentRevoked = true;
        RuntimeState old = runtime; runtime = null; listeners.clear();
        initializationFailure = "consent_revoked";
        ConsentState consent = old == null ? lastConsent : old.consent;
        if (consent != null) {
            try { consent.revoke(); }
            catch (IOException e) { initializationFailure = "consent_persistence_failed"; }
        }
        if (old != null) old.close(true);
    }

    private static RuntimeState active() {
        RuntimeState r = runtime;
        if (r == null || consentRevoked || !r.consent.allowed()) throw new IllegalStateException("Reliability is disabled or has no collection consent");
        return r;
    }

    private static String processName(Context context) throws Exception {
        try {
            Object value = Application.class.getMethod("getProcessName").invoke(null);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (ReflectiveOperationException ignored) {}
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object value = activityThread.getMethod("currentProcessName").invoke(null);
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (ReflectiveOperationException ignored) {}
        android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            List<android.app.ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo p : processes) {
                if (p.pid == android.os.Process.myPid() && p.processName != null) return p.processName;
            }
        }
        throw new IOException("Cannot safely isolate process journal");
    }

    private static String digest(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(Redactor.UTF8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }

    private static ThreadPoolExecutor executor(final String name, int capacity) {
        ThreadPoolExecutor e = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(capacity), new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) { Thread t = new Thread(runnable, name); t.setDaemon(true); return t; }
        });
        e.allowCoreThreadTimeOut(true);
        return e;
    }

    private static final class RuntimeState {
        final Context context;
        final Config config;
        final DiskJournal store;
        final ConsentState consent;
        final BinderSender sender;
        final ThreadPoolExecutor writer = executor("reliability-journal", 64);
        final ThreadPoolExecutor notifications = executor("reliability-notify", 1);
        volatile boolean stopped;
        Thread.UncaughtExceptionHandler previousHandler, installedHandler;
        Application application;
        Application.ActivityLifecycleCallbacks lifecycle;
        PrintStream previousOut, previousErr, installedOut, installedErr;

        RuntimeState(Context context, Config config, DiskJournal store, ConsentState consent) {
            this.context = context; this.config = config; this.store = store; this.consent = consent;
            sender = new BinderSender(context, store);
        }
        void install() {
            if (config.captureCrashes) installCrash();
            if (config.captureLifecycle && context instanceof Application) installLifecycle((Application) context);
            if (config.teeStdout) installStdio();
            if (config.capturePreviousExitInfo) writer.execute(new Runnable() { @Override public void run() { previousExits(); } });
            if (config.captureLifecycle) enqueue(store.prepare(IncidentProtocol.LIFECYCLE, "process_started"));
            sender.nudge();
        }
        void enqueue(final DiskJournal.Pending prepared) {
            if (stopped || !consent.allowed()) return;
            try { writer.execute(new Runnable() {
                @Override public void run() {
                    if (stopped || consentRevoked) return;
                    try { store.append(prepared); changed(); }
                    catch (IOException error) { store.noteDrop(); }
                }
            }); } catch (RejectedExecutionException full) { store.noteDrop(); }
        }
        void changed() {
            sender.nudge();
            if (listeners.isEmpty() || stopped) return;
            try { notifications.execute(new Runnable() { @Override public void run() {
                if (stopped || consentRevoked || !consent.allowed()) return;
                for (Runnable listener : listeners) { try { listener.run(); } catch (Throwable ignored) {} }
            } }); } catch (RejectedExecutionException coalesced) { /* Latest snapshot is recovered by the next nudge/start. */ }
        }
        boolean flush() {
            if (stopped || Thread.currentThread().getName().equals("reliability-journal")) return false;
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] success = { false };
            try {
                writer.execute(new Runnable() { @Override public void run() {
                    try { store.flushDrops(); success[0] = true; } catch (IOException ignored) {} finally { done.countDown(); }
                } });
                return done.await(2, TimeUnit.SECONDS) && success[0];
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            catch (RejectedExecutionException full) { return false; }
        }
        void installCrash() {
            previousHandler = Thread.getDefaultUncaughtExceptionHandler();
            installedHandler = new Thread.UncaughtExceptionHandler() {
                private final java.util.concurrent.atomic.AtomicBoolean handling = new java.util.concurrent.atomic.AtomicBoolean();
                @Override public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        if (!stopped && !consentRevoked && handling.compareAndSet(false, true)) {
                            store.appendFatal(store.prepare(IncidentProtocol.FATAL_JAVA, Redactor.exception(error)));
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (previousHandler != null) previousHandler.uncaughtException(thread, error);
                        else {
                            android.os.Process.killProcess(android.os.Process.myPid());
                            System.exit(10);
                        }
                    }
                }
            };
            Thread.setDefaultUncaughtExceptionHandler(installedHandler);
        }
        void installLifecycle(Application app) {
            application = app;
            lifecycle = new Application.ActivityLifecycleCallbacks() {
                private void capture(Activity activity, String transition) {
                    if (!stopped && !consentRevoked) {
                        try { enqueue(store.prepare(IncidentProtocol.LIFECYCLE, transition + " " + activity.getClass().getName())); }
                        catch (Throwable ignored) { store.noteDrop(); }
                    }
                }
                @Override public void onActivityCreated(Activity a, Bundle b) { capture(a, "created"); }
                @Override public void onActivityStarted(Activity a) { capture(a, "started"); sender.nudge(); }
                @Override public void onActivityResumed(Activity a) { capture(a, "resumed"); sender.nudge(); }
                @Override public void onActivityPaused(Activity a) { capture(a, "paused"); }
                @Override public void onActivityStopped(Activity a) { capture(a, "stopped"); }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { /* Never read Bundle/Intent state. */ }
                @Override public void onActivityDestroyed(Activity a) { capture(a, "destroyed"); }
            };
            app.registerActivityLifecycleCallbacks(lifecycle);
        }
        void previousExits() {
            if (stopped || consentRevoked) return;
            try {
                Object manager = context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager == null) return;
                Method history = manager.getClass().getMethod("getHistoricalProcessExitReasons", String.class, int.class, int.class);
                Object result = history.invoke(manager, context.getPackageName(), 0, 8);
                if (!(result instanceof List)) return;
                long watermark = store.exitWatermark(), latest = watermark;
                String ownProcess = processName(context);
                for (Object exit : (List<?>) result) {
                    if (stopped || consentRevoked) return;
                    Class<?> cls = exit.getClass();
                    String process = (String) cls.getMethod("getProcessName").invoke(exit);
                    if (!ownProcess.equals(process)) continue;
                    long timestamp = ((Number) cls.getMethod("getTimestamp").invoke(exit)).longValue();
                    if (timestamp <= watermark) continue;
                    int reason = ((Number) cls.getMethod("getReason").invoke(exit)).intValue();
                    int status = ((Number) cls.getMethod("getStatus").invoke(exit)).intValue();
                    int importance = ((Number) cls.getMethod("getImportance").invoke(exit)).intValue();
                    // No trace stream, description, PSS/RSS/user data; OS previous-exit metadata is NOT a Java crash capture.
                    store.append(store.incident(IncidentProtocol.PREVIOUS_EXIT, "exitTimestamp=" + timestamp + " reason=" + reason + " status=" + status + " importance=" + importance));
                    latest = Math.max(latest, timestamp);
                }
                if (latest > watermark) { store.exitWatermark(latest); changed(); }
            } catch (Exception | LinkageError unavailable) { /* API <30 or OS restriction: explicitly best-effort. */ }
        }
        void installStdio() {
            previousOut = System.out; previousErr = System.err;
            installedOut = new PrintStream(new Tee(previousOut, this), true);
            installedErr = new PrintStream(new Tee(previousErr, this), true);
            System.setOut(installedOut); System.setErr(installedErr);
        }
        void close(boolean purge) {
            stopped = true;
            sender.close(); writer.shutdownNow(); notifications.shutdownNow();
            if (application != null && lifecycle != null) application.unregisterActivityLifecycleCallbacks(lifecycle);
            if (installedHandler != null && Thread.getDefaultUncaughtExceptionHandler() == installedHandler) Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            if (installedOut != null && System.out == installedOut) System.setOut(previousOut);
            if (installedErr != null && System.err == installedErr) System.setErr(previousErr);
            try { if (purge) store.purge(); }
            catch (IOException ignored) { initializationFailure = "private_data_cleanup_failed"; }
            finally {
                try { store.close(); } catch (IOException ignored) { initializationFailure = "private_data_cleanup_failed"; }
            }
        }
    }

    private static final class Tee extends OutputStream {
        final PrintStream delegate;
        final RuntimeState owner;
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        boolean truncated;
        Tee(PrintStream delegate, RuntimeState owner) { this.delegate = delegate; this.owner = owner; }
        @Override public synchronized void write(int b) {
            delegate.write(b);
            if (b == '\n') {
                if (line.size() > 0) {
                    // A truncated line could cut a secret label from its value; discard it rather than emit fragments.
                    if (!truncated) owner.enqueue(owner.store.prepare(IncidentProtocol.STDOUT, new String(line.toByteArray(), Redactor.UTF8)));
                    else owner.store.noteDrop();
                    line.reset(); truncated = false;
                }
            } else if (b != '\r') {
                if (line.size() < 8192) line.write(b); else truncated = true;
            }
        }
        @Override public void flush() { delegate.flush(); }
    }
}
