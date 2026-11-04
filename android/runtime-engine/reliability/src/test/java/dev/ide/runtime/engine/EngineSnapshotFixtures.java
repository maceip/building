// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.runtime.engine;

/** Test source only, same package to construct the immutable real callback DTO without Android Bundles. */
public final class EngineSnapshotFixtures {
    private EngineSnapshotFixtures() {}
    public static EngineSnapshot create(String id, EngineSnapshot.Phase phase, Integer exit) {
        return new EngineSnapshot(id, phase, "token=never-record-stdout", "/private/never-record-stderr", "secret error message",
                500, 400, true, true, false, exit, 1234);
    }
}
