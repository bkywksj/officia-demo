package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.cells.OfficiaCells;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Officia.Cells 消费示例：CSV↔PDF、CSV 解析（不需外部样本文件）。
 *
 * @author officia-demo
 */
@DisplayName("Cells 能力消费示例")
class CellsDemoTest {

    @Test
    @DisplayName("CSV → PDF：输出合法 PDF")
    void csvToPdf() {
        String csv = "Name,Dept,Score\nAlice,R&D,95\nBob,Product,88";
        byte[] pdf = OfficiaCells.csvToPdf(csv);
        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
    }

    @Test
    @DisplayName("CSV 解析（RFC 4180）：逐行字段")
    void parseCsv() {
        List<List<String>> rows = OfficiaCells.parseCsv("a,b,c\n1,\"2,x\",3");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("a", "b", "c");
        assertThat(rows.get(1)).containsExactly("1", "2,x", "3"); // 引号内逗号不拆分
    }

    @Test
    @DisplayName("公式便捷求值：=A1+A2")
    void evaluateFormula() {
        Object v = OfficiaCells.evaluateFormula(java.util.Map.of("A1", "1", "A2", "2"), "=A1+A2");
        assertThat(((Number) v).doubleValue()).isEqualTo(3.0);
    }
}
