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
import plus.ruoyi.officia.ocr.OcrOptions;
import plus.ruoyi.officia.ocr.OfficiaOcr;
import plus.ruoyi.officia.ocr.recog.BuiltinModels;
import plus.ruoyi.officia.ocr.result.OcrLine;
import plus.ruoyi.officia.ocr.result.OcrResult;
import plus.ruoyi.officia.ocr.scan.ScannedPdfConverter;
import plus.ruoyi.officia.pdf.OfficiaPdf;
import plus.ruoyi.officia.pdf.sign.KeyMaterial;
import plus.ruoyi.officia.pdf.sign.SignOptions;
import plus.ruoyi.officia.pdf.sign.SignatureInfo;
import plus.ruoyi.officia.pdf.sign.SignatureVerification;
import plus.ruoyi.officia.pdf.word.WordConvertOptions;
import plus.ruoyi.officia.render.image.ImageRenderOptions;
import plus.ruoyi.officia.render.image.WatermarkOptions;
import plus.ruoyi.officia.slides.OfficiaSlides;
import plus.ruoyi.officia.words.OfficiaWords;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试台 API：把 HTTP 请求转成对 officia 各门面（OfficiaXxx）的真实调用。
 *
 * <p>约定：源文件先 POST /api/upload 拿 id，后续操作只传 id（多输入如 PDF 合并天然支持）；
 * 产出统一进 {@link Store} 并返回元信息（id/大小/页数/耗时），前端据此预览或下载。</p>
 *
 * <p>端点用 {@link Router} 注册成路由表并<b>按模块分组</b>，新增端点只加一行，
 * 不必再维护一个几百行的 switch。</p>
 *
 * @author officia-demo
 */
final class ApiRoutes {

    /** 全部端点；类加载时一次性注册。 */
    private static final Router ROUTER = buildRouter();

    private ApiRoutes() {
    }

    /** 分发一条 /api/* 请求；返回 true 表示已处理。 */
    static boolean dispatch(HttpExchange ex, String path, Map<String, String> q) throws Exception {
        return ROUTER.dispatch(ex, path, q);
    }

    /** 已注册端点数（自检用）。 */
    static int endpointCount() {
        return ROUTER.size();
    }

    private static Router buildRouter() {
        Router r = new Router();
        registerCore(r);
        registerLicense(r);
        registerWords(r);
        registerCells(r);
        registerSlides(r);
        registerPdf(r);
        registerImaging(r);
        registerBarCode(r);
        registerEmail(r);
        registerOcr(r);
        return r;
    }

    // ==================== 通用 ====================

    private static void registerCore(Router r) {
        // 注意：lambda 在请求时才执行，此时 ROUTER 已完成初始化，可安全调用 endpointCount()
        // maxUpload 供前端在发请求前预检文件体积：超限的请求一旦发出去，服务端中止读取会导致
        // 连接被 RST，浏览器只报 "Failed to fetch"，看不到真正的原因（见 Http.drain 的说明）。
        r.add("/api/health", (ex, q) -> Http.json(ex, Json.obj()
            .put("ok", true).put("blobs", Store.size()).put("bytes", Store.bytesUsed())
            .put("endpoints", endpointCount())
            .put("maxUpload", Http.MAX_BODY_BYTES)
            .put("maxHeap", Runtime.getRuntime().maxMemory()).end()));

        r.add("/api/upload", (ex, q) -> {
            byte[] data = Http.body(ex);
            String name = q.getOrDefault("name", "upload.bin");
            Store.Blob b = Store.put(name, Http.mimeOf(name), data);
            if (name.toLowerCase().endsWith(".pdf")) {
                b.withPages(pagesOrUnknown(data));
            }
            Http.json(ex, b.toJson().end());
        });

        r.add("/api/samples", (ex, q) -> samples(ex));
        r.add("/api/batch/run", (ex, q) -> Http.json(ex, Batch.run().end()));
    }

    /** 生成内置样本（officia 自产自销：CSV/PDF/PNG/EML 都能现造，docx/xlsx/pptx 需用户上传）。 */
    private static void samples(HttpExchange ex) throws Exception {
        List<Object> arr = new ArrayList<>();
        StringBuilder csv = new StringBuilder("编号,姓名,部门,城市,金额\n");
        for (int i = 1; i <= 120; i++) {
            csv.append(i).append(",员工").append(i).append(",研发部,深圳,").append(1000 + i * 7).append('\n');
        }
        arr.add(Store.put("样本-120行.csv", "text/csv",
            csv.toString().getBytes(StandardCharsets.UTF_8)).toJson());

        byte[] pdf = OfficiaCells.csvToPdf(csv.toString());
        arr.add(Store.put("样本-多页.pdf", "application/pdf", pdf).withPages(pagesOrUnknown(pdf)).toJson());

        arr.add(Store.put("样本-二维码.png", "image/png",
            OfficiaBarCode.qrPng("https://ruoyi.plus")).toJson());

        EmailMessage m = new EmailMessage().setSubject("Officia 授权确认")
            .setFrom("抓蛙师 <770492966@qq.com>").addTo("zhangsan@example.com")
            .setTextBody("您好，贵司购买的 Officia 全能版授权已签发，附件为 License 文件。");
        arr.add(Store.put("样本-邮件.eml", "message/rfc822", OfficiaEmail.writeEml(m)).toJson());

        Http.json(ex, Json.obj().put("samples", arr).end());
    }

    // ==================== 授权 ====================

