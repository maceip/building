// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

/** Closed engine set. npm, shells, arbitrary executables and native package installers are not engines. */
public enum Engine {
    NODE("node", true), PYTHON("python", false);
    public final String id;
    public final boolean requiresExperimentalConsent;
    Engine(String id, boolean experimental) { this.id = id; this.requiresExperimentalConsent = experimental; }
    static Engine fromId(String id) {
        for (Engine engine : values()) if (engine.id.equals(id)) return engine;
        throw new IllegalArgumentException("Unsupported engine: " + id);
    }
}
