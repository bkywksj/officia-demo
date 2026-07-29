package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;
import plus.ruoyi.officia.barcode.OfficiaBarCode;
import plus.ruoyi.officia.barcode.qr.QrEcc;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.common.json.MiniJson;
import plus.ruoyi.officia.email.EmailMessage;
import plus.ruoyi.officia.email.OfficiaEmail;
import plus.ruoyi.officia.imaging.OfficiaImaging;
import plus.ruoyi.officia.license.OfficiaLicense;
import plus.ruoyi.officia.pdf.OfficiaPdf;
import plus.ruoyi.officia.slides.OfficiaSlides;
import plus.ruoyi.officia.words.OfficiaWords;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试台 API：把 HTTP 请求转成对 officia 各门面（OfficiaXxx）的真实调用。
 *
 * <p>约定：源文件先 POST /api/upload 拿 id，后续操作只传 id（多输入如 PDF 合并天然支持）；
 * 产出统一进 {@link Store} 并返回元信息（id/大小/页数/耗时），前端据此预览或下载。</p>
 *
 * @author officia-demo
 */
final class ApiRoutes {

    private ApiRoutes() {
    }

    /** 分发一条 /api/* 请求；返回 true 表示已处理。 */
    static boolean dispatch(HttpExchange ex, String path, Map<String, String> q) throws Exception {
        switch (path) {
            case "/api/health":
                Http.json(ex, Json.obj().put("ok", true).put("blobs", Store.size()).end());
                return true;
            case "/api/upload":
                upload(ex, q);
                return true;
            case "/api/samples":
                samples(ex);
                return true;

            // ---- 授权 ----
            case "/api/license/status":
                Http.json(ex, licenseStatus().end());
                return true;
            case "/api/license/set": {
                String token = new String(Http.body(ex), StandardCharsets.UTF_8).trim();
                OfficiaLicense.setLicense(token);
                Http.json(ex, licenseStatus().end());
                return true;
            }
            case "/api/license/reset":
                OfficiaLicense.reset();
                Http.json(ex, licenseStatus().end());
                return true;
            case "/api/license/enforce":
                OfficiaLicense.enableEnforcement(!"false".equals(q.get("on")));
                Http.json(ex, licenseStatus().end());
                return true;

            // ---- Words ----
            case "/api/words/topdf": {
                byte[] src = Store.bytes(q.get("id"));
                long t0 = System.nanoTime();
                byte[] pdf;
                if ("stream".equals(q.get("mode"))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    OfficiaWords.toPdf(src, out);      // 流式直写，省峰值内存
                    pdf = out.toByteArray();
                } else {
                    pdf = OfficiaWords.toPdf(src);     // 自动识别 DOCX(OOXML) / DOC(CFB)
                }
                Http.json(ex, result("words.pdf", "application/pdf", pdf, t0).end());
                return true;
            }
            case "/api/words/template": {
                byte[] tpl = Store.bytes(q.get("tplId"));
                String json = new String(Http.body(ex), StandardCharsets.UTF_8).trim();
                String mode = q.getOrDefault("mode", "single");
                long t0 = System.nanoTime();
                if ("each".equals(mode)) {
                    // fillTemplateEachToPdf 只有 List 重载，故先用 officia 自带 MiniJson 把 JSON 数组解析成 List<Map>
                    List<Map<String, Object>> dataList = new ArrayList<>();
                    for (Object o : MiniJson.parseArray(json)) {
                        if (!(o instanceof Map)) {
                            throw new IllegalArgumentException("JSON 数组元素须为对象");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) o;
                        dataList.add(m);
                    }
                    List<byte[]> list = OfficiaWords.fillTemplateEachToPdf(tpl, dataList);
                    List<Object> arr = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        Store.Blob b = store("填充结果-" + (i + 1) + ".pdf", "application/pdf", list.get(i), t0);
                        arr.add(b.toJson());
                    }
                    Http.json(ex, Json.obj().put("multi", true).put("files", arr).end());
                } else if ("merged".equals(mode)) {
                    byte[] pdf = OfficiaWords.fillTemplateMergedToPdf(tpl, json);
                    Http.json(ex, result("邮件合并.pdf", "application/pdf", pdf, t0).end());
                } else if ("docx".equals(mode)) {
                    byte[] docx = OfficiaWords.fillTemplate(tpl, json);
                    Http.json(ex, result("填充结果.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx, t0).end());
                } else {
                    byte[] pdf = OfficiaWords.fillTemplateToPdf(tpl, json);
                    Http.json(ex, result("填充结果.pdf", "application/pdf", pdf, t0).end());
                }
                return true;
            }

            // ---- Cells ----
            case "/api/cells/topdf": {
                long t0 = System.nanoTime();
                byte[] pdf = OfficiaCells.toPdf(Store.bytes(q.get("id")));
                Http.json(ex, result("cells.pdf", "application/pdf", pdf, t0).end());
                return true;
            }
            case "/api/cells/tocsv": {
                long t0 = System.nanoTime();
                String csv = OfficiaCells.toCsv(Store.bytes(q.get("id")));
                Http.json(ex, result("cells.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8), t0)
                    .put("text", csv.length() > 20000 ? csv.substring(0, 20000) + "\n…" : csv).end());
                return true;
            }
            case "/api/cells/csvtopdf": {
                String csv = new String(Http.body(ex), StandardCharsets.UTF_8);
                long t0 = System.nanoTime();
                byte[] pdf = OfficiaCells.csvToPdf(csv);
                Http.json(ex, result("csv.pdf", "application/pdf", pdf, t0).end());
                return true;
            }
            case "/api/cells/parsecsv": {
                String csv = new String(Http.body(ex), StandardCharsets.UTF_8);
                List<List<String>> rows = OfficiaCells.parseCsv(csv);
                List<Object> out = new ArrayList<>();
                for (List<String> r : rows) {
                    out.add(new ArrayList<Object>(r));
                }
                Http.json(ex, Json.obj().put("rows", out).end());
                return true;
            }
            case "/api/cells/formula": {
                String cell = q.get("cell");
                Object v = OfficiaCells.evaluateXlsxCell(Store.bytes(q.get("id")), cell);
                Http.json(ex, Json.obj().put("cell", cell).put("value", String.valueOf(v)).end());
                return true;
            }
            case "/api/cells/recalc": {
                Map<String, Object> m = OfficiaCells.recalculateXlsx(Store.bytes(q.get("id")));
                Map<String, Object> shown = new LinkedHashMap<>();
                int n = 0;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (n++ >= 200) {
                        break;
                    }
                    shown.put(e.getKey(), String.valueOf(e.getValue()));
                }
                Http.json(ex, Json.obj().put("count", m.size()).put("cells", shown).end());
                return true;
            }

            // ---- Slides ----
            case "/api/slides/topdf": {
                byte[] src = Store.bytes(q.get("id"));
                long t0 = System.nanoTime();
                byte[] pdf = "true".equals(q.get("layout"))
                    ? OfficiaSlides.toPdfLayoutAware(src) : OfficiaSlides.toPdf(src);
                Http.json(ex, result("slides.pdf", "application/pdf", pdf, t0).end());
                return true;
            }

            // ---- PDF ----
            case "/api/pdf/info": {
                byte[] pdf = Store.bytes(q.get("id"));
                List<float[]> sizes = OfficiaPdf.pageSizes(pdf);
                String size = sizes.isEmpty() ? "-"
                    : Math.round(sizes.get(0)[0]) + "×" + Math.round(sizes.get(0)[1]);
                Http.json(ex, Json.obj().put("pages", OfficiaPdf.pageCount(pdf))
                    .put("encrypted", OfficiaPdf.isEncrypted(pdf)).put("firstPageSize", size).end());
                return true;
            }
            case "/api/pdf/merge": {
                List<byte[]> list = new ArrayList<>();
                for (String id : q.getOrDefault("ids", "").split(",")) {
                    if (!id.isBlank()) {
                        list.add(Store.bytes(id.trim()));
                    }
                }
                long t0 = System.nanoTime();
                byte[] pdf = OfficiaPdf.merge(list);
                Http.json(ex, result("合并.pdf", "application/pdf", pdf, t0).end());
                return true;
            }
            case "/api/pdf/split": {
                long t0 = System.nanoTime();
                List<byte[]> parts = OfficiaPdf.split(Store.bytes(q.get("id")));
                List<Object> arr = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    arr.add(store("第" + (i + 1) + "页.pdf", "application/pdf", parts.get(i), t0).toJson());
                }
                Http.json(ex, Json.obj().put("multi", true).put("files", arr).end());
                return true;
            }
            case "/api/pdf/pages": {
                byte[] pdf = Store.bytes(q.get("id"));
                int[] idx = parseInts(q.get("pages"));
                long t0 = System.nanoTime();
                byte[] out = "remove".equals(q.get("op"))
                    ? OfficiaPdf.removePages(pdf, idx) : OfficiaPdf.extractPages(pdf, idx);
                Http.json(ex, result("页面处理.pdf", "application/pdf", out, t0).end());
                return true;
            }
            case "/api/pdf/rotate": {
                long t0 = System.nanoTime();
                byte[] out = OfficiaPdf.rotate(Store.bytes(q.get("id")),
                    Integer.parseInt(q.getOrDefault("deg", "90")));
                Http.json(ex, result("旋转.pdf", "application/pdf", out, t0).end());
                return true;
            }
            case "/api/pdf/watermark": {
                byte[] pdf = Store.bytes(q.get("id"));
                String text = q.getOrDefault("text", "CONFIDENTIAL");
                byte[] font = fontBytes(q.get("fontId"));
                long t0 = System.nanoTime();
                byte[] out = font != null ? OfficiaPdf.watermark(pdf, text, font) : OfficiaPdf.watermark(pdf, text);
                Http.json(ex, result("水印.pdf", "application/pdf", out, t0).end());
                return true;
            }
            case "/api/pdf/pagenumbers": {
                byte[] pdf = Store.bytes(q.get("id"));
                String fmt = q.getOrDefault("format", "{page} / {total}");
                byte[] font = fontBytes(q.get("fontId"));
                long t0 = System.nanoTime();
                byte[] out = font != null ? OfficiaPdf.addPageNumbers(pdf, fmt, font)
                    : OfficiaPdf.addPageNumbers(pdf, fmt);
                Http.json(ex, result("页码.pdf", "application/pdf", out, t0).end());
                return true;
            }
            case "/api/pdf/text": {
                byte[] pdf = Store.bytes(q.get("id"));
                List<String> byPage = OfficiaPdf.extractTextByPage(pdf);
                Http.json(ex, Json.obj().put("pages", byPage.size())
                    .put("text", new ArrayList<Object>(byPage)).end());
                return true;
            }
            case "/api/pdf/images": {
                long t0 = System.nanoTime();
                List<byte[]> imgs = OfficiaPdf.extractImages(Store.bytes(q.get("id")));
                List<Object> arr = new ArrayList<>();
                for (int i = 0; i < imgs.size(); i++) {
                    arr.add(store("图片-" + (i + 1) + ".png", "image/png", imgs.get(i), t0).toJson());
                }
                Http.json(ex, Json.obj().put("multi", true).put("files", arr).end());
                return true;
            }
            case "/api/pdf/encrypt": {
                byte[] pdf = Store.bytes(q.get("id"));
                String user = q.getOrDefault("user", "open");
                String owner = q.getOrDefault("owner", "owner");
                int bits = Integer.parseInt(q.getOrDefault("bits", "256"));
                long t0 = System.nanoTime();
                byte[] out = bits == 256 ? OfficiaPdf.encryptAes256(pdf, user, owner)
                    : OfficiaPdf.encrypt(pdf, user, owner, bits);
                Http.json(ex, result("加密.pdf", "application/pdf", out, t0)
                    .put("algo", bits == 256 ? "AES-256 (AESV3)" : "RC4-" + bits).end());
                return true;
            }

            // ---- Imaging ----
            case "/api/imaging/op": {
                byte[] src = Store.bytes(q.get("id"));
                long t0 = System.nanoTime();
                byte[] out = imaging(src, q);
                String mime = "convert".equals(q.get("op"))
                    ? "image/" + q.getOrDefault("format", "png").toLowerCase() : "image/png";
                Http.json(ex, result("处理结果." + mime.substring(mime.indexOf('/') + 1), mime, out, t0).end());
                return true;
            }
            case "/api/imaging/topdf": {
                List<byte[]> imgs = new ArrayList<>();
                for (String id : q.getOrDefault("ids", "").split(",")) {
                    if (!id.isBlank()) {
                        imgs.add(Store.bytes(id.trim()));
                    }
                }
                long t0 = System.nanoTime();
                byte[] pdf = OfficiaImaging.toPdf(imgs);
                Http.json(ex, result("图片.pdf", "application/pdf", pdf, t0).end());
                return true;
            }

            // ---- BarCode ----
            case "/api/barcode": {
                long t0 = System.nanoTime();
                byte[] png = barcode(q);
                Http.json(ex, result(q.getOrDefault("type", "qr") + ".png", "image/png", png, t0).end());
                return true;
            }

            // ---- Email ----
            case "/api/email/parse": {
                EmailMessage m = OfficiaEmail.parseEml(Store.bytes(q.get("id")));
                Json j = Json.obj().put("subject", m.getSubject()).put("from", m.getFrom())
                    .put("to", String.join(", ", nz(m.getTo()))).put("date", String.valueOf(m.getDate()));
                List<Object> atts = new ArrayList<>();
                if (m.getAttachments() != null) {
                    m.getAttachments().forEach(a -> atts.add(Json.obj()
                        .put("name", a.getFilename()).put("size", a.getSize())));
                }
                String body = m.getHtmlBody() != null && !m.getHtmlBody().isBlank() ? m.getHtmlBody() : m.getTextBody();
                Http.json(ex, j.put("attachments", atts).put("body", body == null ? "" : body).end());
                return true;
            }
            case "/api/email/topdf": {
                long t0 = System.nanoTime();
                byte[] pdf = OfficiaEmail.toPdf(Store.bytes(q.get("id")));
                Http.json(ex, result("邮件归档.pdf", "application/pdf", pdf, t0).end());
                return true;
            }

            case "/api/batch/run":
                Http.json(ex, Batch.run().end());
                return true;
            default:
                return false;
        }
    }

