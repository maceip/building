package dev.ide.reliability.fixture;

import android.database.sqlite.SQLiteDatabase;

/** Deliberately destructive migration; the upgrade/downgrade assertions do not change by flavor. */
final class MigrationPolicy {
    static void upgrade(SQLiteDatabase db) {
        db.execSQL("DROP TABLE records");
        FixtureStore.createRecords(db, 2);
    }
    static void downgrade(SQLiteDatabase db) {
        db.execSQL("DROP TABLE records");
        FixtureStore.createRecords(db, 1);
    }
}