    private static void registerLicense(Router r) {
        r.add("/api/license/status", (ex, q) -> Http.json(ex, licenseStatus().end()));

        r.add("/api/license/set", (ex, q) -> {
            OfficiaLicense.setLicense(new String(Http.body(ex), StandardCharsets.UTF_8).trim());
            Http.json(ex, licenseStatus().end());
        });

        r.add("/api/license/reset", (ex, q) -> {
            OfficiaLicense.reset();
            Http.json(ex, licenseStatus().end());
        });

        r.add("/api/license/enforce", (ex, q) -> {
            OfficiaLicense.enableEnforcement(!"false".equals(q.get("on")));
            Http.json(ex, licenseStatus().end());
        });
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

    // ==================== Words ====================

    private static void registerWords(Router r) {
        r.add("/api/words/topdf", (ex, q) -> {
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
            Http.json(ex, result(outName(q.get("id"), "pdf", "words.pdf"),
                "application/pdf", pdf, t0).end());
        });

        // docx/doc → 一页一张图片（文档在线预览）。与 topdf 共用前段，只换输出后端
        r.add("/api/words/toimages", (ex, q) -> {
            byte[] src = Store.bytes(q.get("id"));
            ImageRenderOptions opts = ImageRenderOptions.defaults()
                .dpi(Integer.parseInt(q.getOrDefault("dpi", "96")))
                .format(q.getOrDefault("format", "png"));

            String wmText = q.get("watermark");
            if (wmText != null && !wmText.isBlank()) {
                WatermarkOptions wm = WatermarkOptions.text(wmText);
                if ("1".equals(q.get("tile"))) {
                    wm.tile(true).fontSizePt(13).opacity(0.13f);
                }
                opts.watermark(wm);
            }

            long t0 = System.nanoTime();
            List<byte[]> images = OfficiaWords.toImages(src, null, opts);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            List<Object> arr = new ArrayList<>();
            long total = 0;
            String ext = opts.getFormat().equals("png") ? "png" : "jpg";
            String mime = opts.getFormat().equals("png") ? "image/png" : "image/jpeg";
            for (int i = 0; i < images.size(); i++) {
                arr.add(store(outName(q.get("id"), "第" + (i + 1) + "页", ext, "页面." + ext),
                    mime, images.get(i), t0).toJson());
                total += images.get(i).length;
            }
            Http.json(ex, Json.obj().put("multi", true).put("files", arr)
                .put("pages", images.size())
                .put("ms", ms)
                .put("dpi", opts.getDpi())
                .put("avgKb", images.isEmpty() ? 0 : total / 1024 / images.size())
                .put("hint", "图片可直接在浏览器/移动端 webview 显示，不依赖 PDF 阅读器；"
                    + "体积按页计费远大于 PDF，线上预览建议懒加载")
                .end());
        });

        r.add("/api/words/template", (ex, q) -> {
            byte[] tpl = Store.bytes(q.get("tplId"));
            String json = new String(Http.body(ex), StandardCharsets.UTF_8).trim();
            String mode = q.getOrDefault("mode", "single");
            long t0 = System.nanoTime();
            switch (mode) {
                case "each": {
                    List<byte[]> list = OfficiaWords.fillTemplateEachToPdf(tpl, parseJsonArray(json));
                    List<Object> arr = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        arr.add(store(outName(q.get("tplId"), "填充" + (i + 1), "pdf", "填充结果.pdf"),
                            "application/pdf", list.get(i), t0).toJson());
                    }
                    Http.json(ex, Json.obj().put("multi", true).put("files", arr).end());
                    break;
                }
                case "merged":
                    Http.json(ex, result(outName(q.get("tplId"), "邮件合并", "pdf", "邮件合并.pdf"),
                        "application/pdf",
                        OfficiaWords.fillTemplateMergedToPdf(tpl, json), t0).end());
                    break;
                case "docx":
                    Http.json(ex, result(outName(q.get("tplId"), "填充", "docx", "填充结果.docx"),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        OfficiaWords.fillTemplate(tpl, json), t0).end());
                    break;
                default:
                    Http.json(ex, result(outName(q.get("tplId"), "填充", "pdf", "填充结果.pdf"),
                        "application/pdf",
                        OfficiaWords.fillTemplateToPdf(tpl, json), t0).end());
            }
        });
    }

    /**
     * JSON 数组 → {@code List<Map>}（fillTemplateEachToPdf 只有 List 重载）。
     * 用 officia 自带的 MiniJson 解析，元素非对象即报可读错误（见 json-serialization 技能）。
     */
    private static List<Map<String, Object>> parseJsonArray(String json) {
        List<Map<String, Object>> dataList = new ArrayList<>();
        for (Object o : MiniJson.parseArray(json)) {
            if (!(o instanceof Map)) {
                throw new IllegalArgumentException("JSON 数组元素须为对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) o;
            dataList.add(m);
        }
        return dataList;
    }

    // ==================== Cells ====================

    private static void registerCells(Router r) {
        r.add("/api/cells/topdf", (ex, q) -> {
            long t0 = System.nanoTime();
            Http.json(ex, result(outName(q.get("id"), "pdf", "cells.pdf"), "application/pdf",
                OfficiaCells.toPdf(Store.bytes(q.get("id"))), t0).end());
        });

        r.add("/api/cells/tocsv", (ex, q) -> {
            long t0 = System.nanoTime();
            String csv = OfficiaCells.toCsv(Store.bytes(q.get("id")));
            Http.json(ex, result(outName(q.get("id"), "csv", "cells.csv"),
                    "text/csv", csv.getBytes(StandardCharsets.UTF_8), t0)
                .put("text", csv.length() > 20000 ? csv.substring(0, 20000) + "\n…" : csv).end());
        });

        r.add("/api/cells/csvtopdf", (ex, q) -> {
            String csv = new String(Http.body(ex), StandardCharsets.UTF_8);
            long t0 = System.nanoTime();
            Http.json(ex, result("csv.pdf", "application/pdf", OfficiaCells.csvToPdf(csv), t0).end());
        });

        r.add("/api/cells/parsecsv", (ex, q) -> {
            List<List<String>> rows = OfficiaCells.parseCsv(new String(Http.body(ex), StandardCharsets.UTF_8));
            List<Object> out = new ArrayList<>();
            for (List<String> row : rows) {
                out.add(new ArrayList<Object>(row));
            }
            Http.json(ex, Json.obj().put("rows", out).end());
        });

        r.add("/api/cells/formula", (ex, q) -> {
            String cell = q.get("cell");
            Object v = OfficiaCells.evaluateXlsxCell(Store.bytes(q.get("id")), cell);
            Http.json(ex, Json.obj().put("cell", cell).put("value", String.valueOf(v)).end());
        });

        r.add("/api/cells/recalc", (ex, q) -> {
            Map<String, Object> all = OfficiaCells.recalculateXlsx(Store.bytes(q.get("id")));
            Map<String, Object> shown = new LinkedHashMap<>();
            int n = 0;
            for (Map.Entry<String, Object> e : all.entrySet()) {
                if (n++ >= 200) {   // 只回传前 200 个，避免超大表把响应撑爆
                    break;
                }
                shown.put(e.getKey(), String.valueOf(e.getValue()));
            }
            Http.json(ex, Json.obj().put("count", all.size()).put("cells", shown).end());
        });
    }

    // ==================== Slides ====================

    private static void registerSlides(Router r) {
        r.add("/api/slides/topdf", (ex, q) -> {
            byte[] src = Store.bytes(q.get("id"));
            long t0 = System.nanoTime();
            // toPdf 即版式保真（形状绝对定位、每张幻灯片一页），不再有模式分支
            byte[] pdf = OfficiaSlides.toPdf(src);
            Http.json(ex, result(outName(q.get("id"), "pdf", "slides.pdf"),
                "application/pdf", pdf, t0).end());
        });
    }

    // ==================== PDF ====================

    private static void registerPdf(Router r) {
        r.add("/api/pdf/info", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            List<float[]> sizes = OfficiaPdf.pageSizes(pdf);
            String size = sizes.isEmpty() ? "-"
                : Math.round(sizes.get(0)[0]) + "×" + Math.round(sizes.get(0)[1]);
            Http.json(ex, Json.obj().put("pages", OfficiaPdf.pageCount(pdf))
                .put("encrypted", OfficiaPdf.isEncrypted(pdf)).put("firstPageSize", size).end());
        });

        r.add("/api/pdf/merge", (ex, q) -> {
            long t0 = System.nanoTime();
            Http.json(ex, result(mergedName(q.get("ids"), "合并", "pdf", "合并.pdf"), "application/pdf",
                OfficiaPdf.merge(bytesOfIds(q.get("ids"))), t0).end());
        });

        r.add("/api/pdf/split", (ex, q) -> {
            long t0 = System.nanoTime();
            List<byte[]> parts = OfficiaPdf.split(Store.bytes(q.get("id")));
            List<Object> arr = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                arr.add(store(outName(q.get("id"), "第" + (i + 1) + "页", "pdf", "拆分.pdf"),
                    "application/pdf", parts.get(i), t0).toJson());
            }
            Http.json(ex, Json.obj().put("multi", true).put("files", arr).end());
        });

        r.add("/api/pdf/pages", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            int[] idx = parseInts(q.get("pages"));
            long t0 = System.nanoTime();
            boolean remove = "remove".equals(q.get("op"));
            byte[] out = remove ? OfficiaPdf.removePages(pdf, idx) : OfficiaPdf.extractPages(pdf, idx);
            Http.json(ex, result(outName(q.get("id"), remove ? "删页" : "抽页", "pdf", "页面处理.pdf"),
                "application/pdf", out, t0).end());
        });

