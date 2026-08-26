package plus.ruoyi.officia.demo;

import plus.ruoyi.officia.barcode.OfficiaBarCode;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.email.EmailMessage;
import plus.ruoyi.officia.email.OfficiaEmail;
import plus.ruoyi.officia.imaging.OfficiaImaging;
import plus.ruoyi.officia.license.OfficiaLicense;
import plus.ruoyi.officia.ocr.OcrOptions;
import plus.ruoyi.officia.ocr.OfficiaOcr;
import plus.ruoyi.officia.ocr.recog.BuiltinModels;
import plus.ruoyi.officia.pdf.OfficiaPdf;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * officia 依赖消费演示（可 main 运行）。
 *
 * <p>展示：引入 officia-all + officia-license 后，直接调门面即可用；未配 License 处于评估态。</p>
 *
 * <p>刻意<b>不依赖任何外部样本文件</b>——用 officia 自己现造输入（CSV / PNG / EML），
 * 所以拿到 jar 直接 {@code java -jar} 就能跑，不用先去找一份 docx。</p>
 *
 * @author officia-demo
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==== Officia 依赖消费演示 ====");

        // 1) CSV → PDF
        byte[] pdf = OfficiaCells.csvToPdf("Name,Dept\nAlice,R&D\nBob,Product");
        System.out.println("[Cells] CSV→PDF: " + pdf.length + " 字节, 头="
            + new String(pdf, 0, 8, StandardCharsets.ISO_8859_1).trim());

        // 2) 公式求值
        Object sum = OfficiaCells.evaluateFormula(Map.of("A1", "10", "A2", "32"), "=A1+A2");
        System.out.println("[Cells] =A1+A2 = " + sum);

        // 3) PDF 合并 + 文本抽取（读写闭环：自己生成的 PDF 自己读回来）
        byte[] merged = OfficiaPdf.merge(List.of(pdf, OfficiaCells.csvToPdf("X,Y\n1,2")));
        System.out.println("[Pdf] 合并后 " + OfficiaPdf.pageCount(merged) + " 页，抽取文本含 Alice="
            + OfficiaPdf.extractText(merged).contains("Alice"));

        // 4) 条码 / 二维码
        System.out.println("[BarCode] QR PNG " + OfficiaBarCode.qrPng("https://officia.ruoyi.plus").length
            + " 字节, Code128 " + OfficiaBarCode.code128Png("OFFICIA-2026").length + " 字节");

        // 5) 图像处理
        byte[] png = textPng("20260826");
        System.out.println("[Imaging] 灰度滤镜 " + OfficiaImaging.grayscale(png).length + " 字节");

        // 6) 邮件往返
        EmailMessage mail = new EmailMessage().setSubject("Officia 演示")
            .setFrom("demo@ruoyi.plus").addTo("user@example.com").setTextBody("正文");
        System.out.println("[Email] EML 往返主题一致="
            + "Officia 演示".equals(OfficiaEmail.parseEml(OfficiaEmail.writeEml(mail)).getSubject()));

        // 7) OCR：图片 → 文字
        //    这里用【字符白名单】走确定性路径——票据号/编号场景就该这么用：
        //    候选被塌缩到白名单内，结果可精确校验。通用文档反而不要给白名单
        //    （会退到需要字体同源的模板匹配路径上）
        String text = OfficiaOcr.recognize(png, OcrOptions.digitsOnly()).strip();
        System.out.println("[OCR] 票据号识别: " + text + "（原文 20260826，"
            + ("20260826".equals(text) ? "精确命中" : "有出入") + "）");
        //    🔴 通用识别务必显式指定语种：OcrOptions 默认英文，中文图走英文模型
        //    【不会报错】，只会吐满篇拉丁噪声，且置信度反而更高
        OfficiaOcr.recognize(png, OcrOptions.defaults()
            .setLanguage(BuiltinModels.Language.CHINESE));

        // 8) 授权门面（未配 License → 评估态）
        System.out.println("[License] isLicensed = " + OfficiaLicense.isLicensed());
        System.out.println("[License] isEvaluation = " + OfficiaLicense.isEvaluation());
        System.out.println("[License] hasModule(words) = " + OfficiaLicense.hasModule("words"));

        System.out.println("==== 演示结束（引入 officia 依赖即可用，零传递第三方依赖） ====");
    }

    /**
     * 现造一张白底黑字的 PNG，供 Imaging 与 OCR 演示使用。
     *
     * <p>用等宽拉丁字体：中文字形在无中文字体的环境（如精简容器）里会退成方框，
     * 演示程序不该因为运行环境缺字体而看起来"识别错了"。</p>
     */
    private static byte[] textPng(String text) throws Exception {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 40);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        int w = pg.getFontMetrics().stringWidth(text) + 20;
        int ascent = pg.getFontMetrics().getAscent();
        int h = pg.getFontMetrics().getHeight() + 20;
        pg.dispose();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            g.drawString(text, 10, 10 + ascent);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
