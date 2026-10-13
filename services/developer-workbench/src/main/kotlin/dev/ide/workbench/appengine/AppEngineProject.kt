// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.workbench.appengine

/** Self-contained framework-only Android project. No IDE IPC, remote repository, or Gradle wrapper. */
internal object AppEngineProject {
    fun files(request: AppEnginePackageRequest): Map<String, String> {
        val pkg = request.applicationId
        val java = "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.java"
        return linkedMapOf(
            ".platform/workspace.json" to """
                {"version":1,"projects":[{"id":"engine-app","name":"Engine app","root":".","buildSystem":"native","settings":{},"modules":[{"id":"app","name":"app","dir":"app"}],"libraries":[{"name":"runtime-engine","kind":"JAR","classes":["libs/runtime-engine.jar"],"sources":[]}]}]}
            """.trimIndent() + "\n",
            ".platform/libraries.json" to "{\"version\":1,\"libraries\":[]}\n",
            ".platform/sdks.json" to "{\"version\":1,\"sdks\":[]}\n",
            "app/module.toml" to module(pkg),
            "app/proguard-rules.pro" to "-keep class dev.ide.runtime.engine.NativeBridge { *; }\n-keep class dev.ide.runtime.engine.RuntimeService { *; }\n",
            "app/src/main/AndroidManifest.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$pkg">
                    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
                    ${if (request.internetPermission) "<uses-permission android:name=\"android.permission.INTERNET\"/>" else "<!-- No network permission: fully bundled offline app. -->"}
                    <application android:label="@string/app_name" android:allowBackup="false"
                        android:extractNativeLibs="true" android:usesCleartextTraffic="false"
                        android:theme="@android:style/Theme.Material.Light.NoActionBar">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                        </activity>
                        <service android:name="dev.ide.runtime.engine.RuntimeService" android:exported="false" android:process=":runtime" android:stopWithTask="true"/>
                    </application>
                </manifest>
            """.trimIndent() + "\n",
            "app/src/main/res/values/strings.xml" to "<resources><string name=\"app_name\" translatable=\"false\" formatted=\"false\">\"${xml(request.label)}\"</string></resources>\n",
            java to activity(request),
        )
    }
    private fun module(pkg: String): String = buildString {
        append("""
            version = 2
            [module]
            type = "android-app"
            name = "app"
            languageLevel = "JAVA_8"
            output = "build/classes"

        """.trimIndent())
        listOf("main", "debug", "release").forEach { source ->
            append("""

                [sourceSets.$source]
                scope = "IMPLEMENTATION"
                java = ["src/$source/java"]
                res = ["src/$source/res"]
                assets = ["src/$source/assets"]
                jniLibs = ["src/$source/jniLibs"]
                resources = ["src/$source/resources"]
                aidl = ["src/$source/aidl"]

            """.trimIndent())
        }
        append("""

            [dependencies]
            implementation = ["runtime-engine"]

            [android]
            namespace = "$pkg"
            compileSdk = 36
            minSdk = 26
            targetSdk = 36
            manifest = "src/main/AndroidManifest.xml"
            versionCode = 1
            versionName = "1.0"
            isApplication = true
            manifestPlaceholders = []
            buildTypes = [{ name = "debug", debuggable = true, minifyEnabled = false, shrinkResources = false }, { name = "release", debuggable = false, minifyEnabled = false, shrinkResources = false }]

        """.trimIndent())
    }
    private fun xml(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun java(value: String): String = buildString {
        value.forEach { char ->
            append(when (char) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '\b' -> "\\b"
                '\u000c' -> "\\f"
                else -> if (char.code < 32 || char.code == 127) "\\" + char.code.toString(8).padStart(3, '0') else char.toString()
            })
        }
    }
    private fun argv(values: List<String>): String = if (values.isEmpty()) "Collections.<String>emptyList()"
        else values.joinToString(prefix = "java.util.Arrays.asList(", postfix = ")") { "\"${java(it)}\"" }

    private fun activity(request: AppEnginePackageRequest): String = """
        package ${request.applicationId};

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
        import java.util.Collections;

        /** Installed APK runs offline without CodeAssist. Recovery starts a NEW run only after a tap. */
        public final class MainActivity extends Activity {
            private EngineClient client;
            private TextView output, status;
            private Button start, stop, send;
            private EditText input;
            @Override public void onCreate(Bundle saved) {
                super.onCreate(saved);
                LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(24, 24, 24, 24);
                status = new TextView(this); status.setText("Bundled ${request.engine.id} app — ready offline"); layout.addView(status);
                start = new Button(this); start.setText("Run / restart"); layout.addView(start);
                stop = new Button(this); stop.setText("Stop"); stop.setEnabled(false); layout.addView(stop);
                input = new EditText(this); input.setHint("Standard input"); layout.addView(input);
                send = new Button(this); send.setText("Send input"); send.setEnabled(false); layout.addView(send);
                Button eof = new Button(this); eof.setText("Close input"); layout.addView(eof);
                ScrollView scroll = new ScrollView(this); output = new TextView(this); output.setTextIsSelectable(true);
                output.setTypeface(android.graphics.Typeface.MONOSPACE); scroll.addView(output);
                layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(layout);
                client = new EngineClient(this, new EngineClient.Listener() {
                    @Override public void onState(EngineSnapshot state) {
                        status.setText(state.phase.name() + " pid=" + state.processId + " exit=" + state.exitCode + " " + state.error);
                        output.setText(state.stdout + "\n[stderr]\n" + state.stderr
                            + (state.stdoutTruncated || state.stderrTruncated ? "\n[older output discarded]" : ""));
                        start.setEnabled(state.isTerminal()); stop.setEnabled(!state.isTerminal()); send.setEnabled(state.acceptsInput);
                    }
                    @Override public void onInputAccepted(int bytes) {
                        if (bytes > 0) input.setText("");
                        else status.setText(bytes == 0 ? "Input busy — retry" : "Input is closed");
                    }
                });
                start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    client.start(new EngineRequest(Engine.${request.engine.id.uppercase()}, "${java(request.entry)}", ${argv(request.arguments)}, 60000, 65536, ${request.allowUnqualifiedNode}));
                }});
                stop.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { client.stop(); }});
                send.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
                    byte[] bytes = (input.getText().toString() + "\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 4096) status.setText("Input is limited to 4096 UTF-8 bytes");
                    else if (!client.writeInput(bytes)) status.setText("Input busy — retry");
                }});
                eof.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { client.closeInput(); }});
            }
            @Override protected void onDestroy() { if (client != null) client.close(); super.onDestroy(); }
        }
    """.trimIndent() + "\n"
}
