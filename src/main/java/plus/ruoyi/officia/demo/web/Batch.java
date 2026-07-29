package plus.ruoyi.officia.demo.web;

import plus.ruoyi.officia.barcode.OfficiaBarCode;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.email.EmailMessage;
import plus.ruoyi.officia.email.OfficiaEmail;
import plus.ruoyi.officia.imaging.OfficiaImaging;
import plus.ruoyi.officia.license.OfficiaLicense;
import plus.ruoyi.officia.pdf.OfficiaPdf;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
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
