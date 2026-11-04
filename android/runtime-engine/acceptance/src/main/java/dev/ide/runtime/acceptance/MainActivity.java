// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.acceptance;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.ide.runtime.engine.Engine;
import dev.ide.runtime.engine.EngineClient;
import dev.ide.runtime.engine.EngineRequest;
import dev.ide.runtime.engine.EngineSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actual embedding acceptance app: every result below is received over Binder from the native engine. */
public final class MainActivity extends Activity {
    private EngineClient client;
    private TextView status, output;
    private EditText input;
    private final List<Button> runs = new ArrayList<Button>();
    private Button stop, send;
    private Engine selected;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        selected = "node".equals(getString(getResources().getIdentifier("engine_id", "string", getPackageName()))) ? Engine.NODE : Engine.PYTHON;
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(20, 20, 20, 20);
        TextView title = new TextView(this); title.setText("Standalone " + selected.id + " embedding / no IDE"); column.addView(title);
        status = new TextView(this); status.setText("Ready offline; run a real native engine test"); column.addView(status);
        LinearLayout row1 = row(column), row2 = row(column), row3 = row(column);
        addRun(row1, "Calculate + persist", "compute", 15000);
        addRun(row1, "Echo stdin", "echo", 30000);
        addRun(row2, "Flood output", "flood", 15000);
        addRun(row2, "Script exception", "exception", 15000);
        addRun(row3, "Crash runtime", "crash", 15000);
        addRun(row3, "Timeout loop", "loop", 3000);
        stop = button(column, "Stop", new View.OnClickListener() { @Override public void onClick(View v) { client.stop(); }}); stop.setEnabled(false);
        input = new EditText(this); input.setHint("Input line"); column.addView(input);
        LinearLayout inputRow = row(column);
        send = button(inputRow, "Send", new View.OnClickListener() { @Override public void onClick(View v) {
            byte[] bytes = (input.getText().toString() + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 4096) status.setText("Input too long");
            else if (!client.writeInput(bytes)) status.setText("Input busy; retry");
        }}); send.setEnabled(false);
        button(inputRow, "EOF", new View.OnClickListener() { @Override public void onClick(View v) { client.closeInput(); }});
        ScrollView scroll = new ScrollView(this); output = new TextView(this); output.setTextIsSelectable(true);
        output.setTypeface(android.graphics.Typeface.MONOSPACE); output.setTextSize(12); scroll.addView(output);
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(column);
        client = new EngineClient(this, new EngineClient.Listener() {
            @Override public void onState(EngineSnapshot state) {
                status.setText(state.phase.name() + " pid=" + state.processId + " exit=" + state.exitCode
                        + " bytes=" + state.stdoutBytes + "/" + state.stderrBytes + " " + state.error);
                for (Button run : runs) run.setEnabled(state.isTerminal());
                stop.setEnabled(!state.isTerminal()); send.setEnabled(state.acceptsInput);
                output.setText(state.stdout + "\n[stderr]\n" + state.stderr + "\ntruncated=" + state.stdoutTruncated + "/" + state.stderrTruncated);
            }
            @Override public void onInputAccepted(int count) {
                if (count > 0) input.setText(""); else status.setText(count == 0 ? "Input busy; retry" : "Input closed");
            }
        });
    }
    private void addRun(LinearLayout parent, String label, final String entry, final long timeout) {
        runs.add(button(parent, label, new View.OnClickListener() { @Override public void onClick(View v) {
            client.start(new EngineRequest(selected, entry + (selected == Engine.PYTHON ? ".py" : ".js"),
                    Collections.<String>emptyList(), timeout, 8192, selected == Engine.NODE));
        }}));
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); parent.addView(row); return row;
    }
    private Button button(LinearLayout parent, String label, View.OnClickListener action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(action);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) parent.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        else parent.addView(button);
        return button;
    }
    @Override protected void onDestroy() { if (client != null) client.close(); super.onDestroy(); }
}