    // ==================== 具体能力 ====================

    private static byte[] imaging(byte[] src, Map<String, String> q) {
        String op = q.getOrDefault("op", "grayscale");
        int a = Integer.parseInt(q.getOrDefault("a", "0"));
        int b = Integer.parseInt(q.getOrDefault("b", "0"));
        switch (op) {
            case "grayscale":  return OfficiaImaging.grayscale(src);
            case "sepia":      return OfficiaImaging.sepia(src);
            case "invert":     return OfficiaImaging.invert(src);
            case "binarize":   return OfficiaImaging.binarize(src, a == 0 ? 128 : a);
            case "blur":       return OfficiaImaging.blur(src, a == 0 ? 3 : a);
            case "sharpen":    return OfficiaImaging.sharpen(src);
            case "brightness": return OfficiaImaging.brightness(src, a == 0 ? 30 : a);
            case "contrast":   return OfficiaImaging.contrast(src, Float.parseFloat(q.getOrDefault("f", "1.4")));
            case "posterize":  return OfficiaImaging.posterize(src, a == 0 ? 4 : a);
            case "resize":     return OfficiaImaging.resize(src, a == 0 ? 640 : a, b == 0 ? 360 : b);
            case "crop":       return OfficiaImaging.crop(src, a, b,
                Integer.parseInt(q.getOrDefault("w", "200")), Integer.parseInt(q.getOrDefault("h", "200")));
            case "rotate":     return OfficiaImaging.rotate(src, Double.parseDouble(q.getOrDefault("deg", "90")));
            case "flipH":      return OfficiaImaging.flipHorizontal(src);
            case "flipV":      return OfficiaImaging.flipVertical(src);
            case "convert":    return OfficiaImaging.convert(src, q.getOrDefault("format", "jpg"));
            case "textmark":   return OfficiaImaging.textWatermark(src, q.getOrDefault("text", "Officia Demo"),
                a, b == 0 ? 40 : b, Float.parseFloat(q.getOrDefault("size", "28")), 0x808080,
                Float.parseFloat(q.getOrDefault("opacity", "0.5")));
            case "imgmark":    return OfficiaImaging.imageWatermark(src, Store.bytes(q.get("markId")), a, b,
                Float.parseFloat(q.getOrDefault("opacity", "0.5")));
            default: throw new IllegalArgumentException("未知图像操作: " + op);
        }
    }

