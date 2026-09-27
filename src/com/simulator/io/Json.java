package com.simulator.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, strict JSON reader and writer.
 *
 * <p>The project has no build file and therefore no dependency management, so
 * pulling in a JSON library would mean editing the hard-coded library path in
 * {@code .idea/libraries/lib.xml}. This covers the subset the scenario format
 * needs and nothing more: objects, arrays, strings, numbers, booleans and null.
 *
 * <p>Parsing is deliberately unforgiving. Anything the writer could not have
 * produced is rejected with a message naming the offset, rather than being
 * silently guessed at, because these files are meant to be hand-editable and a
 * typo must not quietly become a default.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ read

    /**
     * Parses a complete JSON document.
     *
     * @return {@link Map}, {@link List}, {@link String}, {@link Double},
     *         {@link Boolean}, or {@code null}
     * @throws JsonException if the text is not valid JSON
     */
    public static Object parse(String text) {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("trailing content after the top level value");
        }
        return value;
    }

    private static final class Parser {

        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        JsonException error(String message) {
            return new JsonException(message + " at offset " + pos);
        }

        void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        void expect(char c) {
            if (atEnd() || text.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        Object readValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readKeyword("true", Boolean.TRUE);
                case 'f' -> readKeyword("false", Boolean.FALSE);
                case 'n' -> readKeyword("null", null);
                default -> readNumber();
            };
        }

        private Object readKeyword(String keyword, Object value) {
            if (!text.startsWith(keyword, pos)) {
                throw error("expected '" + keyword + "'");
            }
            pos += keyword.length();
            return value;
        }

        private Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                Object value = readValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return map;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw error("raw control character in string");
                    }
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("unterminated escape sequence");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("truncated \\u escape");
                        }
                        String hex = text.substring(pos, pos + 4);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            throw error("bad \\u escape '" + hex + "'");
                        }
                        pos += 4;
                    }
                    default -> throw error("unknown escape '\\" + esc + "'");
                }
            }
        }

        private Double readNumber() {
            int start = pos;
            if (!atEnd() && text.charAt(pos) == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            if (!atEnd() && text.charAt(pos) == '.') {
                pos++;
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!atEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String literal = text.substring(start, pos);
            if (literal.isEmpty() || literal.equals("-")) {
                throw error("expected a value");
            }
            try {
                return Double.valueOf(literal);
            } catch (NumberFormatException e) {
                throw error("malformed number '" + literal + "'");
            }
        }
    }

    /** Thrown for any input this reader will not accept. */
    public static final class JsonException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    // ----------------------------------------------------------------- write

    /** Serialises the value with two-space indentation and a trailing newline. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Number n) {
            writeNumber(sb, n);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, depth);
        } else if (value instanceof Iterable<?> list) {
            writeArray(sb, list, depth);
        } else {
            throw new IllegalArgumentException(
                    "cannot serialise " + value.getClass().getName());
        }
    }

    private static void writeNumber(StringBuilder sb, Number n) {
        double d = n.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            sb.append(d);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int depth) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int remaining = map.size();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            indent(sb, depth + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), depth + 1);
            if (--remaining > 0) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, depth);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> list, int depth) {
        List<Object> items = new ArrayList<>();
        list.forEach(items::add);
        if (items.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < items.size(); i++) {
            indent(sb, depth + 1);
            writeValue(sb, items.get(i), depth + 1);
            if (i < items.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, depth);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }
}
