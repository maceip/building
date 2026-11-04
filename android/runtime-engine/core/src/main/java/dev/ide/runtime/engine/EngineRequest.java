// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

import android.os.Bundle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An app-bundled entry point, not CLI options or an arbitrary interpreter command. */
public final class EngineRequest {
    public static final int MAX_ARGUMENT_BYTES = 32768;
    public final Engine engine;
    public final String entry;
    public final List<String> arguments;
    public final long timeoutMillis;
    public final int outputLimitBytes;
    public final boolean allowUnqualifiedNode;

    public EngineRequest(Engine engine, String entry, List<String> arguments,
                         long timeoutMillis, int outputLimitBytes, boolean allowUnqualifiedNode) {
        if (engine == null || arguments == null) throw new IllegalArgumentException("Engine and arguments required");
        Paths.relative(entry);
        if (!entry.endsWith(engine == Engine.PYTHON ? ".py" : ".js")
                && !(engine == Engine.NODE && (entry.endsWith(".mjs") || entry.endsWith(".cjs")))) {
            throw new IllegalArgumentException("Entry extension does not match engine");
        }
        if (timeoutMillis < 100 || timeoutMillis > 86400000L) throw new IllegalArgumentException("Timeout must be 100ms..24h");
        if (outputLimitBytes < 1024 || outputLimitBytes > 262144) throw new IllegalArgumentException("Output limit must be 1..256 KiB per stream");
        if (engine.requiresExperimentalConsent && !allowUnqualifiedNode)
            throw new IllegalArgumentException("Node Android is unqualified upstream; explicit consent required");
        if (arguments.size() > 128) throw new IllegalArgumentException("Too many arguments");
        int bytes = 0;
        ArrayList<String> copy = new ArrayList<String>();
        for (String argument : arguments) {
            if (argument == null || argument.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid argument");
            bytes += argument.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_ARGUMENT_BYTES) throw new IllegalArgumentException("Arguments too large");
            copy.add(argument);
        }
        this.engine = engine; this.entry = entry; this.arguments = Collections.unmodifiableList(copy);
        this.timeoutMillis = timeoutMillis; this.outputLimitBytes = outputLimitBytes;
        this.allowUnqualifiedNode = allowUnqualifiedNode;
    }

    Bundle encode() {
        Bundle b = new Bundle(); b.putString("engine", engine.id); b.putString("entry", entry);
        b.putStringArrayList("args", new ArrayList<String>(arguments)); b.putLong("timeout", timeoutMillis);
        b.putInt("outputLimit", outputLimitBytes); b.putBoolean("experimental", allowUnqualifiedNode);
        return b;
    }
    static EngineRequest decode(Bundle b) {
        if (b == null) throw new IllegalArgumentException("Missing request");
        ArrayList<String> args = b.getStringArrayList("args");
        return new EngineRequest(Engine.fromId(b.getString("engine")), b.getString("entry"), args,
                b.getLong("timeout"), b.getInt("outputLimit"), b.getBoolean("experimental"));
    }
}