    private static byte[] barcode(Map<String, String> q) {
        String type = q.getOrDefault("type", "qr");
        String text = q.getOrDefault("text", "https://ruoyi.plus");
        int mod = Integer.parseInt(q.getOrDefault("module", "8"));
        int quiet = Integer.parseInt(q.getOrDefault("quiet", "4"));
        int h = Integer.parseInt(q.getOrDefault("height", "80"));
        boolean withText = "true".equals(q.get("withText"));
        switch (type) {
            case "qr":
                return OfficiaBarCode.qrPng(text, eccOf(q.getOrDefault("ecc", "M")), mod, quiet);
            case "code128": return withText ? OfficiaBarCode.code128PngText(text) : OfficiaBarCode.code128Png(text, mod, h, quiet);
            case "code39":  return withText ? OfficiaBarCode.code39PngText(text)  : OfficiaBarCode.code39Png(text, mod, h, quiet);
            case "code93":  return withText ? OfficiaBarCode.code93PngText(text)  : OfficiaBarCode.code93Png(text, mod, h, quiet);
            case "ean13":   return withText ? OfficiaBarCode.ean13PngText(text)   : OfficiaBarCode.ean13Png(text, mod, h, quiet);
            case "ean8":    return withText ? OfficiaBarCode.ean8PngText(text)    : OfficiaBarCode.ean8Png(text, mod, h, quiet);
            case "upca":    return OfficiaBarCode.upcaPng(text);
            case "itf14":   return withText ? OfficiaBarCode.itf14PngText(text)   : OfficiaBarCode.itf14Png(text, mod, h, quiet);
            default: throw new IllegalArgumentException("未知码制: " + type);
        }
    }

