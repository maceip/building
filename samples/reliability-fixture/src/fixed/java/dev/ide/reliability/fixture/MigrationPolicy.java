package dev.ide.reliability.fixture;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

final class MigrationPolicy {
    static void upgrade(SQLiteDatabase db) {
        boolean exists = false;
        try (Cursor columns = db.rawQuery("PRAGMA table_info(records)", null)) {
            while (columns.moveToNext()) {
                if ("edited_at".equals(columns.getString(columns.getColumnIndexOrThrow("name")))) exists = true;
            }
        }
        if (!exists) db.execSQL("ALTER TABLE records ADD COLUMN edited_at INTEGER NOT NULL DEFAULT 0");
    }
    static void downgrade(SQLiteDatabase db) {
        // V2 adds only an optional column with a default. V1 queries explicit columns, so retain the
        // compatible superset rather than deleting user data. A future incompatible schema must refuse.
    }
}
