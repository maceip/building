package dev.ide.reliability.fixture;

import dev.ide.applog.Reliability;
import dev.ide.applog.UrlConnectionInstrumentation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** The app's actual HTTP path uses the production SDK adapter, not test-generated telemetry. */
public final class FixtureHttpClient {
    public enum Outcome { SAVED, TIMEOUT, UNAUTHORIZED, RATE_LIMITED, SERVER_ERROR, MALFORMED, PARTIAL, IO_ERROR }
    public static final class Result {
        public final Outcome outcome;
        public final int status;
        Result(Outcome outcome, int status) { this.outcome = outcome; this.status = status; }
    }

    public Result sync(FixtureStore store, URL endpoint) {
        HttpURLConnection connection = null;
        int status = 0;
        try {
            connection = UrlConnectionInstrumentation.open(endpoint);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1000); connection.setReadTimeout(200);
            connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
            status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] bytes = new byte[1024];
                    for (int n; (n = input.read(bytes)) != -1;) {
                        if (body.size() + n > 16384) throw new IOException("Fixture response exceeds limit");
                        body.write(bytes, 0, n);
                    }
                }
            }
            if (status == 401) return new Result(Outcome.UNAUTHORIZED, status);
            if (status == 429) return new Result(Outcome.RATE_LIMITED, status);
            if (status >= 500) return new Result(Outcome.SERVER_ERROR, status);
            if (status != 200) return new Result(Outcome.IO_ERROR, status);
            if (connection.getContentLength() >= 0 && body.size() != connection.getContentLength())
                return new Result(Outcome.PARTIAL, status);
            JSONObject payload = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            if (!payload.getBoolean("saved")) return new Result(Outcome.MALFORMED, status);
            store.putSetting("lastSync", "saved");
            return new Result(Outcome.SAVED, status);
        } catch (SocketTimeoutException timeout) { return new Result(Outcome.TIMEOUT, status); }
        catch (JSONException malformed) { return new Result(Outcome.MALFORMED, status); }
        catch (IOException io) { return new Result(status == 200 ? Outcome.PARTIAL : Outcome.IO_ERROR, status); }
        finally {
            if (connection != null) connection.disconnect();
            Reliability.flush();
        }
    }
}
