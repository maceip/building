package dev.ide.reliability.fixture;

/** Intentional regression seed. Repair THIS method, never the frozen instrumentation assertions. */
final class DraftPersistence {
    static void checkpoint(FixtureStore store, FixtureStore.Draft draft) {
        // BUG: only the Activity's in-memory EditText owns the unsaved draft and selection.
    }
}
