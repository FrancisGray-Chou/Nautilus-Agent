package com.starsea.nautilus;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small JSON codec for the local JSON Lines protocol; no runtime dependencies. */
final class Json {
    private Json() {}

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                switch (ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (ch < 32 || Character.isSurrogate(ch)) out.append(String.format("\\u%04x", (int) ch));
                        else out.append(ch);
                    }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(encode(key.toString()) + ":" + encode(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> entries = new ArrayList<>();
            items.forEach(item -> entries.add(encode(item)));
            return "[" + String.join(",", entries) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON type");
    }

    static Map<String, Object> object(String input) throws IOException {
        Parser parser = new Parser(input);
        Object result = parser.value(0);
        parser.space();
        if (parser.pos != input.length() || !(result instanceof Map<?, ?> map)) throw new IOException("无效的 JSON 响应");
        Map<String, Object> object = new LinkedHashMap<>();
        map.forEach((key, value) -> object.put((String) key, value));
        return object;
    }

    private static final class Parser {
        private final String input;
        private int pos;
        Parser(String input) { this.input = input; }
        void space() {
            while (pos < input.length() && " \r\n\t".indexOf(input.charAt(pos)) >= 0) pos++;
        }
        Object value(int depth) throws IOException {
            space();
            if (depth > 64 || pos >= input.length()) throw invalid();
            char ch = input.charAt(pos);
            if (ch == '"') return string();
            if (ch == '{') {
                pos++;
                Map<String, Object> result = new LinkedHashMap<>();
                if (take('}')) return result;
                do {
                    space();
                    if (pos >= input.length() || input.charAt(pos) != '"') throw invalid();
                    String key = string();
                    if (!take(':')) throw invalid();
                    result.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) throw invalid();
                return result;
            }
            if (ch == '[') {
                pos++;
                List<Object> result = new ArrayList<>();
                if (take(']')) return result;
                do { result.add(value(depth + 1)); } while (take(','));
                if (!take(']')) throw invalid();
                return result;
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (input.startsWith(literal, pos)) {
                    pos += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = pos;
            while (pos < input.length() && "-+0123456789.eE".indexOf(input.charAt(pos)) >= 0) pos++;
            String number = input.substring(start, pos);
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
            try { return new BigDecimal(number); } catch (NumberFormatException ex) { throw invalid(); }
        }
        boolean take(char ch) {
            space();
            if (pos < input.length() && input.charAt(pos) == ch) { pos++; return true; }
            return false;
        }
        String string() throws IOException {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < input.length()) {
                char ch = input.charAt(pos++);
                if (ch == '"') return out.toString();
                if (ch < 32) throw invalid();
                if (ch != '\\') { out.append(ch); continue; }
                if (pos >= input.length()) throw invalid();
                switch (input.charAt(pos++)) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > input.length()) throw invalid();
                        try { out.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16)); }
                        catch (NumberFormatException ex) { throw invalid(); }
                        pos += 4;
                    }
                    default -> throw invalid();
                }
            }
            throw invalid();
        }
        IOException invalid() { return new IOException("无效的 JSON 响应，位置 " + pos); }
    }
}
