package dev.ide.applog;

import java.nio.charset.Charset;
import java.util.regex.Pattern;

/** Conservative, bounded redaction at the collection boundary, before even the in-memory queue. */
final class Redactor {
    static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?|ftp)://[^\\s<>\\\"]+");
    private static final Pattern AUTH = Pattern.compile("(?im)\\b(?:authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*[^\\r\\n]*");
    private static final Pattern SECRET = Pattern.compile("(?i)(?:[\\\"']?)(?:access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|password|passwd|secret|token|session[_-]?id)(?:[\\\"']?)\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/=_\\-.]+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern OPAQUE = Pattern.compile("(?<![A-Za-z0-9_.])[A-Za-z0-9_+/=\\-]{24,}(?:\\.[A-Za-z0-9_+/=\\-]+)*");

    private Redactor() {}

    static String text(String value) {
        if (value == null) return "";
        String bounded = value.substring(0, Math.min(value.length(), 16384));
        bounded = AUTH.matcher(bounded).replaceAll("[redacted header]");
        bounded = URL.matcher(bounded).replaceAll("[redacted URL]");
        bounded = SECRET.matcher(bounded).replaceAll("[redacted secret]");
        bounded = BEARER.matcher(bounded).replaceAll("[redacted credential]");
        bounded = EMAIL.matcher(bounded).replaceAll("[redacted email]");
        bounded = OPAQUE.matcher(bounded).replaceAll("[redacted identifier]");
        StringBuilder clean = new StringBuilder(bounded.length());
        for (int i = 0; i < bounded.length(); i++) {
            char c = bounded.charAt(i);
            if (c == '\n' || c == '\t' || (c >= 32 && c != 127)) clean.append(c);
        }
        return utf8Limit(clean.toString(), IncidentProtocol.MAX_TEXT_BYTES);
    }

    static String utf8Limit(String value, int maxBytes) {
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int cp = value.codePointAt(end);
            int n = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + n > maxBytes) break;
            bytes += n;
            end += Character.charCount(cp);
        }
        return value.substring(0, end);
    }

    /** Exception messages, thread names, arguments, and suppressed payloads are intentionally excluded. */
    static String exception(Throwable error) {
        StringBuilder out = new StringBuilder(2048);
        Throwable next = error;
        for (int cause = 0; next != null && cause < 4 && out.length() < 12000; cause++) {
            if (cause > 0) out.append("\ncaused by ");
            out.append(next.getClass().getName());
            StackTraceElement[] frames = next.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 48) && out.length() < 12000; i++) {
                StackTraceElement f = frames[i];
                out.append("\n at ").append(f.getClassName()).append('.').append(f.getMethodName())
                    .append(':').append(f.getLineNumber());
            }
            Throwable parent = next.getCause();
            if (parent == next) break;
            next = parent;
        }
        return text(out.toString());
    }
}
