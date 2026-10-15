package dev.ide.reliability.fixture;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.ide.applog.backup.BackupKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real user-operated transfer for this cooperating debug app only, not a cross-app backup manager. */
public final class FixtureBackupActivity extends Activity {
    public static final int EXPORT_ID = 0x12340101, IMPORT_ID = 0x12340102, PASSPHRASE_ID = 0x12340103;
    public static final int CONFIRM_ID = 0x12340104, CONSENT_ID = 0x12340105, START_ID = 0x12340106;
    public static final int STATUS_ID = 0x12340107, CANCEL_ID = 0x12340108;
    private static final int EXPORT = 41, RESTORE = 42;
    // Application-scoped operation ownership also covers leaving/reopening this screen before cancelled
    // provider I/O has drained. A second Activity must never clean another worker's private staging.
    private static Session processSession;
    private Session session;
    private Button export, restore, start, cancel;
    private EditText passphrase, confirmation;
    private CheckBox consent;
    private TextView status;
    private LinearLayout approval;
    private ProgressBar progress;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        session = (Session) getLastNonConfigurationInstance();
        if (session == null) session = processSession;
        if (session == null) {
            session = new Session(getApplicationContext());
            if (savedState != null) {
                session.pendingPicker = savedState.getInt("backupPicker", 0);
                session.message = savedState.getBoolean("backupRunning", false)
                        ? "The process stopped during transfer. Re-select your file. A restore is atomic; an export may be incomplete."
                        : "Select a file again; passphrases and file permissions are never saved by this screen.";
            }
        }
        processSession = session;
        session.activity = this;
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);
        text(column, "Export / restore this app's user data").setTextSize(22);
        text(column, "Participating debug fixture only. Includes saved records, notes.txt, sort and fontScale. "
                + "Drafts, other attachments/settings, credentials, SDK logs and signing keys are excluded. "
                + "No other app's private data is accessed.");
        text(column, "Choose a local file through Android's document picker. Transfer the encrypted archive to another "
                + "installation yourself, and supply the same strong passphrase there. Keep the passphrase separately: "
                + "there is no account recovery. An incomplete export file must be discarded.");
        export = button(column, EXPORT_ID, "Choose new backup file");
        restore = button(column, IMPORT_ID, "Choose backup to restore");
        export.setOnClickListener(v -> chooseDocument(EXPORT));
        restore.setOnClickListener(v -> chooseDocument(RESTORE));
        approval = new LinearLayout(this);
        approval.setOrientation(LinearLayout.VERTICAL);
        column.addView(approval);
        passphrase = secret(approval, PASSPHRASE_ID, "Strong passphrase (12–1024 characters)");
        confirmation = secret(approval, CONFIRM_ID, "Confirm export passphrase");
        consent = new CheckBox(this);
        consent.setId(CONSENT_ID);
        consent.setSaveEnabled(false);
        consent.setFilterTouchesWhenObscured(true);
        approval.addView(consent);
        start = button(approval, START_ID, "Continue");
        start.setOnClickListener(v -> startTransfer());
        progress = new ProgressBar(this);
        column.addView(progress);
        cancel = button(column, CANCEL_ID, "Cancel transfer");
        cancel.setOnClickListener(v -> { session.cancel(); render(); });
        status = text(column, "");
        status.setId(STATUS_ID);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        render();
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(this);
        view.setText(value);
        parent.addView(view);
        return view;
    }
    private Button button(LinearLayout parent, int id, String label) {
        Button view = new Button(this);
        view.setId(id);
        view.setText(label);
        view.setFilterTouchesWhenObscured(true);
        parent.addView(view);
        return view;
    }
    private EditText secret(LinearLayout parent, int id, String hint) {
        EditText view = new EditText(this);
        view.setId(id);
        view.setHint(hint);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        view.setSingleLine(true);
        view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        view.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
        view.setFilters(new InputFilter[] { new InputFilter.LengthFilter(1024) });
        parent.addView(view);
        return view;
    }

    private void chooseDocument(int operation) {
        if (session.running || session.pendingPicker != 0) return;
        clearSecrets();
        session.document = null;
        session.operation = 0;
        Intent intent = new Intent(operation == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(operation == EXPORT ? "application/octet-stream" : "*/*");
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(operation == EXPORT ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (operation == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "fixture-user-data.idebackup");
        session.pendingPicker = operation;
        session.message = "Choose a local document. Nothing is exported or restored until you confirm below.";
        try { startActivityForResult(intent, operation); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            session.pendingPicker = 0;
            session.message = "The Android document picker is unavailable. No app data was changed.";
        }
        render();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != session.pendingPicker || (requestCode != EXPORT && requestCode != RESTORE)) return;
        session.pendingPicker = 0;
        clearSecrets();
        Uri uri = data == null ? null : data.getData();
        int grant = requestCode == EXPORT ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (resultCode != RESULT_OK || uri == null) {
            session.message = "File selection cancelled. No app data was changed.";
        } else if (!"content".equals(uri.getScheme()) || (data.getFlags() & grant) == 0) {
            session.message = "Choose a document with the required temporary permission. No app data was changed.";
        } else {
            session.document = uri;
            session.operation = requestCode;
            session.message = requestCode == EXPORT
                    ? "New file selected. Enter and confirm your passphrase. A cancelled export can leave an empty or partial file."
                    : "Archive selected. Enter its passphrase and explicitly approve replacing this app's selected user data.";
        }
        render();
    }

    private void startTransfer() {
        if (session.running || session.document == null || !consent.isChecked()) {
            session.message = "Select a file and explicitly check the consent box first.";
            render();
            return;
        }
        char[] secret = characters(passphrase), repeated = characters(confirmation);
        BackupKey key = null;
        try {
            if (session.operation == EXPORT && !Arrays.equals(secret, repeated)) {
                session.message = "The passphrases do not match. Enter both again.";
                return;
            }
            key = BackupKey.passphrase(secret);
            session.start(key);
            key = null; // Worker owns and clears the key, including failure/cancellation.
        } catch (IllegalArgumentException invalid) {
            session.message = "Use a strong passphrase of 12–1024 characters. It is not stored or recoverable.";
        } finally {
            if (key != null) key.close();
            Arrays.fill(secret, '\0');
            Arrays.fill(repeated, '\0');
            clearSecrets();
            render();
        }
    }

    private static char[] characters(EditText view) {
        Editable text = view.getText();
        char[] result = new char[text.length()];
        text.getChars(0, text.length(), result, 0);
        return result;
    }
    private void clearSecrets() {
        if (passphrase != null) passphrase.getText().clear();
        if (confirmation != null) confirmation.getText().clear();
        if (consent != null) consent.setChecked(false);
    }
    private void render() {
        boolean idle = !session.running && session.pendingPicker == 0;
        export.setEnabled(idle);
        restore.setEnabled(idle);
        approval.setVisibility(idle && session.document != null ? View.VISIBLE : View.GONE);
        confirmation.setVisibility(session.operation == EXPORT ? View.VISIBLE : View.GONE);
        consent.setText(session.operation == EXPORT
                ? "I saved this passphrase separately and consent to exporting the selected user data."
                : "Replace all saved records, notes.txt, sort and fontScale here. Keep my draft and unselected local data.");
        start.setText(session.operation == EXPORT ? "Encrypt and export selected data" : "Restore and replace selected data");
        progress.setVisibility(session.running ? View.VISIBLE : View.GONE);
        cancel.setVisibility(session.running ? View.VISIBLE : View.GONE);
        cancel.setEnabled(session.running && !session.cancelled.get());
        status.setText(session.message);
    }

    @Override public Object onRetainNonConfigurationInstance() { return session; }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("backupPicker", session.pendingPicker);
        state.putBoolean("backupRunning", session.running);
        super.onSaveInstanceState(state); // Secret fields opt out of view-state persistence.
    }
    @Override protected void onPause() { clearSecrets(); super.onPause(); }
    @Override protected void onDestroy() {
        clearSecrets();
        boolean ownsScreen = session.activity == this;
        if (ownsScreen) session.activity = null;
        if (!isChangingConfigurations() && ownsScreen) {
            session.document = null;
            session.operation = 0;
            session.pendingPicker = 0;
            session.cancel();
            if (!session.running && processSession == session) processSession = null;
        }
        super.onDestroy();
    }

    /** One operation survives Activity rotation, without retaining the old Activity or persisting secrets. */
    private static final class Session {
        final Context context;
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean cancelled = new AtomicBoolean();
        FixtureBackupActivity activity;
        Uri document;
        int operation, pendingPicker;
        boolean running;
        CancellationSignal providerCancellation;
        Thread worker;
        String message = "No file selected. Export and restore require your explicit consent.";
        Session(Context context) { this.context = context; }

        void start(final BackupKey key) {
            final Uri selected = document;
            final int action = operation;
            final CancellationSignal signal = new CancellationSignal();
            providerCancellation = signal;
            cancelled.set(false);
            running = true;
            document = null;
            operation = 0;
            message = action == EXPORT ? "Encrypting and exporting selected data…" : "Authenticating archive before restoring…";
            worker = new Thread(() -> transfer(selected, action, signal, key), "fixture-backup-transfer");
            worker.start();
        }
        void cancel() {
            if (!running) return;
            cancelled.set(true);
            message = "Cancellation requested. Waiting for provider I/O or the atomic commit boundary…";
            providerCancellation.cancel();
            worker.interrupt();
        }
        void transfer(Uri selected, int action, CancellationSignal signal, BackupKey supplied) {
            FixtureBackup adapter = new FixtureBackup();
            String result;
            try (BackupKey key = supplied) {
                Path staging = context.getCacheDir().toPath().resolve("fixture-document-transfer");
                Files.createDirectories(staging);
                clearInterruptedStaging(staging);
                FixtureStore store = ((FixtureApplication) context).store();
                ContentResolver resolver = context.getContentResolver();
                if (action == EXPORT) {
                    adapter.exportDocument(store, () -> output(resolver, selected, signal), staging, key, cancelled::get);
                    result = "Encrypted backup exported. Transfer the archive and passphrase separately. No relay upload was performed.";
                } else {
                    adapter.restoreDocument(store, () -> input(resolver, selected, signal), staging, key, cancelled::get);
                    result = "Restore committed: saved records, notes.txt, sort and fontScale replaced. Draft and unselected local data kept.";
                }
            } catch (Exception failure) {
                // Never log provider errors/URIs, passphrases or decrypted data. A post-commit cleanup error
                // must not be misreported as a rolled-back restore.
                if (adapter.restoreCommitted()) {
                    result = "Restore committed, but private temporary-file cleanup failed. Unselected local data was kept.";
                } else if (action == EXPORT) {
                    result = (cancelled.get() ? "Export cancelled." : "Export failed.")
                            + " App data was not changed. Discard any empty or incomplete destination file and try a new file.";
                } else {
                    result = (cancelled.get() ? "Restore cancelled before commit." : "Restore failed: check the passphrase, archive and file access.")
                            + " Existing app data was not changed.";
                }
            }
            final String outcome = result;
            main.post(() -> {
                running = false;
                worker = null;
                providerCancellation = null;
                message = outcome;
                if (activity != null) activity.render();
                else if (processSession == this) processSession = null;
            });
        }

        private static InputStream input(ContentResolver resolver, Uri uri, CancellationSignal signal) throws IOException {
            AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "r", signal);
            if (descriptor == null) throw new IOException("No document input");
            try { return descriptor.createInputStream(); }
            catch (IOException | RuntimeException failed) { descriptor.close(); throw failed; }
        }
        private static OutputStream output(ContentResolver resolver, Uri uri, CancellationSignal signal) throws IOException {
            AssetFileDescriptor descriptor = resolver.openAssetFileDescriptor(uri, "wt", signal);
            if (descriptor == null) throw new IOException("No document output");
            try { return descriptor.createOutputStream(); }
            catch (IOException | RuntimeException failed) { descriptor.close(); throw failed; }
        }
        private static void clearInterruptedStaging(Path staging) throws IOException {
            if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private staging unavailable");
            // This dedicated cache contains only this single worker's SDK-generated files, never user
            // paths. A prior process death can leave plaintext staging; remove it before the next use.
            // Files.walk does not follow symbolic links, including any unexpected child entry.
            try (java.util.stream.Stream<Path> paths = Files.walk(staging)) {
                Path[] previous = paths.filter(path -> !path.equals(staging)).sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : previous) Files.delete(path);
            }
        }
    }
}
