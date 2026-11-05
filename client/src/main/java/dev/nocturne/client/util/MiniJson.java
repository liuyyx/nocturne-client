package dev.nocturne.client.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 无外部依赖的严格 JSON 子集解析器：对象、数组、字符串、数字、布尔和 null。 */
public final class MiniJson {
    private MiniJson() {
    }

    public static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.space();
        if (!parser.end()) {
            throw new IllegalArgumentException("trailing JSON at " + parser.at);
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int at;

        private Parser(String text) {
            this.text = text == null ? "" : text;
        }

        private Object value() {
            space();
            if (end()) {
                throw new IllegalArgumentException("missing JSON value");
            }
            char c = text.charAt(at);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't' && consume("true")) return Boolean.TRUE;
            if (c == 'f' && consume("false")) return Boolean.FALSE;
            if (c == 'n' && consume("null")) return null;
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            at++;
            space();
            if (take('}')) return out;
            while (true) {
                space();
                String key = string();
                space();
                require(':');
                out.put(key, value());
                space();
                if (take('}')) return out;
                require(',');
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<Object>();
            at++;
            space();
            if (take(']')) return out;
            while (true) {
                out.add(value());
                space();
                if (take(']')) return out;
                require(',');
            }
        }

        private String string() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(at++);
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (end()) throw new IllegalArgumentException("unterminated escape");
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (at + 4 > text.length()) throw new IllegalArgumentException("short unicode escape");
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default: throw new IllegalArgumentException("bad escape " + escaped);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Double number() {
            int start = at;
            while (!end() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
            if (start == at) throw new IllegalArgumentException("bad JSON number at " + at);
            return Double.valueOf(text.substring(start, at));
        }

        private void space() {
            while (!end() && Character.isWhitespace(text.charAt(at))) at++;
        }

        private boolean take(char wanted) {
            if (!end() && text.charAt(at) == wanted) {
                at++;
                return true;
            }
            return false;
        }

        private void require(char wanted) {
            if (!take(wanted)) throw new IllegalArgumentException("expected " + wanted + " at " + at);
        }

        private boolean consume(String wanted) {
            if (text.regionMatches(at, wanted, 0, wanted.length())) {
                at += wanted.length();
                return true;
            }
            return false;
        }

        private boolean end() {
            return at >= text.length();
        }
    }
}
