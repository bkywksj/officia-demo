package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 工具测试：查询串解析（含中文/空值）与 MIME 推断。
 *
 * <p>查询串是本测试台传参的唯一通道（请求体留给原始文件字节），
 * 解码错了会直接导致"文件名乱码 / 参数丢失"，故必须锁住。</p>
 *
 * @author officia-demo
 */
@DisplayName("Http 工具测试")
class HttpTest {

    @Test
    @DisplayName("查询串解析：普通键值")
    void query() {
        Map<String, String> q = Http.query("id=b1&mode=stream");
        assertThat(q).containsEntry("id", "b1").containsEntry("mode", "stream");
    }

    @Test
    @DisplayName("查询串解析：URL 编码的中文正确还原")
    void queryChinese() {
        Map<String, String> q = Http.query("name=%E5%AD%A3%E5%BA%A6%E6%8A%A5%E5%91%8A.docx");
        assertThat(q).containsEntry("name", "季度报告.docx");
    }

    @Test
    @DisplayName("查询串解析：空串 / null / 无值键")
    void queryEdge() {
        assertThat(Http.query(null)).isEmpty();
        assertThat(Http.query("")).isEmpty();
        assertThat(Http.query("flag")).containsEntry("flag", "");
    }

    @Test
    @DisplayName("MIME 推断：常见办公与图片格式")
    void mime() {
        assertThat(Http.mimeOf("a.pdf")).isEqualTo("application/pdf");
        assertThat(Http.mimeOf("A.PNG")).isEqualTo("image/png");
        assertThat(Http.mimeOf("报告.docx"))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(Http.mimeOf("x.eml")).isEqualTo("message/rfc822");
    }

    @Test
    @DisplayName("MIME 推断：未知扩展名 / 无扩展名 → 二进制流")
    void mimeUnknown() {
        assertThat(Http.mimeOf("a.unknown")).isEqualTo("application/octet-stream");
        assertThat(Http.mimeOf("noext")).isEqualTo("application/octet-stream");
    }

    @Test
    @DisplayName("请求体上限常量存在且合理（防超大文件撑爆内存）")
    void bodyLimit() {
        assertThat(Http.MAX_BODY_BYTES).isPositive().isLessThanOrEqualTo(1024 * 1024 * 1024);
    }
}
