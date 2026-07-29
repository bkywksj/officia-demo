package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.license.OfficiaLicense;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 授权门控降级演示：打开 enforcement 后，未授权 cells 转 PDF 被降级（水印/限页）。
 *
 * <p><b>本示例不携带任何 License 令牌</b>——demo 面向公开展示，绝不内置可解锁的 .lic。
 * “授权后不降级”的效果由客户放入自己的 {@code officia.lic} 时自然生效（{@link #licensedIfProvided}，
 * 未放置时自动跳过）。真实客户从 flm 后台“授权管理 → 签发”下载自己的 officia.lic。</p>
 *
 * @author officia-demo
 */
@DisplayName("授权门控降级演示")
class EnforcementDemoTest {

    private static final String CSV = "Name,Dept\nAlice,R&D\nBob,Product";

    @BeforeEach
    void setup() {
        OfficiaLicense.enableEnforcement(false);
        OfficiaLicense.reset();
    }

    @AfterEach
    void teardown() {
        OfficiaLicense.enableEnforcement(false);
        OfficiaLicense.reset();
    }

    private void assertPdf(byte[] pdf) {
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).startsWith("%PDF-");
        assertThat(pdf.length).isGreaterThan(0);
    }

    @Test
    @DisplayName("enforcement 关(开发默认) → 正常出 PDF")
    void notEnforced() {
        assertPdf(OfficiaCells.csvToPdf(CSV));
        assertThat(OfficiaLicense.isEvaluation()).isTrue(); // 未授权但未强制执行，故不降级
    }

    @Test
    @DisplayName("enforcement 开 + 未授权 → 仍出 PDF（降级：水印/限页），不抛异常不退出")
    void enforcedUnlicensedDegrades() {
        OfficiaLicense.enableEnforcement(true);
        // 库不阻断宿主：仍能转换，只是降级
        assertPdf(OfficiaCells.csvToPdf(CSV));
        assertThat(OfficiaLicense.isEvaluation()).isTrue();
    }

    @Test
    @DisplayName("enforcement 开 + 客户自备 officia.lic → 授权则不降级（无 lic 时跳过）")
    void licensedIfProvided() {
        File lic = new File("officia.lic"); // 客户把自己的 License 放到工作目录
        assumeTrue(lic.isFile(), "未放置 officia.lic，跳过授权演示（demo 不内置任何令牌）");
        OfficiaLicense.enableEnforcement(true);
        OfficiaLicense.setLicense(lic);
        assumeTrue(OfficiaLicense.hasModule("cells"), "该 License 未授权 cells 模块，跳过");
        // 授权 → 正常出 PDF，无水印
        assertPdf(OfficiaCells.csvToPdf(CSV));
    }
}
