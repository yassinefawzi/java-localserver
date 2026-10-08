package server.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser.
 *
 * Supports objects, arrays, strings, numbers, booleans and null which is all
 * that the configuration file requires. Parsed values are represented as:
 *   object  -> Map<String, Object>
 *   array   -> List<Object>
 *   string  -> String
 *   number  -> Long or Double
 *   boolean -> Boolean
 *   null    -> null
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos != parser.src.length()) {
            throw parser.error("Unexpected trailing characters");
        }
        return value;
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return readObject();
            case '[':
                return readArray();
            case '"':
                return readString();
            case 't':
            case 'f':
                return readBoolean();
            case 'n':
                return readNull();
            default:
                return readNumber();
        }
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
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
            char c = next();
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw error("Expected ',' or '}' in object");
            }
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            Object value = readValue();
            list.add(value);
            skipWhitespace();
            char c = next();
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw error("Expected ',' or ']' in array");
            }
        }
        return list;
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char esc = next();
                switch (esc) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        String hex = src.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    default:
                        throw error("Invalid escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Object readNumber() {
        int start = pos;
        boolean isDouble = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                isDouble = true;
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        if (num.isEmpty()) {
            throw error("Invalid number");
        }
        if (isDouble) {
            return Double.parseDouble(num);
        }
        return Long.parseLong(num);
    }

    private Boolean readBoolean() {
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        throw error("Invalid literal");
    }

    private Object readNull() {
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw error("Invalid literal");
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos++);
    }

    private void expect(char c) {
        char actual = next();
        if (actual != c) {
            throw error("Expected '" + c + "' but found '" + actual + "'");
        }
    }

    private RuntimeException error(String message) {
        return new IllegalArgumentException("JSON parse error at position " + pos + ": " + message);
    }
}
