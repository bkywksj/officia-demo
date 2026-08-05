package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.pdf.OfficiaPdf;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 字体校验测试：锁住"看起来有字体、实际画不出中文"这两种线上事故。
 *
 * <p>背景：容器里装了 {@code fonts-noto-cjk}，启动横幅报"已探测到中文 TTF（可用）"，
 * 实际导出的 PDF 里中文水印是空白。根因是探测只判"文件存不存在"，而 officia 的 PDF
 * 子集器只支持 TrueType {@code glyf} 轮廓：</p>
 * <ul>
 *   <li>CFF/OTTO 轮廓（Noto CJK 官方包、PingFang、.otf）→ 子集器<b>抛异常</b>；</li>
 *   <li>纯拉丁 glyf 字体（DejaVu/Arial）→ 不报错，中文<b>静默画成空白</b>（更难发现）。</li>
 * </ul>
 *
 * <p>所以 {@link Fonts#usableForCjk} 必须两条都拦。本测试用真实字体文件验证，
 * 环境里没有对应字体时跳过（CI 与开发机字体不一致是常态，不能因此变红）。</p>
 *
 * @author officia-demo
 */
@DisplayName("字体可用性校验测试")
class FontsTest {

    /** 纯拉丁 glyf 字体：有 glyf，但没有中文字形。 */
    private static final String[] LATIN = {
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/Library/Fonts/Arial.ttf"
    };

    /** CFF/OTTO 轮廓字体：officia 的子集器完全用不了。 */
    private static final String[] CFF = {
        "C:/Windows/Fonts/DavidCLM-Bold.otf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/System/Library/Fonts/PingFang.ttc"
    };

    /** 含中文的 glyf 字体：正常可用。 */
    private static final String[] CJK = {
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/msyh.ttf",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
    };

    @Test
    @DisplayName("纯拉丁字体不得判为可画中文（否则中文水印静默变空白）")
    void latinFontRejectedForCjk() throws Exception {
        byte[] font = firstExisting(LATIN);
        assumeThat(font).as("本机没有拉丁字体可测").isNotNull();

        assertThat(Fonts.usableForCjk(font))
            .as("拉丁字体有 glyf 但无中文字形，必须判为不可用")
            .isFalse();
        assertThat(Fonts.usableForAscii(font))
            .as("拉丁字体画 ASCII 仍然可用")
            .isTrue();
    }

    @Test
    @DisplayName("CFF/OTTO 轮廓字体一律判为不可用（子集器会抛异常）")
    void cffFontRejected() throws Exception {
        byte[] font = firstExisting(CFF);
        assumeThat(font).as("本机没有 CFF 字体可测").isNotNull();

        assertThat(Fonts.usableForCjk(font)).isFalse();
        assertThat(Fonts.usableForAscii(font))
            .as("CFF 连 ASCII 都不能用——子集器要 glyf/loca")
            .isFalse();
    }

    @Test
    @DisplayName("含中文的 glyf 字体判为可用，且真能画出中文水印")
    void cjkFontAcceptedAndActuallyWorks() throws Exception {
        byte[] font = firstExisting(CJK);
        assumeThat(font).as("本机没有中文字体可测").isNotNull();

        assertThat(Fonts.usableForCjk(font)).isTrue();

        // 光判"可用"不够，得真画一次——校验逻辑与 officia 子集器的实际要求必须对齐
        byte[] pdf = OfficiaPdf.watermark(minimalPdf(), "内部资料", font);
        assertThat(pdf).startsWith('%', 'P', 'D', 'F', '-');
        assertThat(OfficiaPdf.extractText(pdf))
            .as("中文字形真被嵌入，抽得出文字（画成空白时抽不出）")
            .contains("内部资料");
    }

    @Test
    @DisplayName("探测结果自洽：报了 hasCjk 就必须真能画中文")
    void probeResultIsHonest() {
        byte[] probed = Fonts.systemCjk();
        assumeThat(probed).as("本机未探测到任何字体").isNotNull();

        if (Fonts.hasCjk()) {
            assertThat(Fonts.usableForCjk(probed))
                .as("横幅报了『中文水印可用』，就不能是画不出中文的字体")
                .isTrue();
        } else {
            assertThat(Fonts.usableForAscii(probed))
                .as("退到 ASCII 兜底时，至少得是 glyf 轮廓")
                .isTrue();
        }
    }

    /** 造一份最小 PDF 作水印底稿（用 officia 自产，不依赖测试资源文件）。 */
    private static byte[] minimalPdf() {
        return plus.ruoyi.officia.cells.OfficiaCells.csvToPdf("a,b\n1,2\n");
    }

    /** 返回第一个存在的字体文件字节；都不存在返回 null（调用方 assume 跳过）。 */
    private static byte[] firstExisting(String[] paths) throws Exception {
        for (String p : paths) {
            File f = new File(p);
            if (f.isFile() && f.length() > 0) {
                return Files.readAllBytes(Path.of(p));
            }
        }
        return null;
    }
}
