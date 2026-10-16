package ai.building.loader;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.google.android.play.core.splitinstall.testing.FakeSplitInstallManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Platform-only runner: drives accessibility on the actual optimized release.
 * No shared AndroidX/Kotlin test dependency can be removed from beneath this APK by R8.
 */
public class ProofRunner extends Instrumentation {
    private String phase;
    private android.app.Activity activity;
    @Override public void onCreate(Bundle args) { super.onCreate(args); phase = args.getString("phase"); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            switch (phase) {
                case "stageOneOfflineEditingAndSafSave": stageOne(); break;
                case "stageOneColdRestartOffline": stageOneRestart(); break;
                case "failedAndCanceledDownloadPreservesEditor": failedAndCanceled(); break;
                case "stageTwoDownloadAndRunEditedSource": stageTwo(); break;
                case "stageTwoColdRestartOffline": stageTwoRestart(); break;
                case "newDocumentOffline": newDocument(); break;
                default: throw new AssertionError("Unknown phase: " + phase);
            }
            result.putString("stream", "\nOK (1 test)\n" + phase + "\n"); finish(-1, result);
        } catch (Throwable error) {
            capture();
            result.putString("stream", "\nFAILED: " + phase + "\n" + android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String kind, String value) {
        if (root == null) return null;
        if (kind.equals("editable") && root.isEditable()) return root;
        CharSequence actual = kind.equals("id") ? root.getViewIdResourceName()
            : kind.equals("desc") ? root.getContentDescription() : root.getText();
        if (actual != null && (kind.equals("contains") ? actual.toString().contains(value) : actual.toString().equals(value))) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = find(root.getChild(i), kind, value);
            if (found != null) return found;
        }
        return null;
    }
    private AccessibilityNodeInfo waitFor(String kind, String value, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo found = find(getUiAutomation().getRootInActiveWindow(), kind, value);
            if (found != null) return found;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Missing " + kind + ": " + value);
    }
    private AccessibilityNodeInfo view(String id) { return waitFor("id", id, 10000); }
    private void clickNode(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
        }
        throw new AssertionError("Not clickable: " + node);
    }
    private void click(String id) { clickNode(view(id)); SystemClock.sleep(200); }
    private void expect(String id, String text, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        do {
            AccessibilityNodeInfo node = find(getUiAutomation().getRootInActiveWindow(), "id", id);
            if (node != null && find(node, "contains", text) != null) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError(id + " should contain " + text);
    }
    private void expect(String id, String text) { expect(id, text, 20000); }
    private void setText(String id, String value) {
        AccessibilityNodeInfo node = view(id);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Cannot edit " + id);
        SystemClock.sleep(250);
    }
    private void type(String value) { setText("editor", value); }
    private void launch() {
        ActivityMonitor monitor = addMonitor("ai.building.loader.EditorActivity", null, false);
        Intent intent = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
        getTargetContext().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity = monitor.waitForActivityWithTimeout(10000);
        removeMonitor(monitor);
        require(activity != null, "Editor activity did not launch");
        view("editor");
    }
    // SplitInstallManagerFactory creates its own local-testing instance. Inject faults
    // into the instance actually driving the UI, not a second standalone fake.
    private FakeSplitInstallManager uiManager() throws Exception {
        var queue = new java.util.ArrayDeque<java.util.Map.Entry<Object, Integer>>();
        var seen = new java.util.IdentityHashMap<Object, Boolean>();
        queue.add(new java.util.AbstractMap.SimpleImmutableEntry<>(activity, 0));
        while (!queue.isEmpty() && seen.size() < 1000) {
            var item = queue.removeFirst(); Object object = item.getKey();
            if (object instanceof FakeSplitInstallManager) return (FakeSplitInstallManager) object;
            if (seen.put(object, true) != null || item.getValue() >= 6 ||
                object.getClass().getClassLoader() != activity.getClass().getClassLoader()) continue;
            for (var field : object.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                field.setAccessible(true);
                Object value = field.get(object);
                if (value != null) queue.add(new java.util.AbstractMap.SimpleImmutableEntry<>(value, item.getValue() + 1));
            }
        }
        throw new AssertionError("No local-testing manager in UI graph (" + seen.size() + " objects)");
    }
    private void offline() {
        require(getTargetContext().getSystemService(ConnectivityManager.class).getActiveNetwork() == null, "Active network exists");
    }
    private void capture() {
        try {
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            try (OutputStream out = new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null), phase + ".png"))) {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception ignored) { }
    }
    private void home() { capture(); sendKeyDownUpSync(KeyEvent.KEYCODE_HOME); SystemClock.sleep(500); }
    private String read(Uri uri) throws Exception {
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return bytes.toString("UTF-8");
        }
    }
    private void stageOne() throws Exception {
        offline();
        boolean absent = false;
        try { getTargetContext().getClassLoader().loadClass("ai.building.loader.node.NodeRuntime"); }
        catch (ClassNotFoundException expected) { absent = true; }
        require(absent, "Feature implementation leaked into base");
        absent = false;
        try (InputStream ignored = getTargetContext().getAssets().open("node-runtime/manifest.json")) { }
        catch (java.io.FileNotFoundException expected) { absent = true; }
        require(absent, "Runtime payload leaked into base");
        launch();
        String filename = "building-loader-proof-" + System.currentTimeMillis() + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/");
        Uri fixture = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        try (OutputStream out = getContext().getContentResolver().openOutputStream(fixture)) {
            out.write("console.log('original');\n".getBytes(StandardCharsets.UTF_8));
        }
        click("open");
        clickNode(waitFor("desc", "Show roots", 10000));
        clickNode(waitFor("text", "Downloads", 10000));
        clickNode(waitFor("text", filename, 10000));
        expect("filename", filename); expect("editor", "original");
        type("console.log('stage-one-offline ✓');\n");
        click("undo"); expect("editor", "original");
        click("redo"); expect("editor", "stage-one-offline");
        click("search"); setText("query", "stage-one-offline"); click("find-next"); expect("message", "Match selected");
        click("save"); expect("message", "Saved");
        require(read(fixture).equals("console.log('stage-one-offline ✓');\n"), "Saved bytes differ");
        require(getTargetContext().getSharedPreferences("proof", Context.MODE_PRIVATE).edit().putString("fixture", fixture.toString()).commit(), "Cannot persist test fixture URI");
        click("settings"); click("runtime-enabled"); click("settings-done"); expect("delivery-status", "Disabled");
        home();
    }
    private void stageOneRestart() throws Exception {
        offline(); launch(); expect("editor", "stage-one-offline ✓"); expect("delivery-status", "Disabled");
        type("console.log('cold-save ✓');\n"); click("save"); expect("message", "Saved");
        Uri fixture = Uri.parse(getTargetContext().getSharedPreferences("proof", Context.MODE_PRIVATE).getString("fixture", ""));
        require(read(fixture).equals("console.log('cold-save ✓');\n"), "Cold save bytes differ");
        click("settings"); click("runtime-enabled"); click("settings-done"); home();
    }
    private void failedAndCanceled() throws Exception {
        launch();
        var manager = uiManager();
        manager.setShouldNetworkError(true);
        try { click("download"); expect("delivery-status", "failed", 30000); }
        finally { manager.setShouldNetworkError(false); }
        expect("editor", "cold-save"); click("download"); click("cancel"); expect("delivery-status", "canceled", 30000);
        require(!manager.getInstalledModules().contains("node_runtime"), "Cancellation installed the runtime");
        expect("editor", "cold-save"); home();
    }
    private void stageTwo() {
        launch(); click("download"); waitFor("id", "run", 90000);
        type("const {spawnSync} = require('child_process');\n" +
            "const child = spawnSync(process.execPath, ['-e', \"console.log('child-ok')\"], {encoding:'utf8'});\n" +
            "if(child.status !== 0) throw new Error(child.stderr || String(child.error));\n" +
            "console.log('stage-two-node-' + process.versions.node);\nconsole.log(child.stdout.trim());\n");
        click("run"); expect("output", "Exit 0", 45000); expect("output", "stage-two-node-"); expect("output", "child-ok");
        type("console.log('edited-again-' + (6 * 7));\n"); click("run"); expect("output", "edited-again-42", 45000); home();
    }
    private void stageTwoRestart() {
        offline(); launch(); expect("editor", "edited-again-"); click("run"); expect("output", "Exit 0", 45000);
        expect("output", "edited-again-42");
        click("settings"); click("runtime-enabled"); click("settings-done"); require(!view("run").isEnabled(), "Runtime switch did not disable Run");
        expect("delivery-status", "Disabled");
        click("settings"); click("runtime-enabled"); click("settings-done"); click("run"); expect("output", "edited-again-42", 45000); home();
    }
    private void newDocument() throws Exception {
        offline(); launch();
        java.util.Set<Uri> before = new java.util.HashSet<>();
        for (var permission : getTargetContext().getContentResolver().getPersistedUriPermissions()) before.add(permission.getUri());
        click("new"); click("discard"); expect("filename", "untitled.js");
        String source = "console.log('created-offline ✓');\n";
        type(source); click("save");
        AccessibilityNodeInfo name = waitFor("editable", "", 10000);
        String filename = "loader-created-" + System.currentTimeMillis() + ".js";
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename);
        require(name.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args), "Cannot name the new file");
        clickNode(waitFor("id", "android:id/button1", 10000));
        expect("message", "Saved " + filename);
        require(view("filename").getText().toString().equals(filename), "Provider changed source extension");
        Uri created = null;
        for (var permission : getTargetContext().getContentResolver().getPersistedUriPermissions()) {
            if (!before.contains(permission.getUri())) created = permission.getUri();
        }
        require(created != null, "New document permission was not persisted");
        try (var input = getTargetContext().getContentResolver().openInputStream(created)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            require(bytes.toString("UTF-8").equals(source), "New document bytes differ");
        }
        click("run"); expect("output", "created-offline ✓", 45000); home();
    }
}
