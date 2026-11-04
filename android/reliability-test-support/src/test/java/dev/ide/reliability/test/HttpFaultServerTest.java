package dev.ide.reliability.test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HttpFaultServerTest {
    @Test void actualHttpResponsesAndTruncatedWireBody() throws Exception {
        HttpFaultServer.Fault[] faults = { HttpFaultServer.Fault.OK, HttpFaultServer.Fault.UNAUTHORIZED,
                HttpFaultServer.Fault.RATE_LIMITED, HttpFaultServer.Fault.SERVER_ERROR,
                HttpFaultServer.Fault.MALFORMED_JSON, HttpFaultServer.Fault.PARTIAL_BODY };
        int[] codes = { 200, 401, 429, 500, 200, 200 };
        for (int i = 0; i < faults.length; i++) {
            try (HttpFaultServer server = new HttpFaultServer(faults[i])) {
                HttpURLConnection connection = (HttpURLConnection) server.url().openConnection();
                connection.setReadTimeout(1000);
                try {
                    assertEquals(codes[i], connection.getResponseCode());
                    InputStream input = codes[i] >= 400 ? connection.getErrorStream() : connection.getInputStream();
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    try (InputStream owned = input) {
                        byte[] bytes = new byte[128];
                        for (int n; (n = owned.read(bytes)) != -1;) body.write(bytes, 0, n);
                    }
                    if (faults[i] == HttpFaultServer.Fault.PARTIAL_BODY)
                        assertTrue(body.size() < connection.getContentLength());
                    else assertEquals(connection.getContentLength(), body.size());
                    assertEquals(1, server.observedRequests());
                    assertNull(server.serverFailure());
                } finally { connection.disconnect(); }
            }
        }
    }

    @Test void timeoutIsRaisedByTheRealClientAndReleaseGateFailsClosed() throws Exception {
        assertThrows(SecurityException.class, () -> HttpFaultServer.requireDebuggable(0));
        try (HttpFaultServer server = new HttpFaultServer(HttpFaultServer.Fault.READ_TIMEOUT)) {
            HttpURLConnection connection = (HttpURLConnection) server.url().openConnection();
            connection.setReadTimeout(100);
            try {
                assertThrows(SocketTimeoutException.class, connection::getResponseCode);
                assertEquals(1, server.observedRequests());
            } finally { connection.disconnect(); }
        }
    }
}
