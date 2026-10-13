package dev.ide.reliability.fixture;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Real app-owned SQLite state, including user records, attachment bytes, settings and editor draft. */
public final class FixtureStore extends SQLiteOpenHelper {
    public static final String SAVED_BODY = "Saved Helsinki note";
    public static final byte[] ATTACHMENT = "fixture-attachment\nÅland / 東京\n".getBytes(StandardCharsets.UTF_8);

    public static final class Draft {
        public final String body;
        public final int start, end;
        public final long selectedId;
        public Draft(String body, int start, int end, long selectedId) {
            if (body == null || start < 0 || end < start || end > body.length())
                throw new IllegalArgumentException("Invalid editor state");
            this.body = body; this.start = start; this.end = end; this.selectedId = selectedId;
        }
    }

    public FixtureStore(Context context, String name) { super(context, name, null, BuildConfig.DATA_SCHEMA); }

    static void createRecords(SQLiteDatabase db, int version) {
        db.execSQL("CREATE TABLE records(id INTEGER PRIMARY KEY,title TEXT NOT NULL,body TEXT NOT NULL"
                + (version >= 2 ? ",edited_at INTEGER NOT NULL DEFAULT 0" : "") + ")");
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createRecords(db, BuildConfig.DATA_SCHEMA);
        db.execSQL("CREATE TABLE attachments(name TEXT PRIMARY KEY,content BLOB NOT NULL)");
        db.execSQL("CREATE TABLE settings(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE draft(id INTEGER PRIMARY KEY CHECK(id=1),body TEXT NOT NULL,"
                + "selection_start INTEGER NOT NULL,selection_end INTEGER NOT NULL,selected_id INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new IllegalStateException("Unsupported fixture migration");
        MigrationPolicy.upgrade(db);
    }
    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 2 || newVersion != 1) throw new IllegalStateException("Unsupported fixture downgrade");
        MigrationPolicy.downgrade(db);
    }

    /** Explicit seed only; Activity startup never silently replaces data lost by a broken migration. */
    public synchronized void seed() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("records", null, null); db.delete("attachments", null, null);
            db.delete("settings", null, null); db.delete("draft", null, null);
            insertRecord(db, 1, "Helsinki", SAVED_BODY);
            insertRecord(db, 2, "Kyoto", "Second user record: 京都");
            putBlob(db, "notes.txt", ATTACHMENT);
            putSetting(db, "sort", "title"); putSetting(db, "fontScale", "1.25");
            saveDraft(new Draft(SAVED_BODY, 0, 0, 1));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void seedDifferentDestination() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("records", null, null); db.delete("attachments", null, null);
            db.delete("settings", null, null); db.delete("draft", null, null);
            insertRecord(db, 99, "Destination", "Existing destination record");
            putBlob(db, "notes.txt", "existing destination attachment".getBytes(StandardCharsets.UTF_8));
            putSetting(db, "sort", "destination-only"); putSetting(db, "fontScale", "0.85");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized Draft draft() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT body,selection_start,selection_end,selected_id FROM draft WHERE id=1", null)) {
            if (!c.moveToFirst()) return new Draft("", 0, 0, 1);
            return new Draft(c.getString(0), c.getInt(1), c.getInt(2), c.getLong(3));
        }
    }

    public synchronized void saveDraft(Draft draft) {
        ContentValues values = new ContentValues();
        values.put("id", 1); values.put("body", draft.body);
        values.put("selection_start", draft.start); values.put("selection_end", draft.end);
        values.put("selected_id", draft.selectedId);
        getWritableDatabase().insertWithOnConflict("draft", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void saveRecord(Draft draft) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            ContentValues values = new ContentValues(); values.put("body", draft.body);
            if (db.update("records", values, "id=?", new String[] { Long.toString(draft.selectedId) }) != 1)
                throw new IllegalStateException("Selected record is absent");
            saveDraft(draft);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized String recordBody(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM records WHERE id=?",
                new String[] { Long.toString(id) })) { return c.moveToFirst() ? c.getString(0) : null; }
    }
    public synchronized int recordCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM records", null)) {
            c.moveToFirst(); return c.getInt(0);
        }
    }
    public synchronized String setting(String name) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE name=?", new String[] { name })) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }
    public synchronized byte[] attachment(String name) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT content FROM attachments WHERE name=?", new String[] { name })) {
            return c.moveToFirst() ? c.getBlob(0) : null;
        }
    }
    public synchronized void putSetting(String name, String value) { putSetting(getWritableDatabase(), name, value); }

    public synchronized void checkpointDisk() {
        // Force a real SQLite checkpoint before the external controller kills this process. SQLite
        // commits already provide durability; this additionally avoids confusing a copied WAL with DB data.
        try (Cursor checkpoint = getWritableDatabase().rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
            if (checkpoint.moveToFirst() && checkpoint.getInt(0) != 0)
                throw new IllegalStateException("Database checkpoint is busy");
        }
    }

    synchronized Map<String, byte[]> selectedUserData() throws JSONException {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<String, byte[]>();
        JSONArray records = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,title,body FROM records ORDER BY id", null)) {
            while (c.moveToNext()) records.put(new JSONObject().put("id", c.getLong(0))
                    .put("title", c.getString(1)).put("body", c.getString(2)));
        }
        JSONObject settings = new JSONObject();
        // Explicit user preference whitelist: no credential, pairing, authentication, or SDK journal data.
        settings.put("sort", setting("sort")); settings.put("fontScale", setting("fontScale"));
        result.put("records.json", records.toString().getBytes(StandardCharsets.UTF_8));
        result.put("preferences.json", settings.toString().getBytes(StandardCharsets.UTF_8));
        byte[] bytes = attachment("notes.txt");
        result.put("attachments/notes.txt", bytes == null ? new byte[0] : bytes.clone());
        return result;
    }

    synchronized void replaceSelectedUserData(Map<String, byte[]> data, boolean failCommit) throws Exception {
        // Parsing and validation happen before the transaction deletes anything.
        JSONArray records = new JSONArray(new String(data.get("records.json"), StandardCharsets.UTF_8));
        JSONObject settings = new JSONObject(new String(data.get("preferences.json"), StandardCharsets.UTF_8));
        if (records.length() > 100 || !settings.has("sort") || !settings.has("fontScale"))
            throw new IllegalArgumentException("Invalid fixture domain data");
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("records", null, null); db.delete("attachments", null, null); db.delete("settings", null, null);
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                insertRecord(db, record.getLong("id"), record.getString("title"), record.getString("body"));
            }
            putSetting(db, "sort", settings.getString("sort"));
            putSetting(db, "fontScale", settings.getString("fontScale"));
            putBlob(db, "notes.txt", data.get("attachments/notes.txt"));
            if (failCommit) throw new java.io.IOException("Fixture interrupted commit before SQLite transaction success");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized boolean hasSeedUserData() {
        return recordCount() == 2 && SAVED_BODY.equals(recordBody(1))
                && "Second user record: 京都".equals(recordBody(2)) && "title".equals(setting("sort"))
                && "1.25".equals(setting("fontScale")) && Arrays.equals(ATTACHMENT, attachment("notes.txt"));
    }

    private static void insertRecord(SQLiteDatabase db, long id, String title, String body) {
        ContentValues values = new ContentValues(); values.put("id", id); values.put("title", title); values.put("body", body);
        db.insertOrThrow("records", null, values);
    }
    private static void putSetting(SQLiteDatabase db, String name, String value) {
        ContentValues values = new ContentValues(); values.put("name", name); values.put("value", value);
        db.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }
    private static void putBlob(SQLiteDatabase db, String name, byte[] bytes) {
        ContentValues values = new ContentValues(); values.put("name", name); values.put("content", bytes);
        db.insertOrThrow("attachments", null, values);
    }
}
