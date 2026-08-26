package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.common.exception.OfficiaException;
import plus.ruoyi.officia.imaging.OfficiaImaging;
import plus.ruoyi.officia.ocr.OcrOptions;
import plus.ruoyi.officia.ocr.OfficiaOcr;
import plus.ruoyi.officia.ocr.recog.BuiltinModels;
import plus.ruoyi.officia.ocr.result.OcrLine;
import plus.ruoyi.officia.ocr.result.OcrResult;
import plus.ruoyi.officia.ocr.scan.ScannedPdfConverter;
import plus.ruoyi.officia.ooxml.opc.OpcPackage;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Officia.OCR 消费示例：图片 → 文字、扫描件 PDF → 可编辑 Word。
 *
 * <p>本类同时是一份<b>避坑清单</b>。OCR 与套件里其它能力有一点根本不同：
 * <b>它配错了不会报错</b>——识别器在固定字符集上永远给出某个类别，
 * 没有「认不出」这一档。所以下面几个测试除了演示用法，
 * 更重要的是把「什么时候会静默出错」摆出来。</p>
 *
 * @author officia-demo
 */
@DisplayName("OCR 能力消费示例")
class OcrDemoTest {

    /**
     * 现造一张白底黑字的文字 PNG。
     *
     * <p>先用临时画布量出实际尺寸再画——猜宽度会把最后一个字裁掉，
     * 而裁掉的字识别不出来，看上去像"识别错了"。</p>
     */
    private static byte[] textPng(String... lines) throws Exception {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 40);
        int pad = 10;
        int lineHeight = Math.round(40 * 1.6f);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fm.stringWidth(line));
        }
        int ascent = fm.getAscent();
        pg.dispose();

        BufferedImage img = new BufferedImage(textWidth + pad * 2,
                lineHeight * lines.length + pad * 2, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            for (int i = 0; i < lines.length; i++) {
                g.drawString(lines[i], pad, pad + ascent + i * lineHeight);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("票据号识别：给字符白名单，结果精确等于原文")
    void recognizeWithWhitelist() throws Exception {
        String truth = "20260825";

        // charWhitelist 会让门面改走模板匹配：候选塌缩到白名单内，
        // 是票据号/编号这类场景提准最划算的一招（模型的类别集训练时已固定，给不了这个能力）
        String text = OfficiaOcr.recognize(textPng(truth), OcrOptions.digitsOnly());

        assertThat(text).isEqualTo(truth);
    }

    @Test
    @DisplayName("中文识别：analyze 给出逐行文本 + 坐标 + 置信度")
    void analyzeChinese() throws Exception {
        OcrResult result = OfficiaOcr.analyze(textPng("第一行中文", "第二行 ABC 123"),
                OcrOptions.defaults().setLanguage(BuiltinModels.Language.CHINESE));

        // 只断言结构，不断言识别文本：合成图用什么字体取决于运行环境
        // （容器里可能压根没有中文字体，退成方框），硬断言会变成随环境飘的脆弱用例
        assertThat(result.lineCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.width()).isPositive();
        for (OcrLine line : result.lines()) {
            assertThat(line.box().width()).isPositive();
            assertThat(line.confidence()).isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("扫描件 PDF → 可编辑 Word（结构完整的 OOXML 包）")
    void scannedPdfToWord() throws Exception {
        byte[] scannedPdf = OfficiaImaging.toPdf(textPng("扫描件转 Word 示例", "第二行内容"));

        byte[] docx = new ScannedPdfConverter()
                .language(BuiltinModels.Language.CHINESE)
                .minConfidence(0.01)
                .toWord(scannedPdf);

        assertThat(docx).startsWith((byte) 0x50, (byte) 0x4B);   // PK：ZIP 容器
        OpcPackage pkg = OpcPackage.load(docx);
        assertThat(pkg.hasPart("word/document.xml")).isTrue();
    }

    /**
     * 🔴 语种必填 —— 这是 OCR 门面最重要的一条约定。
     *
     * <p>为什么宁可响亮失败：{@code OcrOptions} 的默认语种是<b>英文</b>，
     * 而中文扫描件走英文模型<b>不会报错</b>，只会吐出满篇拉丁噪声，
     * 且平均置信度<b>反而更高</b>（按置信度过滤会被它骗过去）。
     * 上游实测：整本 183 页中文书就这么白转出 25 万字垃圾，耗时 8.6 分钟、零报错。</p>
     */
    @Test
    @DisplayName("不指定语种直接转换会抛异常，而不是静默产出噪声")
    void languageIsMandatory() throws Exception {
        byte[] scannedPdf = OfficiaImaging.toPdf(textPng("中文内容"));

        assertThatThrownBy(() -> new ScannedPdfConverter().toWord(scannedPdf))
                .isInstanceOf(OfficiaException.class)
                .hasMessageContaining("语种");
    }

    /**
     * 🔴 版面参数配错同样不会报错，只会产出怪字——这条无法用断言表达，故写成文档。
     *
     * <p>实测同一本页面横放 + 两页并排扫描的书（183 页），同一份权重，只差版面参数：</p>
     * <pre>
     *   不给参数                          → 5.9 KB，全是乱码
     *   rotate(CLOCKWISE_90)+split(true) → 285.8 KB，9330 段、17.6 万字可读
     * </pre>
     *
     * <p>50 倍差距，而失败那次一样返回正常结果、不抛异常。
     * <b>每本书都要先转两三页看输出是不是人话</b>，再决定整本怎么转。</p>
     */
    @Test
    @DisplayName("版面参数用法：横放 + 对开扫描的书需要 rotate + splitFacingPages")
    void facingPagesUsage() throws Exception {
        byte[] scannedPdf = OfficiaImaging.toPdf(textPng("版面参数示例"));

        // 这里只演示参数怎么串（本样本是普通单页，不需要这些参数）
        byte[] docx = new ScannedPdfConverter()
                .language(BuiltinModels.Language.CHINESE)
                .rotate(ScannedPdfConverter.Rotation.NONE)
                .splitFacingPages(false)
                .minConfidence(0.01)
                .toWord(scannedPdf);

        assertThat(docx).startsWith((byte) 0x50, (byte) 0x4B);
    }
}
