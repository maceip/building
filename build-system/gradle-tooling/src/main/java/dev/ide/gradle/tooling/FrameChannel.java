package dev.ide.gradle.tooling;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 4-byte big-endian length, then UTF-8 JSON. Frames larger than 16 MiB are rejected. */
public final class FrameChannel {
    static final int MAX_FRAME = 16 * 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;

    public FrameChannel(FileDescriptor inFd, FileDescriptor outFd) {
        this.in = new FileInputStream(inFd);
        this.out = new FileOutputStream(outFd);
    }

    public synchronized Object read() throws IOException {
        byte[] header = readFully(4);
        int n = ((header[0] & 0xff) << 24)
                | ((header[1] & 0xff) << 16)
                | ((header[2] & 0xff) << 8)
                | (header[3] & 0xff);
        if (n < 0 || n > MAX_FRAME) throw new IOException("frame length " + n);
        byte[] body = readFully(n);
        return Json.parse(new String(body, StandardCharsets.UTF_8));
    }

    public synchronized void writeJson(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_FRAME) throw new IOException("frame length " + body.length);
        byte[] header = new byte[] {
                (byte) (body.length >>> 24),
                (byte) (body.length >>> 16),
                (byte) (body.length >>> 8),
                (byte) body.length
        };
        out.write(header);
        out.write(body);
        out.flush();
    }

    public void respond(long id, Object result) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("result", result);
        writeJson(Json.write(msg));
    }

    public void fail(long id, String message) throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message == null ? "unknown error" : message);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", id);
        msg.put("error", error);
        writeJson(Json.write(msg));
    }

    public void event(Map<String, Object> event) throws IOException {
        writeJson(Json.write(event));
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("stdin closed");
            off += r;
        }
        return buf;
    }

    /** Line-buffering stream that emits one protocol log event per newline. */
    public static final class LineLog extends OutputStream {
        private final FrameChannel channel;
        private final long id;
        private final String stream;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        public LineLog(FrameChannel channel, long id, String stream) {
            this.channel = channel;
            this.id = id;
            this.stream = stream;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (b == '\n') flushLine();
            else if (b != '\r') pending.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) write(b[off + i]);
        }

        public synchronized void flushRemainder() throws IOException {
            if (pending.size() > 0) flushLine();
        }

        private void flushLine() throws IOException {
            String line = pending.toString(StandardCharsets.UTF_8);
            pending.reset();
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("event", "log");
            event.put("id", id);
            event.put("stream", stream);
            event.put("line", line);
            channel.event(event);
        }
    }
}
