package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Json 输出器测试：转义、类型映射、嵌套结构。
 *
 * <p>断言"生成的 JSON 文本"这一结构性事实——前端 JSON.parse 失败往往就出在转义上
 * （中文/引号/换行/控制字符），故重点覆盖这些边界。</p>
 *
 * @author officia-demo
 */
@DisplayName("Json 输出器测试")
class JsonTest {

    @Test
    @DisplayName("基本类型：字符串/数字/布尔/null")
    void primitives() {
        String s = Json.obj().put("name", "officia").put("size", 1024)
            .put("ok", true).put("nil", null).end();
        assertThat(s).isEqualTo("{\"name\":\"officia\",\"size\":1024,\"ok\":true,\"nil\":null}");
    }

    @Test
    @DisplayName("中文直出 UTF-8，不做 unicode 转义")
    void chinese() {
        assertThat(Json.obj().put("k", "张三·研发部").end()).isEqualTo("{\"k\":\"张三·研发部\"}");
    }

    @Test
    @DisplayName("特殊字符转义：引号/反斜杠/换行/制表")
    void escaping() {
        String s = Json.obj().put("k", "a\"b\\c\nd\tef").end();
        assertThat(s).isEqualTo("{\"k\":\"a\\\"b\\\\c\\nd\\tef\"}");
    }

    @Test
    @DisplayName("控制字符转 uXXXX 形式（否则前端 JSON.parse 直接失败）")
    void controlChar() {
        String s = Json.obj().put("k", "a" + (char) 1 + "b").end();
        // 用 (char) 92 拼出反斜杠，避免源码里出现 uXXXX 序列被 Java 词法器提前解释
        String expected = "{" + '"' + "k" + '"' + ":" + '"' + "a" + (char) 92 + "u0001b" + '"' + "}";
        assertThat(s).isEqualTo(expected);
        assertThat(s.indexOf((char) 1)).as("原始控制字符不应出现在输出中").isEqualTo(-1);
    }

    @Test
    @DisplayName("嵌套：Map / List / 内层 Json 对象")
    void nested() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", 1);
        String s = Json.obj()
            .put("map", m)
            .put("list", List.of("a", 2, false))
            .put("inner", Json.obj().put("deep", "v"))
            .end();
        assertThat(s).isEqualTo("{\"map\":{\"x\":1},\"list\":[\"a\",2,false],\"inner\":{\"deep\":\"v\"}}");
    }

    @Test
    @DisplayName("空对象合法")
    void empty() {
        assertThat(Json.obj().end()).isEqualTo("{}");
    }
}
