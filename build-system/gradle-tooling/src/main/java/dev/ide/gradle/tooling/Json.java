package dev.ide.gradle.tooling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader and writer. Objects keep insertion order. Integers are {@link Long}. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Cursor c = new Cursor(text);
        Object v = c.value();
        c.ws();
        if (!c.done()) throw new IllegalArgumentException("trailing content at " + c.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        return null;
    }

    public static String str(Object v, String key) {
        Map<String, Object> m = obj(v);
        if (m == null) return null;
        Object x = m.get(key);
        return x instanceof String ? (String) x : null;
    }

    public static long id(Object v) {
        Map<String, Object> m = obj(v);
        if (m == null) return -1;
        Object x = m.get("id");
        if (x instanceof Long) return (Long) x;
        if (x instanceof Double) return ((Double) x).longValue();
        return -1;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v, String key) {
        Map<String, Object> m = obj(v);
        if (m == null) return List.of();
        Object x = m.get(key);
        if (x instanceof List) return (List<Object>) x;
        return List.of();
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            sb.append('"');
            escape(sb, (String) v);
            sb.append('"');
        } else if (v instanceof Long || v instanceof Integer || v instanceof Short) {
            sb.append(v.toString());
        } else if (v instanceof Double) {
            Double d = (Double) v;
            if (d.isNaN() || d.isInfinite()) sb.append("null");
            else if (d == Math.rint(d) && !d.isInfinite()) sb.append(d.longValue());
            else sb.append(d);
        } else if (v instanceof Boolean) {
            sb.append(v.toString());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"');
                escape(sb, e.getKey());
                sb.append("\":");
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            sb.append('"');
            escape(sb, v.toString());
            sb.append('"');
        }
    }

    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
    }

    private static final class Cursor {
        final String s;
        int pos;

        Cursor(String s) { this.s = s; }

        boolean done() { return pos >= s.length(); }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() {
            ws();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(pos);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (c == 't') return literal("true", Boolean.TRUE);
            if (c == 'f') return literal("false", Boolean.FALSE);
            if (c == 'n') return literal("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return num();
            throw new IllegalArgumentException("unexpected '" + c + "' at " + pos);
        }

        Map<String, Object> obj() {
            pos++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek('}')) { pos++; return m; }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"')
                    throw new IllegalArgumentException("expected key at " + pos);
                String key = str();
                ws();
                if (!peek(':')) throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                m.put(key, value());
                ws();
                if (peek('}')) { pos++; return m; }
                if (!peek(',')) throw new IllegalArgumentException("expected ',' at " + pos);
                pos++;
            }
        }

        List<Object> arr() {
            pos++;
            List<Object> list = new ArrayList<>();
            ws();
            if (peek(']')) { pos++; return list; }
            while (true) {
                list.add(value());
                ws();
                if (peek(']')) { pos++; return list; }
                if (!peek(',')) throw new IllegalArgumentException("expected ',' at " + pos);
                pos++;
            }
        }

        String str() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("bad escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("bad unicode");
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape " + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        Object num() {
            int start = pos;
            if (peek('-')) pos++;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            boolean real = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                real = true;
                pos++;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                real = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') pos++;
            }
            String raw = s.substring(start, pos);
            if (real) return Double.valueOf(raw);
            return Long.valueOf(raw);
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) throw new IllegalArgumentException("expected " + word);
            pos += word.length();
            return value;
        }

        boolean peek(char c) { return pos < s.length() && s.charAt(pos) == c; }
    }
}