    private static QrEcc eccOf(String s) {
        switch (s.toUpperCase()) {
            case "L": return QrEcc.L;
            case "Q": return QrEcc.Q;
            case "H": return QrEcc.H;
            default:  return QrEcc.M;
        }
    }

    // ==================== 通用 ====================

    private static void upload(HttpExchange ex, Map<String, String> q) throws Exception {
        byte[] data = Http.body(ex);
        String name = q.getOrDefault("name", "upload.bin");
        Store.Blob b = Store.put(name, Http.mimeOf(name), data);
        if (name.toLowerCase().endsWith(".pdf")) {
            try {
                b.pages = OfficiaPdf.pageCount(data);
            } catch (RuntimeException ignore) {
                // 加密或异常 PDF：页数留 -1，不影响上传
            }
        }
        Http.json(ex, b.toJson().end());
    }

    /** 生成内置样本（officia 自产自销：CSV/PDF/PNG/EML 都能现造，docx/xlsx/pptx 需用户上传）。 */
    private static void samples(HttpExchange ex) throws Exception {
        List<Object> arr = new ArrayList<>();
        StringBuilder csv = new StringBuilder("编号,姓名,部门,城市,金额\n");
        for (int i = 1; i <= 120; i++) {
            csv.append(i).append(",员工").append(i).append(",研发部,深圳,").append(1000 + i * 7).append('\n');
        }
        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        arr.add(Store.put("样本-120行.csv", "text/csv", csvBytes).toJson());

        byte[] pdf = OfficiaCells.csvToPdf(csv.toString());
        Store.Blob pb = Store.put("样本-多页.pdf", "application/pdf", pdf);
        try {
            pb.pages = OfficiaPdf.pageCount(pdf);
        } catch (RuntimeException ignore) {
            // 评估降级下仍可用，忽略页数异常
        }
        arr.add(pb.toJson());

        arr.add(Store.put("样本-二维码.png", "image/png",
            OfficiaBarCode.qrPng("https://ruoyi.plus")).toJson());

        EmailMessage m = new EmailMessage().setSubject("Officia 授权确认")
            .setFrom("抓蛙师 <770492966@qq.com>").addTo("zhangsan@example.com")
            .setTextBody("您好，贵司购买的 Officia 全能版授权已签发，附件为 License 文件。");
        arr.add(Store.put("样本-邮件.eml", "message/rfc822", OfficiaEmail.writeEml(m)).toJson());

        Http.json(ex, Json.obj().put("samples", arr).end());
    }

