package dev.ide.applog.relay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Immutable routing/idempotency context. Contains IDs, never tokens, event text, or backup data. */
public final class RelayBatch {
    public final String applicationId;
    public final String batchId;
    public final RelayScope scope;
    public final String resourceId;
    public final List<String> incidentIds;

    public RelayBatch(String applicationId, String batchId, RelayScope scope, String resourceId, List<String> incidentIds) {
        identifier(applicationId, 200);
        identifier(batchId, 128);
        if (scope == null || incidentIds == null || incidentIds.size() > 1024)
            throw new IllegalArgumentException("Invalid relay batch");
        if (scope == RelayScope.EVENTS) {
            if (resourceId != null && !resourceId.isEmpty() || incidentIds.isEmpty())
                throw new IllegalArgumentException("Event batch requires incident IDs only");
        } else {
            identifier(resourceId, 128);
            if (!incidentIds.isEmpty()) throw new IllegalArgumentException("Backup operations cannot use incident IDs");
        }
        List<String> ids = new ArrayList<String>();
        HashSet<String> unique = new HashSet<String>();
        for (String id : incidentIds) {
            identifier(id, 128);
            if (!unique.add(id)) throw new IllegalArgumentException("Duplicate incident ID");
            ids.add(id);
        }
        this.applicationId = applicationId; this.batchId = batchId; this.scope = scope;
        this.resourceId = resourceId == null ? "" : resourceId;
        this.incidentIds = Collections.unmodifiableList(ids);
    }
    static void identifier(String value, int limit) {
        if (value == null || value.length() > limit || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new IllegalArgumentException("Invalid relay identifier");
    }
    byte[] context() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(1);
        text(out, applicationId); text(out, batchId); text(out, scope.wireName()); text(out, resourceId);
        out.writeInt(incidentIds.size());
        for (String id : incidentIds) text(out, id);
        return buffer.toByteArray();
    }
    static void text(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length); output.write(bytes);
    }
}
