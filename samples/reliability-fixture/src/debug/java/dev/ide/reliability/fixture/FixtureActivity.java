package dev.ide.reliability.fixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.ide.applog.Incident;
import dev.ide.applog.Reliability;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** An ordinary Android Activity. Only a separately installed instrumentation controller kills it. */
public final class FixtureActivity extends Activity {
    public static final int EDITOR_ID = 0x12340001;
    public static final int SAVE_ID = 0x12340002;
    public static final int SELECT_ID = 0x12340003;
    public static final int REPORT_ID = 0x12340004;
    private DraftEditor editor;
    private CheckBox selectSecond;
    private TextView report;
    private FixtureStore store;
    private boolean ready;
    private int savedStateCallbacks;
    private long checkpointSequence;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = ((FixtureApplication) getApplication()).store();
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        column.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this); scroll.addView(column); setContentView(scroll);
        TextView title = new TextView(this); title.setText("Reliability test fixture"); column.addView(title);
        Button seed = button("Seed disposable user data", column);
        seed.setOnClickListener(v -> { store.seed(); loadDraft(); writeCheckpoint(); });
        editor = new DraftEditor(); editor.setId(EDITOR_ID); editor.setSaveEnabled(false);
        editor.setContentDescription("Fixture draft"); editor.setMinLines(3); column.addView(editor);
        selectSecond = new CheckBox(this); selectSecond.setId(SELECT_ID); selectSecond.setSaveEnabled(false);
        selectSecond.setText("Select second record"); column.addView(selectSecond);
        Button save = button("Save record", column); save.setId(SAVE_ID);
        save.setOnClickListener(v -> { store.saveRecord(currentDraft()); writeCheckpoint(); });
        Button checkpoint = button("Checkpoint disk state", column);
        checkpoint.setOnClickListener(v -> { checkpointDraft(); writeCheckpoint(); });
        Button inspect = button("Inspect durable SDK queue", column);
        inspect.setOnClickListener(v -> writeCheckpoint());
        Button backup = button("Export / restore selected user data", column);
        backup.setOnClickListener(v -> {
            checkpointDraft();
            startActivity(new Intent(this, FixtureBackupActivity.class));
        });
        Button crash = button("Crash fixture process", column);
        crash.setOnClickListener(v -> {
            checkpointDraft(); writeCheckpoint();
            // Deliberately uncaught in the ordinary app main thread. Calling recordException() is NOT
            // crash-handler proof; this goes through Android's previous default uncaught handler.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                throw new IllegalStateException("FixtureUncaughtCrashMarker");
            }, 150);
        });
        report = new TextView(this); report.setId(REPORT_ID); report.setTextIsSelectable(true); column.addView(report);
        loadDraft();
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { checkpointDraft(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        selectSecond.setOnCheckedChangeListener((button, checked) -> checkpointDraft());
        writeCheckpoint();
    }

    private Button button(String label, LinearLayout column) {
        Button button = new Button(this); button.setText(label); column.addView(button); return button;
    }
    private void loadDraft() {
        ready = false;
        FixtureStore.Draft draft = store.draft();
        editor.setText(draft.body); editor.setSelection(draft.start, draft.end);
        selectSecond.setChecked(draft.selectedId == 2); ready = true;
    }
    private FixtureStore.Draft currentDraft() {
        int start = Math.max(0, editor.getSelectionStart()), end = Math.max(0, editor.getSelectionEnd());
        return new FixtureStore.Draft(editor.getText().toString(), Math.min(start, end), Math.max(start, end),
                selectSecond.isChecked() ? 2 : 1);
    }
    private void checkpointDraft() { if (ready) DraftPersistence.checkpoint(store, currentDraft()); }
    @Override protected void onPause() { checkpointDraft(); super.onPause(); }
    @Override protected void onStop() { checkpointDraft(); writeCheckpoint(); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        checkpointDraft(); savedStateCallbacks++; super.onSaveInstanceState(state);
    }
    public int savedStateCallbacks() { return savedStateCallbacks; }
    public FixtureStore store() { return store; }
    public EditText editor() { return editor; }
    public CheckBox selectedRecord() { return selectSecond; }

    private void writeCheckpoint() {
        if (report == null) return;
        try {
            store.checkpointDisk(); Reliability.flush();
            FixtureStore.Draft draft = store.draft();
            JSONObject object = new JSONObject().put("uid", Process.myUid()).put("pid", Process.myPid())
                    .put("checkpointSequence", ++checkpointSequence)
                    .put("versionCode", BuildConfig.VERSION_CODE).put("schema", BuildConfig.DATA_SCHEMA)
                    .put("body", draft.body).put("selectionStart", draft.start).put("selectionEnd", draft.end)
                    .put("selectedId", draft.selectedId).put("recordCount", store.recordCount())
                    .put("savedRecord", store.recordBody(1)).put("seedUserData", store.hasSeedUserData());
            JSONArray pending = new JSONArray();
            for (Incident incident : Reliability.journal("fixture-observer").snapshotPending(256))
                pending.put(new JSONObject(incident.toJson()));
            object.put("pending", pending);
            JSONArray localPending = new JSONArray();
            for (Incident incident : Reliability.journal().snapshotPending(256)) localPending.put(incident.eventId);
            object.put("localPendingIds", localPending);
            byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
            File temporary = new File(getFilesDir(), "fixture-checkpoint.tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
            File checkpoint = new File(getFilesDir(), "fixture-checkpoint.json");
            android.system.Os.rename(temporary.getAbsolutePath(), checkpoint.getAbsolutePath());
            // This is an explicit test-only domain checkpoint. No nonce, signer, request body, or SDK
            // credentials are included. Shell run-as is used only for this authorized fixture package.
            report.setText("Checkpoint " + Process.myPid() + ": " + draft.body + " [" + draft.start + "," + draft.end + "]");
        } catch (Exception error) { throw new IllegalStateException("Fixture checkpoint failed", error); }
    }

    private final class DraftEditor extends EditText {
        DraftEditor() { super(FixtureActivity.this); }
        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end); checkpointDraft();
        }
    }
}