    private static Json licenseStatus() {
        List<Object> mods = new ArrayList<>();
        for (String m : new String[]{"words", "cells", "slides", "pdf", "email", "imaging", "barcode", "total"}) {
            mods.add(Json.obj().put("code", m).put("granted", OfficiaLicense.hasModule(m)));
        }
        long exp = OfficiaLicense.getExpiresEpoch();
        return Json.obj()
            .put("licensed", OfficiaLicense.isLicensed())
            .put("evaluation", OfficiaLicense.isEvaluation())
            .put("enforced", OfficiaLicense.isEnforced())
            .put("licensee", OfficiaLicense.getLicensee())
            .put("edition", OfficiaLicense.getEdition())
            .put("expires", exp == 0 ? "永久 / 未知" : java.time.Instant.ofEpochSecond(exp).toString())
            .put("modules", mods);
    }

    private static Store.Blob store(String name, String mime, byte[] data, long t0) {
        Store.Blob b = Store.put(name, mime, data);
        b.ms = (System.nanoTime() - t0) / 1_000_000;
        if ("application/pdf".equals(mime)) {
            try {
                b.pages = OfficiaPdf.pageCount(data);
            } catch (RuntimeException ignore) {
                // 加密后的 PDF 读页数会失败，属预期
            }
        }
        return b;
    }

    private static Json result(String name, String mime, byte[] data, long t0) {
        return store(name, mime, data, t0).toJson().put("evaluation", OfficiaLicense.isEvaluation())
            .put("enforced", OfficiaLicense.isEnforced());
    }

    private static byte[] fontBytes(String fontId) {
        if (fontId != null && !fontId.isBlank()) {
            return Store.bytes(fontId);
        }
        return Fonts.systemCjk();
    }

    private static int[] parseInts(String s) {
        if (s == null || s.isBlank()) {
            return new int[0];
        }
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? Arrays.asList() : l;
    }
}
