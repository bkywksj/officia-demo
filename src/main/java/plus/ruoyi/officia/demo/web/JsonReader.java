package plus.ruoyi.officia.demo.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 解析器（RFC 8259）：对象 → {@link LinkedHashMap}、数组 → {@link ArrayList}、
 * 字符串、整数 → {@link Long}、小数 → {@link Double}、true / false / null。
 *
 * <p>为什么自己写：测试台自身也零第三方依赖；officia 内部的 JSON 工具不是公开 API（发布版会被混淆），不借用。
 * 只用来读反馈功能自己写出的 meta.json 与前端提交的小表单，不追求流式与极致性能。</p>
 *
 * @author officia-demo
 */
final class JsonReader {

    /** 嵌套层数上限：防恶意构造的超深数组把栈打爆。 */
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private JsonReader(String s) {
        this.s = s;
    }

    /**
     * 解析一段 JSON 文本。
     *
     * @throws IllegalArgumentException 不是合法 JSON（上层转 400）
     */
    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON 为空");
        }
        JsonReader r = new JsonReader(text);
        r.ws();
        Object v = r.value(0);
        r.ws();
        if (r.i != text.length()) {
            throw r.fail("多余内容");
        }
        return v;
    }

    /** 解析并要求顶层是对象。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON 顶层应为对象");
        }
        return (Map<String, Object>) v;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw fail("嵌套过深");
        }
        if (i >= s.length()) {
            throw fail("意外结束");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw fail("非法字符 '" + c + "'");
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (!peek('"')) {
                throw fail("对象的键必须是字符串");
            }
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        i++;
        ws();
        if (peek(']')) {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value(depth + 1));
            ws();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        i++;                                  // 开引号
        StringBuilder out = new StringBuilder();
        while (true) {
            if (i >= s.length()) {
                throw fail("字符串没有结束引号");
            }
            char c = s.charAt(i++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw fail("字符串里有未转义的控制字符");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw fail("转义序列不完整");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) {
                        throw fail("\\u 转义不完整");
                    }
                    try {
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw fail("\\u 转义不是十六进制");
                    }
                    i += 4;
                    break;
                default:
                    throw fail("非法转义 \\" + e);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek('-')) {
            i++;
        }
        boolean frac = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                frac = true;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        try {
            return frac ? (Object) Double.valueOf(t) : (Object) Long.valueOf(t);
        } catch (NumberFormatException ex) {
            throw fail("非法数字 " + t);
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) {
            throw fail("非法字面量");
        }
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw fail("此处应为 '" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException fail(String why) {
        return new IllegalArgumentException("JSON 解析失败（第 " + i + " 个字符）：" + why);
    }
}
