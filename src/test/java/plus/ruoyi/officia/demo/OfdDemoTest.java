package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.engine.api.ConvertOptions;
import plus.ruoyi.officia.engine.api.ConvertResult;
import plus.ruoyi.officia.ofd.OfficiaOfd;
import plus.ruoyi.officia.ofd.sign.OfdSignatureVerification;
import plus.ruoyi.officia.words.OfficiaWords;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Officia.Ofd 消费示例：Markdown 现造 docx → 转 OFD → 读回文本 / 转 PDF / 验签。
 *
 * <p>不依赖外部样本：OFD 由 {@code OfficiaWords.toOfd} 自产，再交给 {@code OfficiaOfd} 读，自成闭环。
 * 电子发票解析需要真实发票文件，见测试台「OFD · 发票/版式」面板。</p>
 *
 * @author officia-demo
 */
@DisplayName("Ofd 能力消费示例")
class OfdDemoTest {

    private static byte[] docx() {
        return OfficiaWords.markdownToDocx(
            "# 结算单\n\n本期服务费合计 12800 元。".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Word → OFD：ZIP 容器、页数≥1，读回的文本保留原文")
    void wordToOfdAndReadBack() {
        ConvertResult res = OfficiaWords.convertToOfd(docx(), ConvertOptions.defaults());
        byte[] ofd = res.getData();
        assertThat(ofd[0]).isEqualTo((byte) 'P');
        assertThat(ofd[1]).isEqualTo((byte) 'K');
        assertThat(res.getPageCount()).isGreaterThanOrEqualTo(1);
        assertThat(OfficiaOfd.pageCount(ofd)).isEqualTo(res.getPageCount());
        assertThat(OfficiaOfd.extractText(ofd)).contains("12800");
    }

    @Test
    @DisplayName("OFD → PDF：输出合法 PDF")
    void ofdToPdf() {
        byte[] pdf = OfficiaOfd.toPdf(OfficiaWords.toOfd(docx()));
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("未签章的 OFD：verify 如实报告未签章，而不是抛异常")
    void verifyUnsigned() {
        OfdSignatureVerification v = OfficiaOfd.verify(OfficiaWords.toOfd(docx()));
        assertThat(v.isSigned()).isFalse();
        assertThat(v.getSignatures()).isEmpty();
    }
}
