package dev.ide.applog.relay;

import java.io.IOException;

/**
 * Adapter contract for one explicitly configured developer endpoint, never a hosted service dependency.
 * POST application/vnd.ide.relay-v1: int 0x52515031, int contextLength, RelayBatch.context(), int
 * envelopeLength, mandatory RelayCrypto request envelope. All integers big-endian; strings int UTF-8 length.
 * Context: int version=1, appId, batchId, scope wire name, resourceId, int ID count, ordered incident IDs.
 * Response HTTP 200: int 0x52535031, 32-byte RelayCrypto receipt, int responseLength, response bytes.
 * Events/upload response bytes are empty; download bytes are a request-context-bound encrypted response.
 * Endpoint must authorize short-lived bearer app+scope, deny resource ownership mismatch, durably commit
 * before ACK, deduplicate incident IDs (not just batch IDs), and return same ACK for an idempotent replay.
 * No redirects, arbitrary download URLs, query credentials, or server-issued recipient keys are supported.
 */
public interface RelayTransport {
    Response post(RelayConfig config, RelayBatch batch, byte[] encryptedPayload, RelayCredentials.Token token,
                  RelayConsent.Permit consent, int maxResponseBytes) throws IOException;

    final class Response {
        public final int status;
        private final byte[] receipt;
        private final byte[] body;
        public Response(int status, byte[] receipt, byte[] body) {
            this.status = status;
            this.receipt = receipt == null ? new byte[0] : receipt.clone();
            this.body = body == null ? new byte[0] : body.clone();
        }
        public byte[] receipt() { return receipt.clone(); }
        public byte[] body() { return body.clone(); }
    }
}
