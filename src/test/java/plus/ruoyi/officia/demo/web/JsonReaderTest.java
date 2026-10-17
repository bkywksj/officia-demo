package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON 解析：与 {@link Json} 写出器往返一致（反馈的 meta.json 就是这样读写的），非法输入明确报错。
 *
 * @author officia-demo
 */
@DisplayName("JsonReader 解析")
class JsonReaderTest {

    @Test
    @DisplayName("与 Json 写出器往返：中文、转义、嵌套对象与数组、数字布尔 null")
    void roundTripsWithTheWriter() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("size", 12345L);
        inner.put("ratio", 0.5);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("note", "第 3 页表格边框丢了\n\"引号\" \\ 反斜杠\t制表");
        m.put("ok", true);
        m.put("none", null);
        m.put("file", inner);
        m.put("tags", Arrays.asList("a", 2L, false));
        StringBuilder sb = new StringBuilder();
        Json.writeValue(sb, m);

        Map<String, Object> back = JsonReader.parseObject(sb.toString());
        assertThat(back).isEqualTo(m);
    }

    @Test
    @DisplayName("\\u 转义与空白")
    void unicodeEscapesAndWhitespace() {
        Map<String, Object> m = JsonReader.parseObject(" {\n \"k\" : \"\\u4e2d\\u6587\" , \"n\": -3 } ");
        assertThat(m.get("k")).isEqualTo("中文");
        assertThat(m.get("n")).isEqualTo(-3L);
        List<?> arr = (List<?>) JsonReader.parse("[ ]");
        assertThat(arr).isEmpty();
    }

    @Test
    @DisplayName("非法输入抛 IllegalArgumentException（上层转 400）")
    void rejectsMalformedInput() {
        for (String bad : new String[]{"", "{", "{\"a\":}", "{\"a\":1,}", "[1 2]", "\"未结束", "tru", "{\"a\":1} x", "{'a':1}"}) {
            assertThatThrownBy(() -> JsonReader.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> JsonReader.parseObject("[1]")).hasMessageContaining("对象");
    }

    @Test
    @DisplayName("超深嵌套被挡住，不会把栈打爆")
    void deepNestingIsRejected() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append('[');
        }
        assertThatThrownBy(() -> JsonReader.parse(sb.toString())).hasMessageContaining("嵌套过深");
    }
}
