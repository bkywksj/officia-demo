package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.pdf.OfficiaPdf;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Officia.Pdf 消费示例：用 Cells 生成两份 PDF → 合并 → 页数/抽取自洽 → 加密。
 *
 * <p>不依赖外部样本：先用 CSV→PDF 造两份 PDF，再演示 pdf 能力，自成闭环。</p>
 *
 * @author officia-demo
 */
@DisplayName("Pdf 能力消费示例")
class PdfDemoTest {

    private byte[] pdfOf(String csv) {
        return OfficiaCells.csvToPdf(csv);
    }

    @Test
    @DisplayName("两份 PDF 合并 → 页数≥2")
    void mergePdfs() {
        byte[] a = pdfOf("A,B\n1,2");
        byte[] b = pdfOf("C,D\n3,4");
        byte[] merged = OfficiaPdf.merge(List.of(a, b));
        assertThat(new String(merged, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(OfficiaPdf.pageCount(merged)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("AES-256 加密 → 输出合法 PDF")
    void encrypt() {
        byte[] pdf = pdfOf("Secret,Value\nkey,42");
        byte[] enc = OfficiaPdf.encryptAes256(pdf, "openpwd", "ownerpwd");
        assertThat(new String(enc, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(enc.length).isGreaterThan(0);
    }
}
