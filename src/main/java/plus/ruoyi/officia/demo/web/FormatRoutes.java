package plus.ruoyi.officia.demo.web;

import plus.ruoyi.officia.words.FormatOptions;
import plus.ruoyi.officia.words.FormatReport;
import plus.ruoyi.officia.words.FormatResult;
import plus.ruoyi.officia.words.FormatSpec;
import plus.ruoyi.officia.words.OfficiaWords;
import plus.ruoyi.officia.words.ParagraphRole;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文档格式规范化：按一套格式规范统一 Word 文档的标题、正文、表格、落款与页面，单份或批量，导出 docx / PDF。
 *
 * <p>规范从哪来（查询参数 {@code spec}）：</p>
 * <ul>
 *   <li>{@code preset:gongwen}（缺省）——内置的党政机关公文预设（GB/T 9704—2012）；</li>
 *   <li>某个已上传 Blob 的 id——用户上传的规范 JSON，或 {@code /api/format/extract} 从范文提取出来的。</li>
 * </ul>
 *
 * <p>判断不准的段落（存疑）officia 不改。前端把用户确认的结果用 {@code overrides=12:HEADING:2,15:BODY}
 * 传回来再处理一次即可——段落序号与报告里的一致，服务端不保存任何状态。</p>
 *
 * @author officia-demo
 */
final class FormatRoutes {

    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private FormatRoutes() {
    }

