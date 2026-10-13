package dev.ide.applog.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Explicit, versioned selection of a participating application's portable USER data, never filesDir. */
public final class BackupManifest {
    public enum Type {
        USER_PREFERENCES(1), USER_RECORDS(2), USER_ATTACHMENT(3);
        final int wireId;
        Type(int wireId) { this.wireId = wireId; }
        static Type fromWire(int id) {
            for (Type type : values()) if (type.wireId == id) return type;
            throw new IllegalArgumentException("Unsupported backup entry type");
        }
    }

    public static final class Entry {
        private final String name;
        private final Type type;

        public Entry(String name, Type type) {
            validateName(name);
            if (type == null) throw new IllegalArgumentException("Entry type is required");
            this.name = name;
            this.type = type;
        }
        public String name() { return name; }
        public Type type() { return type; }
    }

    private final String applicationId;
    private final String schemaId;
    private final int schemaVersion;
    private final List<Entry> entries;

    public BackupManifest(String applicationId, String schemaId, int schemaVersion, List<Entry> entries) {
        validateIdentifier(applicationId, 200);
        validateIdentifier(schemaId, 128);
        if (schemaVersion < 1) throw new IllegalArgumentException("Schema version must be positive");
        if (entries == null || entries.isEmpty() || entries.size() > 1024)
            throw new IllegalArgumentException("Explicit nonempty selection (at most 1024 entries) is required");
        Set<String> names = new HashSet<String>();
        List<Entry> copy = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (entry == null || !names.add(entry.name)) throw new IllegalArgumentException("Duplicate/null entry");
            copy.add(entry);
        }
        this.applicationId = applicationId;
        this.schemaId = schemaId;
        this.schemaVersion = schemaVersion;
        this.entries = Collections.unmodifiableList(copy);
    }
    public String applicationId() { return applicationId; }
    public String schemaId() { return schemaId; }
    public int schemaVersion() { return schemaVersion; }
    public List<Entry> entries() { return entries; }

    static void validateIdentifier(String text, int max) {
        if (text == null || text.length() > max || !text.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new IllegalArgumentException("Invalid identifier");
    }

    private static void validateName(String name) {
        if (name == null || name.length() > 240 || name.isEmpty())
            throw new IllegalArgumentException("Invalid selected entry name");
        for (String part : name.split("/", -1)) {
            if (part.length() > 128 || !part.matches("[A-Za-z0-9][A-Za-z0-9._-]*") || part.equals(".."))
                throw new IllegalArgumentException("Entry names must be safe relative logical names");
            String lower = part.toLowerCase(Locale.ROOT);
            if (lower.matches(".*\\.(key|pem|jks|keystore|p12|pfx)")
                    || lower.matches("(^|.*[._-])(credentials?|secrets?|passwords?|tokens?|auth|cookies|keystore|signingkeys?)([._-].*|$)"))
                throw new IllegalArgumentException("Credentials and signing keys are not backup data");
        }
    }

    /** Exact names/types only; an importer may migrate older schema versions but never silently downgrade. */
    void requireCompatibleSelection(BackupManifest target) {
        if (!applicationId.equals(target.applicationId) || !schemaId.equals(target.schemaId)
                || schemaVersion > target.schemaVersion || entries.size() != target.entries.size())
            throw new IllegalArgumentException("Archive does not match the selected application/schema");
        for (Entry source : entries) {
            boolean found = false;
            for (Entry selected : target.entries) {
                if (source.name.equals(selected.name) && source.type == selected.type) { found = true; break; }
            }
            if (!found) throw new IllegalArgumentException("Archive contains an unselected name or type");
        }
    }
}
