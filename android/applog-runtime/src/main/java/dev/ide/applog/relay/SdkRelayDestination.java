package dev.ide.applog.relay;

import dev.ide.applog.IncidentJournal;
import dev.ide.applog.Reliability;
import java.io.IOException;

/**
 * Opt-in bridge to the existing SDK journal, not a second event format or a local ACK consumer.
 * Pass to RelayClient.attachEvents only after initialization and separate event-relay consent.
 */
public final class SdkRelayDestination implements RelayClient.DestinationRegistry {
    @Override public IncidentJournal registerDestination(String destinationId) throws IOException {
        Reliability.registerDestination(destinationId);
        return Reliability.journal(destinationId);
    }
    @Override public void unregisterDestination(String destinationId) throws IOException {
        Reliability.unregisterDestination(destinationId);
    }
}