    static void register(Router r) {
        // 内置预设清单（下拉框用）
        r.add("/api/format/presets", (ex, q) -> {
            List<Object> arr = new ArrayList<>();
            for (String id : FormatSpec.presetIds()) {
                arr.add(Json.obj().put("id", "preset:" + id).put("name", FormatSpec.preset(id).getName()));
            }
            Http.json(ex, Json.obj().put("presets", arr).end());
        });

        // 校验用户上传的规范 JSON：读得进来才算数，顺便回显规范化后的写法
        r.add("/api/format/spec", (ex, q) -> {
            FormatSpec spec = FormatSpec.fromJson(Store.bytes(q.get("id")));
            Http.json(ex, withRaw(Json.obj().put("specId", q.get("id"))
                .put("name", spec.getName() == null ? "自定义规范" : spec.getName()), "spec", spec.toJson()));
        });

        // 从排好的范文提取规范，存成一个 JSON Blob，后续用它的 id 当 spec
        r.add("/api/format/extract", (ex, q) -> {
            long t0 = System.nanoTime();
            FormatSpec spec = OfficiaWords.extractFormatSpec(Store.bytes(q.get("id")));
            String json = spec.toJson();
            Store.Blob b = Store.put(ApiRoutes.outName(q.get("id"), "规范", "json", "规范.json"),
                "application/json", json.getBytes(StandardCharsets.UTF_8)).withMs((System.nanoTime() - t0) / 1_000_000);
            Http.json(ex, withRaw(Json.obj().put("specId", b.id()).put("name", b.name())
                .put("ms", (System.nanoTime() - t0) / 1_000_000), "spec", json));
        });

        // 只检查不改：逐段角色 + 不合规项
        r.add("/api/format/check", (ex, q) -> {
            long t0 = System.nanoTime();
            FormatReport report = OfficiaWords.checkFormat(Store.bytes(q.get("id")), spec(q.get("spec")), options(q));
            Json meta = Json.obj().put("ms", (System.nanoTime() - t0) / 1_000_000)
                .put("issues", report.getIssueCount()).put("uncertain", report.getUncertainCount())
                .put("compliant", report.isCompliant());
            Http.json(ex, withRaw(meta, "report", report.toJson()));
        });

        // 单份规范化：docx（可选 PDF）+ 改完后的自检报告
        r.add("/api/format/normalize", (ex, q) -> {
            String id = q.get("id");
            long t0 = System.nanoTime();
            FormatResult res = OfficiaWords.normalizeWithReport(Store.bytes(id), spec(q.get("spec")), options(q));
            Json meta = Json.obj()
                .put("docx", ApiRoutes.result(ApiRoutes.outName(id, "规范化", "docx", "规范化.docx"), DOCX_MIME, res.getDocx(), t0))
                .put("modified", res.getModifiedParagraphs())
                .put("removed", res.getRemovedParagraphs())
                .put("issues", res.getReport().getIssueCount())
                .put("uncertain", res.getReport().getUncertainCount());
            if ("1".equals(q.get("pdf"))) {
                long t1 = System.nanoTime();
                meta.put("pdf", ApiRoutes.result(ApiRoutes.outName(id, "规范化", "pdf", "规范化.pdf"), "application/pdf",
                    OfficiaWords.toPdf(res.getDocx()), t1));
            }
            Http.json(ex, withRaw(meta, "report", res.getReport().toJson()));
        });

        // 批量：逐份处理、单份失败不影响其余，结果打成一个 zip（附汇总表）
        r.add("/api/format/batch", (ex, q) -> {
            String ids = q.get("ids");
            if (ids == null || Http.blank(ids)) {
                throw new IllegalArgumentException("未指定输入文件（ids 为空）");
            }
            FormatSpec spec = spec(q.get("spec"));
            FormatOptions opts = options(q);
            boolean pdf = "1".equals(q.get("pdf"));
            long t0 = System.nanoTime();
            List<Object> items = new ArrayList<>();
            StringBuilder csv = new StringBuilder("﻿文件,结果,改写段数,待确认段数,剩余问题,说明\n");
            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            int ok = 0;
            try (ZipOutputStream zip = new ZipOutputStream(zipBytes, StandardCharsets.UTF_8)) {
                Map<String, Integer> usedNames = new LinkedHashMap<>();
                for (String raw : ids.split(",")) {
                    String id = raw.trim();
                    if (id.isEmpty()) {
                        continue;
                    }
                    Store.Blob src = Store.get(id);
                    String name = src == null ? id : src.name();
                    Json item = Json.obj().put("id", id).put("name", name);
                    try {
                        if (src == null) {
                            throw new IllegalArgumentException("文件已过期，请重新上传");
                        }
                        FormatResult res = OfficiaWords.normalizeWithReport(src.data(), spec, opts);
                        String base = unique(usedNames, baseName(name));
                        put(zip, base + ".docx", res.getDocx());
                        if (pdf) {
                            put(zip, base + ".pdf", OfficiaWords.toPdf(res.getDocx()));
                        }
                        int unc = res.getReport().getUncertainCount();
                        item.put("ok", true).put("modified", res.getModifiedParagraphs()).put("uncertain", unc)
                            .put("issues", res.getReport().getIssueCount());
                        csv.append(csvCell(name)).append(",成功,").append(res.getModifiedParagraphs()).append(',')
                            .append(unc).append(',').append(res.getReport().getIssueCount()).append(',')
                            .append(unc > 0 ? "有存疑段落未改，可单份打开确认后重新处理" : "").append('\n');
                        ok++;
                    } catch (RuntimeException e) {
                        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        item.put("ok", false).put("error", msg);
                        csv.append(csvCell(name)).append(",失败,,,,").append(csvCell(msg)).append('\n');
                    }
                    items.add(item);
                }
                put(zip, "汇总.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
            }
            Json meta = Json.obj()
                .put("zip", ApiRoutes.result("格式规范化-" + ok + "份.zip", "application/zip", zipBytes.toByteArray(), t0))
                .put("ok", ok).put("total", items.size()).put("items", items);
            Http.json(ex, meta.end());
        });
    }

    /** 解析规范来源：缺省用公文预设；{@code preset:xxx} 取内置预设；否则当作已上传规范 JSON 的 Blob id。 */
    static FormatSpec spec(String s) {
        if (s == null || Http.blank(s)) {
            return FormatSpec.preset("gongwen");
        }
        if (s.startsWith("preset:")) {
            return FormatSpec.preset(s.substring("preset:".length()));
        }
        return FormatSpec.fromJson(Store.bytes(s));
    }

    /** 处理选项：人工认定、删空行、保留强调、清颜色、存疑门槛 */
    static FormatOptions options(Map<String, String> q) {
        FormatOptions o = FormatOptions.defaults()
            .removeEmptyParagraphs("1".equals(q.get("removeEmpty")))
            .keepEmphasis(!"0".equals(q.get("keepEmphasis")))
            .clearColors(!"0".equals(q.get("clearColors")));
        String mc = q.get("minConfidence");
        if (mc != null && !Http.blank(mc)) {
            o.minConfidence(Double.parseDouble(mc));
        }
        String ov = q.get("overrides");
        if (ov != null && !Http.blank(ov)) {
            for (String one : ov.split(",")) {
                String[] p = one.trim().split(":");
                if (p.length < 2) {
                    throw new IllegalArgumentException("人工认定格式应为 序号:角色[:级别]，如 12:HEADING:2，实为 " + one);
                }
                ParagraphRole role;
                try {
                    role = ParagraphRole.valueOf(p[1].trim());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("认不出的段落角色: " + p[1]);
                }
                int level = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
                o.override(Integer.parseInt(p[0].trim()), role, level);
            }
        }
        return o;
    }

    /** 在一段 Json 后面拼上一个现成的 JSON 值（officia 的报告 / 规范自带 toJson，不必拆开重组） */
    private static String withRaw(Json head, String key, String rawJson) {
        String h = head.end();
        StringBuilder sb = new StringBuilder(h.length() + rawJson.length() + key.length() + 8);
        sb.append(h, 0, h.length() - 1);
        if (h.length() > 2) {
            sb.append(',');
        }
        Json.writeString(sb, key);
        sb.append(':').append(rawJson).append('}');
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws java.io.IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** 去掉扩展名与路径分隔符（上传名是用户给的，不可信） */
    private static String baseName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        n = dot > 0 ? n.substring(0, dot) : n;
        return n.isEmpty() ? "文档" : n;
    }

    /** 批量里同名文件加序号，免得 zip 里互相覆盖 */
    private static String unique(Map<String, Integer> used, String base) {
        Integer n = used.get(base);
        used.put(base, n == null ? 1 : n + 1);
        return n == null ? base : base + "(" + (n + 1) + ")";
    }

    private static String csvCell(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
