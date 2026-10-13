package dev.ide.applog;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

/**
 * Optional, non-exported initialization provider. All data-access methods are inert. Presence alone does
 * nothing: application metadata must explicitly name a versioned Config asset with enabled + consentGranted.
 * The reusable SDK can instead be initialized directly from Application.onCreate(), including release apps.
 */
public final class IdeLogBridgeProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        // Never let a bridge failure affect the host app: this is a developer convenience, not app logic.
        try {
            IdeLogBridge.start(getContext());
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
