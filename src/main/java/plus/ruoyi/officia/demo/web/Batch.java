package plus.ruoyi.officia.demo.web;

import plus.ruoyi.officia.barcode.OfficiaBarCode;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.email.EmailMessage;
import plus.ruoyi.officia.email.OfficiaEmail;
import plus.ruoyi.officia.imaging.OfficiaImaging;
import plus.ruoyi.officia.ocr.OcrOptions;
import plus.ruoyi.officia.ocr.OfficiaOcr;
import plus.ruoyi.officia.ocr.recog.BuiltinModels;
import plus.ruoyi.officia.ocr.result.OcrResult;
import plus.ruoyi.officia.ocr.scan.ScannedPdfConverter;
import plus.ruoyi.officia.license.OfficiaLicense;
import plus.ruoyi.officia.pdf.OfficiaPdf;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量回归：不依赖外部样本，用 officia 自身现造输入（CSV/PDF/PNG/EML），
 * 跑一遍各能力并对<b>结构性事实</b>断言（%PDF- 头、页数、PNG 魔数、往返一致），
 * 与项目单测同一套判定口径（见 test-development 技能）。
 *
 * @author officia-demo
 */
final class Batch {

    private Batch() {
    }

    /** 跑完整回归，返回逐条结果 + 汇总（全部为局部状态，可并发调用）。 */
    static Json run() {
        List<Case> cases = new ArrayList<>();
        long t0all = System.nanoTime();

        // 造数据
        StringBuilder csv = new StringBuilder("编号,姓名,部门,城市,金额\n");
        for (int i = 1; i <= 150; i++) {
            csv.append(i).append(",员工").append(i).append(",研发部,深圳,").append(1000 + i * 7).append('\n');
        }
        String csvText = csv.toString();

        // 1) CSV → PDF
        cases.add(run("Cells", "CSV(150行) → PDF", () -> {
            byte[] pdf = OfficiaCells.csvToPdf(csvText);
            assertPdf(pdf);
            int pages = OfficiaPdf.pageCount(pdf);
            return "PDF " + pages + " 页 / " + pdf.length + " B";
        }));

        // 2) CSV 解析（RFC4180）
        cases.add(run("Cells", "CSV 解析 parseCsv", () -> {
            List<List<String>> rs = OfficiaCells.parseCsv(csvText);
            if (rs.size() != 151) {
                throw new AssertionError("期望 151 行(含表头)，实际 " + rs.size());
            }
            return rs.size() + " 行 × " + rs.get(0).size() + " 列";
        }));

        // 3) PDF 合并
        cases.add(run("Pdf", "两份 PDF 合并 merge", () -> {
            byte[] a = OfficiaCells.csvToPdf("A,B\n1,2");
            byte[] b = OfficiaCells.csvToPdf("C,D\n3,4");
            byte[] m = OfficiaPdf.merge(List.of(a, b));
            assertPdf(m);
            int p = OfficiaPdf.pageCount(m);
            if (p < 2) {
                throw new AssertionError("合并后页数应 ≥2，实际 " + p);
            }
            return "合并 " + p + " 页";
        }));

        // 4) PDF 文本抽取（生成→读取闭环）
        cases.add(run("Pdf", "文本抽取 extractText", () -> {
            byte[] pdf = OfficiaCells.csvToPdf("标题,数值\n合计,12800");
            String text = OfficiaPdf.extractText(pdf);
            if (text == null || !text.contains("12800")) {
                throw new AssertionError("抽取文本未包含 12800");
            }
            return "命中关键字，长度 " + text.length();
        }));

        // 5) AES-256 加密
        cases.add(run("Pdf", "AES-256 加密 encryptAes256", () -> {
            byte[] pdf = OfficiaCells.csvToPdf("Secret,Value\nkey,42");
            byte[] enc = OfficiaPdf.encryptAes256(pdf, "openpwd", "ownerpwd");
            assertPdf(enc);
            if (!OfficiaPdf.isEncrypted(enc)) {
                throw new AssertionError("加密后 isEncrypted 应为 true");
            }
            return "加密 " + enc.length + " B，isEncrypted=true";
        }));

        // 5.1) PDF → Word
        cases.add(run("Pdf", "PDF → Word toWord", () -> {
            byte[] pdf = OfficiaCells.csvToPdf("项目,金额\n服务费,12800\n合计,12800");
            byte[] docx = OfficiaPdf.toWord(pdf);
            if (docx.length < 4 || docx[0] != (byte) 0x50 || docx[1] != (byte) 0x4B) {
                throw new AssertionError("产物不是 ZIP 容器（docx 必须以 PK 开头）");
            }
            String xml = new String(plus.ruoyi.officia.ooxml.opc.OpcPackage.load(docx)
                    .getPartBytes("word/document.xml"), java.nio.charset.StandardCharsets.UTF_8);
            if (!xml.contains("12800")) {
                throw new AssertionError("转出的 Word 里丢了原文数字");
            }
            return "docx " + docx.length + " B，文本存活";
        }));

        // 5.2) 转出的 docx 必须是结构完整的 OOXML 包——缺一个部件 Word 就判定文件损坏
        //
        // 这里刻意不测"扫描件被拒绝"：评估态下 OfficiaImaging.toPdf 会给图片 PDF 加水印文字，
        // 造不出真正无文字层的样本，硬测只会得到一个前提不成立的用例。
        // 那条边界由单测 PdfToWordTest 覆盖（用 PdfDocument 直接造空白页，构造可控）。
        cases.add(run("Pdf", "转 Word 产物容器完整性", () -> {
            byte[] docx = OfficiaPdf.toWord(OfficiaCells.csvToPdf("列A,列B\n值1,值2"));
            var pkg = plus.ruoyi.officia.ooxml.opc.OpcPackage.load(docx);
            String[] required = {"[Content_Types].xml", "_rels/.rels", "word/document.xml",
                    "word/_rels/document.xml.rels", "word/styles.xml"};
            for (String part : required) {
                if (!pkg.hasPart(part)) {
                    throw new AssertionError("docx 缺少必需部件：" + part);
                }
            }
            return required.length + " 个必需部件齐全";
        }));

        // 6) 条码 Code128 + QR
        cases.add(run("BarCode", "Code128 / QR → PNG", () -> {
            assertPng(OfficiaBarCode.code128Png("OFFICIA-2026"));
            byte[] qr = OfficiaBarCode.qrPng("https://ruoyi.plus");
            assertPng(qr);
            return "Code128 ✓ / QR " + qr.length + " B";
        }));

        // 7) 图像滤镜 + 图片→PDF
        cases.add(run("Imaging", "灰度滤镜 + 图片→PDF", () -> {
            byte[] png = samplePng();
            byte[] gray = OfficiaImaging.grayscale(png);
            assertPng(gray);
            byte[] pdf = OfficiaImaging.toPdf(gray);
            assertPdf(pdf);
            return "灰度 ✓ / 图片PDF " + OfficiaPdf.pageCount(pdf) + " 页";
        }));

        // 8) EML 生成 → 解析往返
        cases.add(run("Email", "EML 生成 → 解析往返", () -> {
            EmailMessage m = new EmailMessage().setSubject("Officia 回归测试")
                .setFrom("demo@ruoyi.plus").addTo("user@example.com").setTextBody("正文内容");
            byte[] eml = OfficiaEmail.writeEml(m);
            EmailMessage back = OfficiaEmail.parseEml(eml);
            if (!"Officia 回归测试".equals(back.getSubject())) {
                throw new AssertionError("往返主题不一致：" + back.getSubject());
            }
            return "主题往返一致";
        }));

        // 9) EML → PDF 归档
        cases.add(run("Email", "EML → PDF 归档", () -> {
            EmailMessage m = new EmailMessage().setSubject("归档邮件")
                .setFrom("demo@ruoyi.plus").addTo("user@example.com").setTextBody("这是一封归档测试邮件。");
            byte[] pdf = OfficiaEmail.toPdf(OfficiaEmail.writeEml(m));
            assertPdf(pdf);
            return "PDF " + OfficiaPdf.pageCount(pdf) + " 页";
        }));

        // 9.1) OCR 模板匹配：渲染已知数字 → 识别 → 断言【精确等于】原文。
        //      这条是确定性的：渲染与匹配用同一个逻辑字体，白名单又把候选塌缩到 10 个，
        //      结果只有对与不对两种，没有"差不多"。它锁住的是预处理→切分→识别整条链路。
        cases.add(run("Ocr", "模板匹配识别数字（精确断言）", () -> {
            String truth = "20260823";
            String got = OfficiaOcr.recognize(textPng(new String[]{truth}), OcrOptions.digitsOnly());
            if (!truth.equals(got)) {
                throw new AssertionError("识别结果不等于原文：期望 " + truth + "，实际 " + got);
            }
            return "精确命中 " + truth;
        }));

        // 9.2) 扫描件 PDF → Word 全链路（图片 PDF → 抽图 → 切分 → 神经网络识别 → 写 docx）。
        //
        //      这条<b>只断言结构</b>，不断言识别文本。原因：合成图用什么字体渲染取决于运行环境
        //      （容器里可能压根没有中文字体，退成方框），与模型训练分布不同分布；
        //      而 CTC 没有"认不出"这个输出，任何输入它都会自信地给一串字。
        //      硬断言文本会得到一条随环境飘的脆弱用例——识别质量该由 tools/ocr-eval 的
        //      六条验收链在真实件上量，不该由这里的合成图冒充。
        cases.add(run("Ocr", "扫描件 PDF → Word（结构断言）", () -> {
            byte[] png = textPng(new String[]{"第一行中文测试", "第二行 ABC 123", "第三行结束"});
            OcrResult probe = OfficiaOcr.analyze(png,
                OcrOptions.defaults().setLanguage(BuiltinModels.Language.CHINESE));
            if (probe.lineCount() == 0) {
                throw new AssertionError("切分层没切出任何文本行");
            }
            byte[] docx = new ScannedPdfConverter()
                .language(BuiltinModels.Language.CHINESE)
                .minConfidence(0.01)
                .toWord(OfficiaImaging.toPdf(png));
            if (docx.length < 4 || docx[0] != (byte) 0x50 || docx[1] != (byte) 0x4B) {
                throw new AssertionError("产物不是 ZIP 容器（docx 必须以 PK 开头）");
            }
            if (!plus.ruoyi.officia.ooxml.opc.OpcPackage.load(docx).hasPart("word/document.xml")) {
                throw new AssertionError("docx 缺少 word/document.xml");
            }
            // 识别文本只回填给人看，不参与判定（见上面为什么）
            return "切出 " + probe.lineCount() + " 行，docx " + docx.length + " B｜识别："
                + probe.text().replace('\n', '/');
        }));

        // 10) 授权门控：评估 vs 授权（同一份数据的页数差）
        cases.add(run("License", "门控：评估降级 vs 完整", () -> {
            boolean wasEnforced = OfficiaLicense.isEnforced();
            OfficiaLicense.enableEnforcement(true);
            int evalPages = OfficiaPdf.pageCount(OfficiaCells.csvToPdf(csvText));
            OfficiaLicense.enableEnforcement(wasEnforced);
            int fullPages = OfficiaPdf.pageCount(OfficiaCells.csvToPdf(csvText));
            return "强制执行下 " + evalPages + " 页 / 当前态 " + fullPages + " 页"
                + (OfficiaLicense.isLicensed() ? "（已授权）" : "（未授权）");
        }));

        // 汇总：直接由结果列表统计，无共享可变状态
        int pass = 0;
        List<Object> rows = new ArrayList<>(cases.size());
        for (Case c : cases) {
            if (c.ok) {
                pass++;
            }
            rows.add(c.json());
        }
        long totalMs = (System.nanoTime() - t0all) / 1_000_000;
        return Json.obj().put("total", cases.size()).put("pass", pass).put("fail", cases.size() - pass)
            .put("totalMs", totalMs).put("rows", rows);
    }

