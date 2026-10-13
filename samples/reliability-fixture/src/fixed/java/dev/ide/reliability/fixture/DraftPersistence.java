package dev.ide.reliability.fixture;

final class DraftPersistence {
    static void checkpoint(FixtureStore store, FixtureStore.Draft draft) {
        store.saveDraft(draft);
    }
}
