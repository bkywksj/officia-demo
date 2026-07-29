package plus.ruoyi.officia.demo.web;

import java.util.Collection;
import java.util.Map;

/**
 * 极简 JSON 输出器（零依赖）。
 *
 * <p>officia 的 MiniJson 只负责<b>解析</b>（JSON → Map/List），测试台需要反向把结果<b>写成</b> JSON
 * 返回给前端，故在 demo 侧自写一个最小输出器：够用即可，不引任何第三方库（与 officia 同样的取舍）。</p>
 *
 * @author officia-demo
 */
public final class Json {

    private final StringBuilder sb = new StringBuilder(256);
    private boolean first = true;

    private Json() {
        sb.append('{');
    }

    /** 开始构建一个 JSON 对象。 */
    public static Json obj() {
        return new Json();
    }

    /** 追加键值对；value 支持 String/数字/布尔/null/Map/Collection/Json/byte[]长度。 */
    public Json put(String key, Object value) {
        if (!first) {
            sb.append(',');
        }
        first = false;
        writeString(sb, key);
        sb.append(':');
        writeValue(sb, value);
        return this;
    }

    /** 完成并返回 JSON 文本。 */
    public String end() {
        return sb.toString() + "}";
    }

    @Override
    public String toString() {
        return end();
    }

    /** 把任意值写成 JSON 片段（供内部与嵌套使用）。 */
    static void writeValue(StringBuilder out, Object v) {
        if (v == null) {
            out.append("null");
        } else if (v instanceof Json) {
            out.append(((Json) v).end());
        } else if (v instanceof Boolean || v instanceof Number) {
            out.append(v);
        } else if (v instanceof Map) {
            out.append('{');
            boolean f = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!f) {
                    out.append(',');
                }
                f = false;
                writeString(out, String.valueOf(e.getKey()));
                out.append(':');
                writeValue(out, e.getValue());
            }
            out.append('}');
        } else if (v instanceof Collection) {
            out.append('[');
            boolean f = true;
            for (Object o : (Collection<?>) v) {
                if (!f) {
                    out.append(',');
                }
                f = false;
                writeValue(out, o);
            }
            out.append(']');
        } else if (v instanceof Object[]) {
            out.append('[');
            Object[] arr = (Object[]) v;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeValue(out, arr[i]);
            }
            out.append(']');
        } else {
            writeString(out, String.valueOf(v));
        }
    }

    /** JSON 字符串转义（含控制字符与中文直出 UTF-8）。 */
    static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