    // ==================== 内部 ====================

    private interface Body {
        String run() throws Exception;
    }

    /** 单条用例结果（不可变快照，无共享状态 → 并发调用互不干扰）。 */
    private static final class Case {
        final String module;
        final String name;
        final boolean ok;
        final String detail;
        final long ms;

        Case(String module, String name, boolean ok, String detail, long ms) {
            this.module = module;
            this.name = name;
            this.ok = ok;
            this.detail = detail;
            this.ms = ms;
        }

        Json json() {
            return Json.obj().put("module", module).put("name", name)
                .put("ok", ok).put("detail", detail).put("ms", ms);
        }
    }

    private static Case run(String module, String name, Body body) {
        long t0 = System.nanoTime();
        boolean ok;
        String detail;
        try {
            detail = body.run();
            ok = true;
        } catch (Throwable t) {
            ok = false;
            detail = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return new Case(module, name, ok, detail, (System.nanoTime() - t0) / 1_000_000);
    }

    private static void assertPdf(byte[] pdf) {
        if (pdf == null || pdf.length < 5
            || !new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF-")) {
            throw new AssertionError("输出不是合法 PDF（缺 %PDF- 头）");
        }
    }

    private static void assertPng(byte[] png) {
        if (png == null || png.length < 4 || (png[0] & 0xFF) != 0x89
            || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new AssertionError("输出不是合法 PNG（缺魔数）");
        }
    }

    /**
     * 现造一张文字 PNG（白底黑字，多行左对齐），供 OCR 用例作输入。
     *
     * <p>配方与 officia-ocr 单测一致：先用临时画布量出实际尺寸再画，
     * 猜宽度会把最后一个字裁掉——而裁掉的字识别不出来，看上去像"识别错了"。</p>
     */
    private static byte[] textPng(String[] lines) throws Exception {
        Font font = new Font(Font.MONOSPACED, Font.PLAIN, 40);
        int pad = 10;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int lineHeight = Math.round(40 * 1.6f);
        int textWidth = 0;
        for (String line : lines) {
            textWidth = Math.max(textWidth, fm.stringWidth(line));
        }
        int ascent = fm.getAscent();
        pg.dispose();

        int w = textWidth + pad * 2;
        int h = lineHeight * lines.length + pad * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
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

    /** 现造一张 PNG 样本（不依赖外部素材）。 */
    static byte[] samplePng() throws Exception {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x1E40AF));
        g.fillRect(0, 0, 320, 180);
        g.setColor(Color.WHITE);
        g.fillOval(40, 30, 120, 120);
        g.setColor(new Color(0x0891B2));
        g.fillRect(180, 40, 100, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
