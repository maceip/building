package dev.ide.applog;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/** Consent-checked access to a local journal. No file paths, credentials, or mutable records are exposed. */
public interface IncidentJournal {
    /** An immutable, non-destructive snapshot. Records retain their original session and build identity. */
    List<Incident> snapshotPending(int limit) throws IOException;

    /**
     * Remove only records for which a trusted receiver has confirmed a durable commit.
     * The transport MUST authenticate the receiver, validate its receipt, and restrict these IDs to the
     * batch it actually submitted. An HTTP success, a Binder return, or an export is NOT a durable receipt.
     * A crash during acknowledgment can replay an already committed ID; receivers must deduplicate IDs.
     */
    void acknowledgeDurablyCommitted(Collection<String> eventIds) throws IOException;

    /** Persisted loss count plus, for a live SDK, any not-yet-persisted queue losses. Not an exactly-once metric. */
    long droppedCount();
}
