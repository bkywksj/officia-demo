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

    /**
     * 运维字体目录（{@code OFFICIA_FONTS_DIR} / {@code -Dofficia.fonts.dir}）里的字体必须被探到。
     *
     * <p><b>线上实际误报</b>：msi 容器的中文字体不在系统路径，而是<b>挂载</b>到
     * {@code /opt/officia/fonts} 再用该变量指过去。此前探测只认硬编码路径表、漏掉它们，
     * 启动横幅报「系统无中文字体，走内置兜底」——而实际渲染早已用上挂载的字体。
     * 措辞与事实不符，会把人引向错误的排查方向（以为字体没生效）。</p>
     *
     * <p>用 {@code -D} 而不是环境变量来测：环境变量在 JVM 内改不了，而两者走同一段解析、
     * 且 {@code -D} 优先级更高，测它等价于测这条链路。</p>
     */
    @Test
    @DisplayName("运维字体目录里的字体应被列出")
    void operatorFontDirIsProbed() throws Exception {
        byte[] cjk = firstExisting(CJK);
        assumeThat(cjk).as("本机无 glyf 轮廓的中文字体，跳过").isNotNull();

        Path dir = Files.createTempDirectory("officia-fonts-probe");
        Path ttf = dir.resolve("probe-cjk.ttf");
        Path noise = dir.resolve("readme.txt");
        String prev = System.getProperty("officia.fonts.dir");
        try {
            Files.write(ttf, cjk);
            Files.writeString(noise, "许可说明，不是字体");
            System.setProperty("officia.fonts.dir", dir.toString());

            String[] found = Fonts.operatorFontFiles();
            assertThat(found).as("目录里的 .ttf 必须被列出——漏了它就会误报「系统无中文字体」")
                .contains(ttf.toString());
            assertThat(found).as("非字体文件（许可/README）不该混进来")
                .doesNotContain(noise.toString());
            assertThat(Fonts.usableForCjk(Files.readAllBytes(ttf)))
                .as("列出来的那份还得真能画中文").isTrue();
        } finally {
            if (prev == null) {
                System.clearProperty("officia.fonts.dir");
            } else {
                System.setProperty("officia.fonts.dir", prev);
            }
            Files.deleteIfExists(ttf);
            Files.deleteIfExists(noise);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("未配置运维目录时返回空，不报错")
    void operatorFontDirAbsentIsSafe() {
        String prev = System.getProperty("officia.fonts.dir");
        try {
            System.setProperty("officia.fonts.dir", "  ");
            assertThat(Fonts.operatorFontFiles()).as("空白配置应视同未配置").isEmpty();
            System.setProperty("officia.fonts.dir", "/this/dir/does/not/exist");
            assertThat(Fonts.operatorFontFiles()).as("目录不存在不该抛异常").isEmpty();
        } finally {
            if (prev == null) {
                System.clearProperty("officia.fonts.dir");
            } else {
                System.setProperty("officia.fonts.dir", prev);
            }
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
