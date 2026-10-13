package dev.ide.applog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.security.Permission;
import java.util.List;
import java.util.Map;

/** Opt-in URLConnection adapter. It delegates real I/O without buffering or inspecting its bytes. */
public final class UrlConnectionInstrumentation {
    private UrlConnectionInstrumentation() {}
    public static HttpURLConnection open(URL url) throws IOException { return wrap(url.openConnection()); }
    public static HttpURLConnection open(URL url, Proxy proxy) throws IOException { return wrap(url.openConnection(proxy)); }
    public static HttpURLConnection wrap(URLConnection connection) {
        if (!(connection instanceof HttpURLConnection)) throw new IllegalArgumentException("HTTP(S) connection required");
        if (connection instanceof TracedConnection) return (HttpURLConnection) connection;
        return new TracedConnection((HttpURLConnection) connection);
    }

    private static final class TracedConnection extends HttpURLConnection {
        private final HttpURLConnection delegate;
        private NetworkTrace trace;
        private volatile int status;
        TracedConnection(HttpURLConnection delegate) { super(delegate.getURL()); this.delegate = delegate; }
        private synchronized NetworkTrace trace() {
            if (trace == null) trace = NetworkTrace.begin(delegate.getRequestMethod(), delegate.getURL());
            return trace;
        }
        private void failed(IOException e) { trace().end(status, e); }
        @Override public void connect() throws IOException { trace(); try { delegate.connect(); } catch (IOException e) { failed(e); throw e; } }
        @Override public int getResponseCode() throws IOException {
            trace();
            try {
                status = delegate.getResponseCode();
                if ("HEAD".equals(delegate.getRequestMethod()) || status == 204 || status == 304) trace().end(status, null);
                return status;
            } catch (IOException e) { failed(e); throw e; }
        }
        @Override public InputStream getInputStream() throws IOException {
            trace();
            try { InputStream stream = delegate.getInputStream(); getResponseCode(); return observed(stream); }
            catch (IOException e) {
                try { status = delegate.getResponseCode(); } catch (IOException ignored) {}
                // HTTP error status is not a transport failure. Preserve it while the caller reads errorStream.
                if (status < 400 || delegate.getErrorStream() == null) failed(e);
                throw e;
            }
        }
        @Override public OutputStream getOutputStream() throws IOException {
            trace();
            try {
                return new FilterOutputStream(delegate.getOutputStream()) {
                    @Override public void write(int b) throws IOException { try { out.write(b); } catch (IOException e) { failed(e); throw e; } }
                    @Override public void write(byte[] b, int off, int len) throws IOException { try { out.write(b, off, len); } catch (IOException e) { failed(e); throw e; } }
                    @Override public void flush() throws IOException { try { out.flush(); } catch (IOException e) { failed(e); throw e; } }
                    @Override public void close() throws IOException { try { out.close(); } catch (IOException e) { failed(e); throw e; } }
                };
            } catch (IOException e) { failed(e); throw e; }
        }
        @Override public InputStream getErrorStream() {
            InputStream stream = delegate.getErrorStream();
            if (stream == null) return null;
            trace();
            try { status = delegate.getResponseCode(); } catch (IOException ignored) {}
            return observed(stream);
        }
        private InputStream observed(InputStream stream) {
            if (stream == null) return null;
            final long expected = delegate.getContentLengthLong(); // Framing only, never stored or exported.
            final boolean checkLength = delegate.getContentEncoding() == null && delegate.getHeaderField("Transfer-Encoding") == null;
            return new FilterInputStream(stream) {
                long read;
                private int observedRead(int count) throws IOException {
                    if (count < 0) {
                        if (checkLength && expected >= 0 && read < expected) {
                            EOFException e = new EOFException("Incomplete HTTP response"); failed(e); throw e;
                        }
                        trace().end(status, null);
                    } else read += count;
                    return count;
                }
                @Override public int read() throws IOException {
                    try { int value = in.read(); observedRead(value < 0 ? -1 : 1); return value; }
                    catch (IOException e) { failed(e); throw e; }
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    try { return observedRead(in.read(b, off, len)); } catch (IOException e) { failed(e); throw e; }
                }
                @Override public long skip(long n) throws IOException { try { long count = in.skip(n); read += count; return count; } catch (IOException e) { failed(e); throw e; } }
                @Override public void close() throws IOException {
                    try {
                        in.close();
                        if (checkLength && expected >= 0 && read < expected) trace().cancel(status); else trace().end(status, null);
                    } catch (IOException e) { failed(e); throw e; }
                }
            };
        }
        @Override public String getResponseMessage() throws IOException { getResponseCode(); return delegate.getResponseMessage(); }
        @Override public void disconnect() { try { delegate.disconnect(); } finally { synchronized (this) { if (trace != null) trace.cancel(status); } } }
        @Override public boolean usingProxy() { return delegate.usingProxy(); }
        @Override public URL getURL() { return delegate.getURL(); }
        @Override public void setRequestMethod(String v) throws ProtocolException { delegate.setRequestMethod(v); }
        @Override public String getRequestMethod() { return delegate.getRequestMethod(); }
        @Override public void setRequestProperty(String k, String v) { delegate.setRequestProperty(k, v); }
        @Override public void addRequestProperty(String k, String v) { delegate.addRequestProperty(k, v); }
        @Override public String getRequestProperty(String k) { return delegate.getRequestProperty(k); }
        @Override public Map<String, List<String>> getRequestProperties() { return delegate.getRequestProperties(); }
        @Override public String getHeaderField(String k) { return delegate.getHeaderField(k); }
        @Override public String getHeaderField(int n) { return delegate.getHeaderField(n); }
        @Override public String getHeaderFieldKey(int n) { return delegate.getHeaderFieldKey(n); }
        @Override public Map<String, List<String>> getHeaderFields() { return delegate.getHeaderFields(); }
        @Override public void setConnectTimeout(int v) { delegate.setConnectTimeout(v); }
        @Override public int getConnectTimeout() { return delegate.getConnectTimeout(); }
        @Override public void setReadTimeout(int v) { delegate.setReadTimeout(v); }
        @Override public int getReadTimeout() { return delegate.getReadTimeout(); }
        @Override public void setDoInput(boolean v) { delegate.setDoInput(v); }
        @Override public boolean getDoInput() { return delegate.getDoInput(); }
        @Override public void setDoOutput(boolean v) { delegate.setDoOutput(v); }
        @Override public boolean getDoOutput() { return delegate.getDoOutput(); }
        @Override public void setUseCaches(boolean v) { delegate.setUseCaches(v); }
        @Override public boolean getUseCaches() { return delegate.getUseCaches(); }
        @Override public void setDefaultUseCaches(boolean v) { delegate.setDefaultUseCaches(v); }
        @Override public boolean getDefaultUseCaches() { return delegate.getDefaultUseCaches(); }
        @Override public void setAllowUserInteraction(boolean v) { delegate.setAllowUserInteraction(v); }
        @Override public boolean getAllowUserInteraction() { return delegate.getAllowUserInteraction(); }
        @Override public void setIfModifiedSince(long v) { delegate.setIfModifiedSince(v); }
        @Override public long getIfModifiedSince() { return delegate.getIfModifiedSince(); }
        @Override public void setInstanceFollowRedirects(boolean v) { delegate.setInstanceFollowRedirects(v); }
        @Override public boolean getInstanceFollowRedirects() { return delegate.getInstanceFollowRedirects(); }
        @Override public void setFixedLengthStreamingMode(int v) { delegate.setFixedLengthStreamingMode(v); }
        @Override public void setFixedLengthStreamingMode(long v) { delegate.setFixedLengthStreamingMode(v); }
        @Override public void setChunkedStreamingMode(int v) { delegate.setChunkedStreamingMode(v); }
        @Override public Permission getPermission() throws IOException { return delegate.getPermission(); }
        @Override public long getLastModified() { return delegate.getLastModified(); }
        @Override public long getExpiration() { return delegate.getExpiration(); }
        @Override public long getDate() { return delegate.getDate(); }
        @Override public int getContentLength() { return delegate.getContentLength(); }
        @Override public String getContentType() { return delegate.getContentType(); }
        @Override public String getContentEncoding() { return delegate.getContentEncoding(); }
    }
}