        r.add("/api/pdf/rotate", (ex, q) -> {
            long t0 = System.nanoTime();
            byte[] out = OfficiaPdf.rotate(Store.bytes(q.get("id")),
                Integer.parseInt(q.getOrDefault("deg", "90")));
            Http.json(ex, result(outName(q.get("id"), "旋转", "pdf", "旋转.pdf"),
                "application/pdf", out, t0).end());
        });

        // 水印：不传样式参数时保持老行为（页心 45° 灰字），传了任一样式参数就走参数化 API。
        // 这样老的调用方（含批量回归里的断言）不受影响，新面板能把全套参数拖出来看效果。
        r.add("/api/pdf/watermark", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            String text = q.getOrDefault("text", "CONFIDENTIAL");
            byte[] font = fontBytes(q.get("fontId"));
            // imgId 指向 Blob 仓里的一张图（印章/LOGO）：给了就走图片水印，text 被忽略
            byte[] wmImage = q.get("imgId") == null ? null : Store.bytes(q.get("imgId"));
            boolean styled = wmImage != null || q.containsKey("tile") || q.containsKey("size")
                || q.containsKey("opacity") || q.containsKey("rotate")
                || q.containsKey("color") || q.containsKey("gap") || q.containsKey("behind")
                || q.containsKey("annot") || q.containsKey("imgw");
            long t0 = System.nanoTime();
            byte[] out;
            if (styled) {
                WatermarkOptions wm = (wmImage != null
                    ? WatermarkOptions.image(wmImage) : WatermarkOptions.text(text))
                    .tile("1".equals(q.get("tile")))
                    .behindText("1".equals(q.get("behind")))
                    .annotation("1".equals(q.get("annot")));
                if (q.containsKey("imgw")) {
                    wm.imageWidthPt(Float.parseFloat(q.get("imgw")));
                }
                if (q.containsKey("size")) {
                    wm.fontSizePt(Float.parseFloat(q.get("size")));
                }
                if (q.containsKey("opacity")) {
                    wm.opacity(Float.parseFloat(q.get("opacity")));
                }
                if (q.containsKey("rotate")) {
                    wm.rotationDegrees(Float.parseFloat(q.get("rotate")));
                }
                if (q.containsKey("color")) {
                    wm.colorRgb(Integer.parseInt(q.get("color").replace("#", ""), 16));
                }
                if (q.containsKey("gap")) {
                    wm.tileGapRatio(Float.parseFloat(q.get("gap")));
                }
                out = OfficiaPdf.watermark(pdf, wm, font);
            } else {
                out = font != null ? OfficiaPdf.watermark(pdf, text, font)
                    : OfficiaPdf.watermark(pdf, text);
            }
            Http.json(ex, result(outName(q.get("id"), "水印", "pdf", "水印.pdf"),
                "application/pdf", out, t0).end());
        });

        r.add("/api/pdf/pagenumbers", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            String fmt = q.getOrDefault("format", "{page} / {total}");
            byte[] font = fontBytes(q.get("fontId"));
            long t0 = System.nanoTime();
            byte[] out = font != null ? OfficiaPdf.addPageNumbers(pdf, fmt, font)
                : OfficiaPdf.addPageNumbers(pdf, fmt);
            Http.json(ex, result(outName(q.get("id"), "页码", "pdf", "页码.pdf"),
                "application/pdf", out, t0).end());
        });

        r.add("/api/pdf/text", (ex, q) -> {
            List<String> byPage = OfficiaPdf.extractTextByPage(Store.bytes(q.get("id")));
            Http.json(ex, Json.obj().put("pages", byPage.size())
                .put("text", new ArrayList<Object>(byPage)).end());
        });

        r.add("/api/pdf/images", (ex, q) -> {
            long t0 = System.nanoTime();
            List<byte[]> imgs = OfficiaPdf.extractImages(Store.bytes(q.get("id")));
            List<Object> arr = new ArrayList<>();
            for (int i = 0; i < imgs.size(); i++) {
                arr.add(store(outName(q.get("id"), "图片" + (i + 1), "png", "图片.png"),
                    "image/png", imgs.get(i), t0).toJson());
            }
            Http.json(ex, Json.obj().put("multi", true).put("files", arr).end());
        });

        // PDF → 一页一张图片（在线预览）。自动选路：扫描件走抽图快路，其余走通用渲染。
        // 不传 dpi/format 就不传给 options —— 扫描件快路据此决定"原样输出"还是重采样重编码，
        // 传了默认值 96/png 反而会把 200-300DPI 的扫描件毁掉、体积暴涨（实测 38MB → 226MB）
        r.add("/api/pdf/toimages", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            ImageRenderOptions opts = ImageRenderOptions.defaults();
            if (q.get("dpi") != null && !q.get("dpi").isBlank()) {
                opts.dpi(Integer.parseInt(q.get("dpi")));
            }
            if (q.get("format") != null && !q.get("format").isBlank()) {
                opts.format(q.get("format"));
            }

            String wmText = q.get("watermark");
            if (wmText != null && !wmText.isBlank()) {
                WatermarkOptions wm = WatermarkOptions.text(wmText);
                if ("1".equals(q.get("tile"))) {
                    wm.tile(true).fontSizePt(13).opacity(0.13f);
                }
                opts.watermark(wm);
            }

            long t0 = System.nanoTime();
            List<byte[]> images = OfficiaPdf.toImages(pdf, opts);
            long ms = (System.nanoTime() - t0) / 1_000_000;

            List<Object> arr = new ArrayList<>();
            long total = 0;
            for (int i = 0; i < images.size(); i++) {
                // 扫描件快路可能原样吐出 JPEG，与 opts.getFormat() 不一定一致 —— 按字节嗅探
                boolean jpg = isJpeg(images.get(i));
                String ext = jpg ? "jpg" : "png";
                arr.add(store(outName(q.get("id"), "第" + (i + 1) + "页", ext, "页面." + ext),
                    jpg ? "image/jpeg" : "image/png", images.get(i), t0).toJson());
                total += images.get(i).length;
            }
            Http.json(ex, Json.obj().put("multi", true).put("files", arr)
                .put("pages", images.size())
                .put("ms", ms)
                .put("avgKb", images.isEmpty() ? 0 : total / 1024 / images.size())
                .put("hint", "扫描件走抽图快路（又快又无损），电子版走通用渲染——"
                    + "后者字形是近似的（不光栅化嵌入字体），版面位置精确")
                .end());
        });

        r.add("/api/pdf/toword", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            WordConvertOptions opts = WordConvertOptions.defaults();
            // 三个开关默认都开；关掉可看"只要文字"的产物有多小
            if ("false".equals(q.get("images"))) {
                opts.setExtractImages(false);
            }
            if ("false".equals(q.get("vectors"))) {
                opts.setExtractVectors(false);
            }
            if ("false".equals(q.get("patterns"))) {
                opts.setRasterizeVectorPatterns(false);
            }
            if (q.get("password") != null && !q.get("password").isEmpty()) {
                opts.setPassword(q.get("password"));
            }
            long t0 = System.nanoTime();
            byte[] out = OfficiaPdf.toWord(pdf, opts);
            Http.json(ex, result(outName(q.get("id"), "转Word", "docx", "转出.docx"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                out, t0).put("srcPages", OfficiaPdf.pageCount(pdf)).end());
        });

        r.add("/api/pdf/encrypt", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            String user = q.getOrDefault("user", "open");
            String owner = q.getOrDefault("owner", "owner");
            int bits = Integer.parseInt(q.getOrDefault("bits", "256"));
            long t0 = System.nanoTime();
            byte[] out = bits == 256 ? OfficiaPdf.encryptAes256(pdf, user, owner)
                : OfficiaPdf.encrypt(pdf, user, owner, bits);
            Http.json(ex, result(outName(q.get("id"), "加密", "pdf", "加密.pdf"),
                "application/pdf", out, t0)
                .put("algo", bits == 256 ? "AES-256 (AESV3)" : "RC4-" + bits).end());
        });

        // 数字签名（PDF32000 §12.8）。产物要用 Adobe Acrobat 打开才看得到防篡改状态——
        // 浏览器内置的 PDF 查看器多数不做签名验证，看不出区别，这一点在页面上也标注了
        r.add("/api/pdf/sign", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            String signer = q.getOrDefault("signer", "张三");
            String reason = q.getOrDefault("reason", "审批通过");
            String location = q.getOrDefault("location", "北京");
            String field = q.getOrDefault("field", "Signature1");

            long t0 = System.nanoTime();
            // 现场生成自签名身份：阅读器会提示"签署人身份未知"，但"文档未被修改"照样是绿的。
            // 要消除该提示需私有 CA 或公共 CA 证书，那属部署环节、不在测试台演示范围
            KeyMaterial id = KeyMaterial.selfSigned(signer, "Officia 测试台", 3650);
            byte[] out = OfficiaPdf.sign(pdf, id, SignOptions.defaults()
                .name(signer).reason(reason).location(location).fieldName(field));

            Http.json(ex, result(outName(q.get("id"), "签名", "pdf", "已签名.pdf"),
                "application/pdf", out, t0)
                .put("signer", signer)
                .put("signatures", countSignatures(out))
                .put("certSubject", id.getSubject())
                .put("hint", "用 Adobe Acrobat 打开看签名面板；浏览器内置阅读器多数不验签名")
                .end());
        });

        // 多人依次签字：一次演示三个人，验证增量更新下前序签名不失效
        r.add("/api/pdf/multisign", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            String[] names = q.getOrDefault("signers", "张三,李四,王五").split(",");
            String[] reasons = {"部门经理审批", "财务复核", "总经理批准"};

            long t0 = System.nanoTime();
            byte[] out = pdf;
            int before = out.length;
            for (int i = 0; i < names.length; i++) {
                String who = names[i].trim();
                if (who.isEmpty()) {
                    continue;
                }
                KeyMaterial id = KeyMaterial.selfSigned(who, "Officia 测试台", 3650);
                out = OfficiaPdf.sign(out, id, SignOptions.defaults()
                    .name(who)
                    .reason(i < reasons.length ? reasons[i] : "审批通过")
                    // 🔴 各人域名必须不同，重名会导致阅读器只认出一个签名
                    .fieldName("Signature" + (i + 1)));
            }
            Http.json(ex, result(outName(q.get("id"), "多人签名", "pdf", "三人签名.pdf"),
                "application/pdf", out, t0)
                .put("signers", String.join(" → ", names))
                .put("signatures", countSignatures(out))
                // 首签走全量重写（顺带压缩内容流，体积可能反而变小），之后每一签才是增量追加；
                // 笼统说成"每次都追加"会在这里显示成负数，自相矛盾
                .put("grow", before / 1024 + " KB → " + out.length / 1024
                    + " KB（首签全量重写，其后每签追加一个增量段、前段字节不变）")
                .put("hint", "Adobe 签名面板应列出多条「修订版」，且每条都显示文档未被修改")
                .end());
        });

        // 验签（PDF32000 §12.8 + RFC 5652）。不产出文件，只回结构化结论——
        // 业务系统据此程序化判定，不必让人开 Acrobat 肉眼看
        r.add("/api/pdf/verify", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            long t0 = System.nanoTime();
            SignatureVerification v = OfficiaPdf.verify(pdf);
            Http.json(ex, verifyJson(v, (System.nanoTime() - t0) / 1_000_000).end());
        });

        // 防篡改演示：拿一份已签名的 PDF，改掉正文里的一个字节，再验一次。
        // 这是"签名能防篡改"最直观的证明——改动前后各验一次，结论一正一反
        r.add("/api/pdf/tamper", (ex, q) -> {
            byte[] pdf = Store.bytes(q.get("id"));
            SignatureVerification before = OfficiaPdf.verify(pdf);
            if (!before.isSigned()) {
                Http.json(ex, Json.obj().put("error", "这份 PDF 没有签名，先用「数字签名」按钮签一份")
                    .end());
                return;
            }
            long t0 = System.nanoTime();
            byte[] tampered = pdf.clone();
            int at = tamperPosition(tampered, before);
            if (at < 0) {
                Http.json(ex, Json.obj().put("error", "未能在签名覆盖区内找到可改动的正文字符")
                    .end());
                return;
            }
            char old = (char) tampered[at];
            tampered[at] = (byte) (old == 'X' ? 'Y' : 'X');

            SignatureVerification after = OfficiaPdf.verify(tampered);
            Http.json(ex, result(outName(q.get("id"), "篡改", "pdf", "被篡改.pdf"),
                "application/pdf", tampered, t0)
                .put("changedAt", "第 " + at + " 字节：'" + old + "' → '"
                    + (char) tampered[at] + "'（只改了 1 个字节）")
                .put("beforeValid", before.isValid())
                .put("afterValid", after.isValid())
                .put("beforeSummary", before.getSummary())
                .put("afterSummary", after.getSummary())
                .put("problem", after.getSignatures().isEmpty() ? null
                    : after.getSignatures().get(0).getProblem())
                .put("hint", "改动前验签通过、改动后不通过；用 Acrobat 打开产物会显示「文档已被更改或损坏」")
                .end());
        });
    }

    /** 把验签结果摊成 JSON。 */
    private static Json verifyJson(SignatureVerification v, long ms) {
        List<Object> sigs = new ArrayList<>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (SignatureInfo s : v.getSignatures()) {
            sigs.add(Json.obj()
                .put("field", s.getFieldName())
                .put("signer", s.getSignerName())
                .put("time", s.getSigningTime() == null ? null : fmt.format(s.getSigningTime()))
                .put("timestamp", s.getTimestampTime() == null ? null
                    : fmt.format(s.getTimestampTime()))
                .put("reason", s.getReason())
                .put("location", s.getLocation())
                .put("subject", s.getSubject())
                .put("issuer", s.getIssuer())
                .put("valid", s.isValid())
                .put("digestMatch", s.isDigestMatch())
                .put("signatureMatch", s.isSignatureMatch())
                .put("certificateIntact", s.isCertificateIntact())
                .put("coversWholeDocument", s.isCoveringWholeDocument())
                .put("problem", s.getProblem()));
        }
        return Json.obj()
            .put("signed", v.isSigned())
            // 🔴 整体结论看 isValid()：它额外查了"最末签名是否覆盖到文件尾"，
            // 逐个签名取与会漏掉"签完之后被追加内容"这种情形
            .put("valid", v.isValid())
            .put("count", v.getSignatureCount())
            .put("fullyCovered", v.isFullyCovered())
            .put("unsignedTailBytes", v.getUnsignedTailBytes())
            .put("summary", v.getSummary())
            .put("ms", ms)
            .put("signatures", sigs);
    }

    /**
     * 在第一个签名的覆盖区内找一个可改的正文字符。
     *
     * <p>挑内容流里 {@code (...)} 中的字母——改它页面上的文字会跟着变，演示效果最直观，
     * 也不会破坏 PDF 结构导致"打不开"而非"被改过"。</p>
     *
     * @return 可改位置；找不到时 -1
     */
    private static int tamperPosition(byte[] pdf, SignatureVerification v) {
        int[] br = v.getSignatures().get(0).getByteRange();
        if (br.length != 4) {
            return -1;
        }
        int end = Math.min(br[0] + br[1], pdf.length) - 1;
        // 优先挑 ASCII 字母：改中文文本会命中多字节字符的半个字节，回显成 '?' 之类的乱码，
        // 篡改效果虽同样成立，但演示时看不清"改了什么"
        for (int i = br[0]; i < end; i++) {
            byte b = pdf[i + 1];
            if (pdf[i] == '(' && ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z'))) {
                return i + 1;
            }
        }
        for (int i = br[0]; i < end; i++) {
            if (pdf[i] == '(' && pdf[i + 1] > 0x20) {
                return i + 1;                       // 正文无 ASCII 字母时的退路
            }
        }
        return -1;
    }

    /** 数文档里的签名数——/ByteRange 是签名字典的必需条目（PDF32000 Table 252）。 */
    private static int countSignatures(byte[] pdf) {
        byte[] mark = "/ByteRange".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int count = 0;
        outer:
        for (int i = 0; i <= pdf.length - mark.length; i++) {
            for (int j = 0; j < mark.length; j++) {
                if (pdf[i + j] != mark[j]) {
                    continue outer;
                }
            }
            count++;
            i += mark.length - 1;
        }
        return count;
    }

    // ==================== Imaging ====================

    private static void registerImaging(Router r) {
        r.add("/api/imaging/op", (ex, q) -> {
            byte[] src = Store.bytes(q.get("id"));
            long t0 = System.nanoTime();
            byte[] out = imaging(src, q);
            boolean convert = "convert".equals(q.get("op"));
            String format = convert ? q.getOrDefault("format", "png").toLowerCase() : "png";
            // 纯格式转换只换后缀（照片.jpg → 照片.png）；滤镜/变换的产出与源同为 png，
            // 用操作名区分，免得一串同名结果分不清是灰度还是模糊
            String op = q.getOrDefault("op", "grayscale");
            Http.json(ex, result(outName(q.get("id"), convert ? null : IMG_TAG.getOrDefault(op, op),
                format, "处理结果." + format), "image/" + format, out, t0).end());
        });

        r.add("/api/imaging/topdf", (ex, q) -> {
            long t0 = System.nanoTime();
            Http.json(ex, result(mergedName(q.get("ids"), "图片", "pdf", "图片.pdf"), "application/pdf",
                OfficiaImaging.toPdf(bytesOfIds(q.get("ids"))), t0).end());
        });
    }

    /**
     * 图像操作分派。参数名语义：{@code x/y} 位置、{@code width/height} 尺寸、
     * {@code amount} 强度（阈值/半径/增量/级数）、{@code factor} 系数、{@code opacity} 透明度。
     */
    private static byte[] imaging(byte[] src, Map<String, String> q) {
        String op = q.getOrDefault("op", "grayscale");
        int x = intOf(q, "x", 0);
        int y = intOf(q, "y", 0);
        int amount = intOf(q, "amount", 0);
        switch (op) {
            case "grayscale":  return OfficiaImaging.grayscale(src);
            case "sepia":      return OfficiaImaging.sepia(src);
            case "invert":     return OfficiaImaging.invert(src);
            case "sharpen":    return OfficiaImaging.sharpen(src);
            case "flipH":      return OfficiaImaging.flipHorizontal(src);
            case "flipV":      return OfficiaImaging.flipVertical(src);
            case "binarize":   return OfficiaImaging.binarize(src, amount == 0 ? 128 : amount);
            case "blur":       return OfficiaImaging.blur(src, amount == 0 ? 3 : amount);
            case "brightness": return OfficiaImaging.brightness(src, amount == 0 ? 30 : amount);
            case "posterize":  return OfficiaImaging.posterize(src, amount == 0 ? 4 : amount);
            case "contrast":   return OfficiaImaging.contrast(src, floatOf(q, "factor", 1.4f));
            case "resize":     return OfficiaImaging.resize(src, intOf(q, "width", 640), intOf(q, "height", 360));
            case "crop":       return OfficiaImaging.crop(src, x, y, intOf(q, "width", 200), intOf(q, "height", 200));
            case "rotate":     return OfficiaImaging.rotate(src, Double.parseDouble(q.getOrDefault("deg", "90")));
            case "convert":    return OfficiaImaging.convert(src, q.getOrDefault("format", "jpg"));
            case "textmark":   return OfficiaImaging.textWatermark(src, q.getOrDefault("text", "Officia Demo"),
                x, y == 0 ? 40 : y, floatOf(q, "size", 28f), 0x808080, floatOf(q, "opacity", 0.5f));
            case "imgmark":    return OfficiaImaging.imageWatermark(src, Store.bytes(q.get("markId")),
                x, y, floatOf(q, "opacity", 0.5f));
            default: throw new IllegalArgumentException("未知图像操作: " + op);
        }
    }

    // ==================== BarCode ====================

    private static void registerBarCode(Router r) {
        r.add("/api/barcode", (ex, q) -> {
            long t0 = System.nanoTime();
            byte[] png = barcode(q);
            Http.json(ex, result(q.getOrDefault("type", "qr") + ".png", "image/png", png, t0).end());
        });
    }

    private static byte[] barcode(Map<String, String> q) {
        String type = q.getOrDefault("type", "qr");
        String text = q.getOrDefault("text", "https://ruoyi.plus");
        int module = intOf(q, "module", 8);
        int quiet = intOf(q, "quiet", 4);
        int height = intOf(q, "height", 80);
        boolean withText = "true".equals(q.get("withText"));
        switch (type) {
            case "qr":      return OfficiaBarCode.qrPng(text, eccOf(q.getOrDefault("ecc", "M")), module, quiet);
            case "code128": return withText ? OfficiaBarCode.code128PngText(text) : OfficiaBarCode.code128Png(text, module, height, quiet);
            case "code39":  return withText ? OfficiaBarCode.code39PngText(text)  : OfficiaBarCode.code39Png(text, module, height, quiet);
            case "code93":  return withText ? OfficiaBarCode.code93PngText(text)  : OfficiaBarCode.code93Png(text, module, height, quiet);
            case "ean13":   return withText ? OfficiaBarCode.ean13PngText(text)   : OfficiaBarCode.ean13Png(text, module, height, quiet);
            case "ean8":    return withText ? OfficiaBarCode.ean8PngText(text)    : OfficiaBarCode.ean8Png(text, module, height, quiet);
            case "upca":    return withText ? OfficiaBarCode.upcaPngText(text)    : OfficiaBarCode.upcaPng(text, module, height, quiet);
            case "itf14":   return withText ? OfficiaBarCode.itf14PngText(text)   : OfficiaBarCode.itf14Png(text, module, height, quiet);
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

    // ==================== Email ====================

    private static void registerEmail(Router r) {
        r.add("/api/email/parse", (ex, q) -> {
            EmailMessage m = OfficiaEmail.parseEml(Store.bytes(q.get("id")));
            Json j = Json.obj().put("subject", m.getSubject()).put("from", m.getFrom())
                .put("to", String.join(", ", nz(m.getTo()))).put("date", String.valueOf(m.getDate()));
            List<Object> atts = new ArrayList<>();
            if (m.getAttachments() != null) {
                m.getAttachments().forEach(a -> atts.add(Json.obj()
                    .put("name", a.getFilename()).put("size", a.getSize())));
            }
            String body = m.getHtmlBody() != null && !m.getHtmlBody().isBlank()
                ? m.getHtmlBody() : m.getTextBody();
            Http.json(ex, j.put("attachments", atts).put("body", body == null ? "" : body).end());
        });

        r.add("/api/email/topdf", (ex, q) -> {
            long t0 = System.nanoTime();
            Http.json(ex, result(outName(q.get("id"), "pdf", "邮件归档.pdf"), "application/pdf",
                OfficiaEmail.toPdf(Store.bytes(q.get("id"))), t0).end());
        });
    }

    // ==================== 结果命名 ====================

    /** 图像操作 → 中文标记：产物名进的是中文界面与中文文件系统，不该混英文 op 名。 */
    private static final Map<String, String> IMG_TAG = Map.ofEntries(
        Map.entry("grayscale", "灰度"), Map.entry("sepia", "怀旧"), Map.entry("invert", "反色"),
        Map.entry("sharpen", "锐化"), Map.entry("blur", "模糊"), Map.entry("binarize", "二值化"),
        Map.entry("posterize", "色调分离"), Map.entry("brightness", "亮度"), Map.entry("contrast", "对比度"),
        Map.entry("flipH", "水平翻转"), Map.entry("flipV", "垂直翻转"), Map.entry("resize", "缩放"),
        Map.entry("crop", "裁剪"), Map.entry("rotate", "旋转"),
        Map.entry("textmark", "文字水印"), Map.entry("imgmark", "图片水印"));

    /**
     * 结果文件名：沿用源文件名，只换扩展名（{@code 年度报告.docx} → {@code 年度报告.pdf}）。
     *
     * <p>此前所有产物都叫 words.pdf / cells.pdf 这类固定名，连转几份后浏览器只能靠
     * "words (1).pdf""words (4).pdf" 区分，下载目录里根本对不上是哪个源文件转的。</p>
     *
     * @param id       源文件的 Blob id；取不到（已被 LRU 逐出 / 无源文件）时退回 fallback
     * @param ext      新扩展名（不带点）
     * @param fallback 无源文件时的兜底名（须自带扩展名）
     */
    private static String outName(String id, String ext, String fallback) {
        return outName(id, null, ext, fallback);
    }

    /**
     * 同上，额外加操作标记（{@code 合同.pdf} + {@code 水印} → {@code 合同-水印.pdf}）。
     *
     * <p>PDF→PDF、DOCX→DOCX 这类<b>不改格式</b>的操作必须用它：只换后缀的话结果与源文件同名，
     * 下载时照样被浏览器加 "(1)"，还容易把原件覆盖混淆。</p>
     */
    private static String outName(String id, String tag, String ext, String fallback) {
        Store.Blob b = id == null || id.isBlank() ? null : Store.get(id);
        String base = baseName(b == null ? fallback : b.name());
        if (base.isBlank()) {
            base = baseName(fallback);
        }
        return base + (tag == null || tag.isBlank() ? "" : "-" + tag) + "." + ext;
    }

    /**
     * 多输入产物的名字：取首个源文件名并标注份数（{@code 合同等3份-合并.pdf}）。
     *
     * @param ids 逗号分隔的 Blob id 列表
     */
    private static String mergedName(String ids, String tag, String ext, String fallback) {
        String[] arr = ids == null || ids.isBlank() ? new String[0] : ids.split(",");
        if (arr.length == 0) {
            return baseName(fallback) + "." + ext;
        }
        String first = outName(arr[0].trim(), null, ext, fallback);
        String base = baseName(first);
        return arr.length > 1 ? base + "等" + arr.length + "份-" + tag + "." + ext
            : base + "-" + tag + "." + ext;
    }

    /**
     * 去扩展名，并剥掉路径部分。
     *
     * <p>剥路径是必要的：个别浏览器/客户端会把 {@code D:\download\合同.docx} 整段当文件名传上来，
     * 直接拼进结果名会得到一个带盘符的怪名字。</p>
     */
    private static String baseName(String name) {
        String s = name == null ? "" : name.trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (slash >= 0) {
            s = s.substring(slash + 1);
        }
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    // ==================== OCR ====================

    private static void registerOcr(Router r) {
        // 图片 → 文字。除整段文本外一并返回逐行的框与置信度：
        // OCR 与其它能力不同，"对不对"没法只看一个总分——要能定位到是哪一行崩了
        r.add("/api/ocr/recognize", (ex, q) -> {
            long t0 = System.nanoTime();
            OcrResult res = OfficiaOcr.analyze(Store.bytes(q.get("id")), ocrOptions(q));
            double min = doubleOf(q, "minConfidence", 0);
            if (min > 0) {
                res = res.filterByConfidence(min);
            }
            List<Object> lines = new ArrayList<>();
            for (OcrLine l : res.lines()) {
                lines.add(Json.obj().put("text", l.text())
                    .put("confidence", round(l.confidence(), 1000))
                    .put("x", l.box().x()).put("y", l.box().y())
                    .put("w", l.box().width()).put("h", l.box().height()));
            }
            Http.json(ex, Json.obj()
                .put("text", res.text())
                .put("lineCount", res.lineCount())
                .put("confidence", round(res.confidence(), 1000))
                .put("width", res.width()).put("height", res.height())
                .put("skewAngleDeg", round(res.skewAngleDeg(), 100))
                .put("lines", lines)
                .put("ms", (System.nanoTime() - t0) / 1_000_000)
                .end());
        });

        // 扫描件 PDF → DOCX
        r.add("/api/ocr/scan2word", (ex, q) -> {
            long t0 = System.nanoTime();
            // 🔴 只转前 maxPages 页。神经网络推理是纯 Java 跑的，实测一张 747×600 的
            // 书页 28 行要 ~40 s（权重加载只占 0.19 s，其余全是推理），一本 183 页的书
            // 按对开切分后是 366 个半页 → 4 小时上不封顶。测试台是<b>共享</b>的，
            // 不设上限等于让一个人把它占死，而调用方只会看到浏览器一直转圈。
            byte[] src = Store.bytes(q.get("id"));
            int total = pagesOrUnknown(src);
            int max = Math.max(1, Math.min(intOf(q, "maxPages", 3), 20));
            if (total > max) {
                int[] head = new int[max];
                for (int i = 0; i < max; i++) {
                    head[i] = i;
                }
                src = OfficiaPdf.extractPages(src, head);
            }
            // 🔴 版面参数必须暴露出来。实测一本页面横放 + 两页并排扫描的书，
            // 不传 rotate/split 转出来 5.7 KB 全是乱码（文字是竖着的，切分层按横排走投影）。
            // 这类输入没有任何报错——CTC 在固定类别集上永远给结果，用户只会看到满屏怪字，
            // 进而误判成"OCR 不可用"。参数摆在端点上，至少让人能试出来。
            byte[] docx = new ScannedPdfConverter()
                .options(ocrOptions(q))
                .rotate(rotation(q.get("rotate")))
                .splitFacingPages(boolOf(q, "split"))
                .minConfidence(doubleOf(q, "minConfidence", 0.01))
                .toWord(src);
            Http.json(ex, result(outName(q.get("id"), "识别", "docx", "扫描件.docx"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx, t0)
                // 截断了必须说——静默只转前几页，用户会以为"后面的内容识别丢了"
                .put("pagesTotal", total)
                .put("pagesConverted", total < 0 ? -1 : Math.min(total, max))
                .end());
        });
    }

    /**
     * 组装 OCR 选项。
     *
     * <p>🔴 <b>语种默认中文，与 {@link OcrOptions#defaults()} 的英文不同</b>——
     * 这是测试台的取舍：本测试台的样本以中文文档为主，而<b>选错语种不会报错</b>，
     * 只会产出满篇拉丁噪声，且平均置信度反而更高（骗过按置信度过滤）。
     * 让默认贴近实际用途，比让用户从一堆乱码里反推原因好。传 {@code lang=en} 切英文。</p>
     *
     * <p>{@code charWhitelist} 非空会让门面<b>改走模板匹配</b>（模型的类别集训练时已固定，
     * 给不了白名单能力）——这是票据/编号场景提准最划算的一招，但对通用中文页面反而更差。</p>
     */
    private static OcrOptions ocrOptions(Map<String, String> q) {
        OcrOptions opts = OcrOptions.defaults()
            .setLanguage("en".equalsIgnoreCase(q.getOrDefault("lang", "zh"))
                ? BuiltinModels.Language.ENGLISH : BuiltinModels.Language.CHINESE);
        String whitelist = q.get("charWhitelist");
        if (whitelist != null && !whitelist.isBlank()) {
            opts.setCharWhitelist(whitelist);
        }
        return opts;
    }

    /** {@code rotate} 参数 → 旋转档位；无法识别的写法按"不旋转"处理。 */
    private static ScannedPdfConverter.Rotation rotation(String v) {
        if (v == null) {
            return ScannedPdfConverter.Rotation.NONE;
        }
        return switch (v.trim()) {
            case "90", "cw" -> ScannedPdfConverter.Rotation.CLOCKWISE_90;
            case "-90", "270", "ccw" -> ScannedPdfConverter.Rotation.COUNTER_CLOCKWISE_90;
            case "180" -> ScannedPdfConverter.Rotation.HALF_TURN;
            default -> ScannedPdfConverter.Rotation.NONE;
        };
    }

    // ==================== 小工具 ====================

    /** 存入产物并补齐耗时/页数，返回 Blob。 */
    private static Store.Blob store(String name, String mime, byte[] data, long t0) {
        Store.Blob b = Store.put(name, mime, data).withMs((System.nanoTime() - t0) / 1_000_000);
        return "application/pdf".equals(mime) ? b.withPages(pagesOrUnknown(data)) : b;
    }

    /** 产物元信息 JSON（附当前授权态，前端据此提示"评估降级"）。 */
    private static Json result(String name, String mime, byte[] data, long t0) {
        return store(name, mime, data, t0).toJson()
            .put("evaluation", OfficiaLicense.isEvaluation())
            .put("enforced", OfficiaLicense.isEnforced());
    }

    /** 读 PDF 页数；加密或异常 PDF 返回 -1（不视为失败）。 */
    private static int pagesOrUnknown(byte[] pdf) {
        try {
            return OfficiaPdf.pageCount(pdf);
        } catch (RuntimeException ignore) {
            return -1;
        }
    }

    /**
     * 按 SOI 标记（FF D8 FF）判定是否 JPEG。
     * PDF 转图片时扫描件快路会原样吐出源图，格式不一定等于 ImageRenderOptions 里的设置，
     * 只能按字节嗅探才能给对扩展名与 MIME。
     */
    private static boolean isJpeg(byte[] img) {
        return img.length >= 3 && (img[0] & 0xFF) == 0xFF
                && (img[1] & 0xFF) == 0xD8 && (img[2] & 0xFF) == 0xFF;
    }

    /** 逗号分隔的 id 列表 → 字节列表（多输入操作用）。 */
    private static List<byte[]> bytesOfIds(String ids) {
        List<byte[]> list = new ArrayList<>();
        for (String id : (ids == null ? "" : ids).split(",")) {
            if (!id.isBlank()) {
                list.add(Store.bytes(id.trim()));
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("未指定输入文件（ids 为空）");
        }
        return list;
    }

    /**
     * 水印/页码用的中文字体：优先用户上传，其次系统探测，都没有则 null（退回 ASCII 重载）。
     *
     * <p>上传的字体<b>先校验再用</b>：officia 的子集器只吃 TrueType glyf 轮廓，
     * 直接把 CFF/OTTO 字体（.otf、Noto CJK 的 .ttc）丢进去只会抛「字体无 glyf/loca」，
     * 用户看不懂。这里提前拦下并说清该换什么。</p>
     */
    private static byte[] fontBytes(String fontId) {
        if (fontId != null && !fontId.isBlank()) {
            byte[] uploaded = Store.bytes(fontId);
            if (uploaded != null && !Fonts.usableForAscii(uploaded)) {
                throw new IllegalArgumentException(
                    "这个字体 officia 用不了：不是 TrueType glyf 轮廓（多半是 CFF/OpenType-PS，"
                        + "如 .otf 或思源黑体/Noto CJK 的 ttc）。请换 glyf 轮廓的 .ttf/.ttc，"
                        + "如 simhei.ttf、msyh.ttf、wqy-zenhei.ttc。");
            }
            if (uploaded != null && !Fonts.usableForCjk(uploaded)) {
                throw new IllegalArgumentException(
                    "这个字体没有中文字形（多半是纯拉丁字体，如 DejaVuSans/Arial）。"
                        + "用它画中文不会报错，但中文位置会是空白——请换含中文的字体。");
            }
            return uploaded;
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

    private static int intOf(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
    }

    private static float floatOf(Map<String, String> q, String key, float def) {
        String v = q.get(key);
        return v == null || v.isBlank() ? def : Float.parseFloat(v.trim());
    }

    private static double doubleOf(Map<String, String> q, String key, double def) {
        String v = q.get(key);
        return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
    }

    private static boolean boolOf(Map<String, String> q, String key) {
        String v = q.get(key);
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    /** 截断小数位，避免 JSON 里出现 0.7599999999999999 这种噪声。 */
    private static double round(double v, int scale) {
        return Math.round(v * scale) / (double) scale;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? Collections.emptyList() : l;
    }
}
