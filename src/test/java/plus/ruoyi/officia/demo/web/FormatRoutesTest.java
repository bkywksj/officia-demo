package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.common.exception.OfficiaException;
import plus.ruoyi.officia.words.OfficiaWords;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 格式规范化接口端到端：起真实 HttpServer，走 预设清单 → 检查 → 规范化（含 PDF）→ 人工认定重做 → 从范文提取规范 →
 * 用提取的规范处理 → 批量打包（含一份坏 id，单份失败不影响其余）→ 入参错误。
 *
 * @author officia-demo
 */
@DisplayName("格式规范化接口端到端")
class FormatRoutesTest {

    private HttpServer server;
    private String base;

    /** 一份乱格式的公文：标题、主送、两级标题、正文（段首用全角空格冒充缩进） */
    private static final String MESSY = String.join("\n\n",
        "关于加强档案管理工作的通知",
        "各区县人民政府：",
        "　　为进一步规范档案管理，提高档案工作的精准性和实效性，现就有关事项通知如下。",
        "一、总体要求",
        "　　各单位要充分认识做好这项工作的重要意义，切实把思想和行动统一到决策部署上来。",
        "（一）加强组织领导",
        "　　主要负责同志要亲自研究部署，分管负责同志要具体抓好落实，确保各项任务落到实处。");

    @BeforeEach
    void start() throws Exception {
        Router r = new Router();
        FormatRoutes.register(r);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                if (!r.dispatch(ex, ex.getRequestURI().getPath(), Http.query(ex.getRequestURI().getRawQuery()))) {
                    Http.error(ex, 404, "未知接口");
                }
            } catch (IllegalArgumentException | OfficiaException e) {
                Http.error(ex, 400, e.getMessage());     // 与 DemoServer 的映射一致
            } catch (Exception e) {
                Http.error(ex, 500, e.toString());
            } finally {
                ex.close();
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("检查 → 规范化（docx + PDF）→ 自检零问题；预设清单里有公文")
    void checkAndNormalize() throws Exception {
        String id = docx("通知.docx");

        Map<String, Object> presets = post("/api/format/presets");
        assertThat(String.valueOf(presets.get("presets"))).contains("preset:gongwen").contains("GB/T 9704");

        Map<String, Object> check = post("/api/format/check?id=" + id);
        assertThat(num(check.get("issues"))).as("乱格式文档有不合规项").isGreaterThan(0);
        assertThat(check.get("report")).as("报告原样嵌入").isInstanceOf(Map.class);

        Map<String, Object> norm = post("/api/format/normalize?id=" + id + "&pdf=1");
        assertThat(num(norm.get("modified"))).isGreaterThan(0);
        assertThat(num(norm.get("issues"))).as("改完自检").isZero();
        Map<?, ?> docx = (Map<?, ?>) norm.get("docx");
        Map<?, ?> pdf = (Map<?, ?>) norm.get("pdf");
        assertThat(String.valueOf(docx.get("name"))).isEqualTo("通知-规范化.docx");
        assertThat(new String(Store.bytes(String.valueOf(pdf.get("id"))), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        // 再查一次改好的文档：零问题
        Map<String, Object> again = post("/api/format/check?id=" + docx.get("id"));
        assertThat(num(again.get("issues"))).isZero();
    }

    @Test
    @DisplayName("人工认定随请求传回：把正文第 2 段认成 2 级标题，报告里就按认定的角色")
    void overridesAreApplied() throws Exception {
        String id = docx("通知.docx");
        Map<String, Object> norm = post("/api/format/normalize?id=" + id + "&overrides=2:HEADING:2");
        Map<?, ?> report = (Map<?, ?>) norm.get("report");
        Map<?, ?> item2 = (Map<?, ?>) ((List<?>) report.get("items")).get(2);
        assertThat(item2.get("role")).isEqualTo("HEADING");
        assertThat(num(item2.get("level"))).isEqualTo(2);

        Res bad = call("/api/format/normalize?id=" + id + "&overrides=2:FOO");
        assertThat(bad.code).isEqualTo(400);
        assertThat(bad.body).contains("认不出的段落角色");
    }

    @Test
    @DisplayName("从范文提取规范 → 存成 JSON Blob → 用它的 id 当规范处理；上传的规范 JSON 先校验")
    void extractAndReuseSpec() throws Exception {
        String sample = String.valueOf(((Map<?, ?>) post("/api/format/normalize?id=" + docx("范文.docx")).get("docx")).get("id"));

        Map<String, Object> ext = post("/api/format/extract?id=" + sample);
        String specId = String.valueOf(ext.get("specId"));
        assertThat(String.valueOf(ext.get("name"))).endsWith("规范.json");
        assertThat(String.valueOf(ext.get("spec"))).contains("仿宋");

        Map<String, Object> norm = post("/api/format/normalize?id=" + docx("草稿.docx") + "&spec=" + specId);
        assertThat(num(norm.get("modified"))).isGreaterThan(0);

        Map<String, Object> checked = post("/api/format/spec?id=" + specId);
        assertThat(checked.get("specId")).isEqualTo(specId);
        String junk = Store.put("坏.json", "application/json", "{\"styles\":{\"body\":{\"size\":\"九号\"}}}"
            .getBytes(StandardCharsets.UTF_8)).id();
        Res bad = call("/api/format/spec?id=" + junk);
        assertThat(bad.code).isEqualTo(400);
        assertThat(bad.body).contains("styles.body.size");
    }

    @Test
    @DisplayName("批量：逐份处理打成 zip（同名加序号、附汇总表），坏 id 单独记失败、不拖累其余")
    void batchZip() throws Exception {
        String a = docx("通知.docx");
        String b = docx("通知.docx");
        Map<String, Object> res = post("/api/format/batch?ids=" + a + "," + b + ",b404&pdf=1");
        assertThat(num(res.get("ok"))).isEqualTo(2);
        assertThat(num(res.get("total"))).isEqualTo(3);
        List<?> items = (List<?>) res.get("items");
        assertThat(((Map<?, ?>) items.get(2)).get("ok")).isEqualTo(false);
        assertThat(String.valueOf(((Map<?, ?>) items.get(2)).get("error"))).contains("过期");

        byte[] zip = Store.bytes(String.valueOf(((Map<?, ?>) res.get("zip")).get("id")));
        assertThat(zipNames(zip)).containsExactly("通知.docx", "通知.pdf", "通知(2).docx", "通知(2).pdf", "汇总.csv");
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 用 officia 把乱格式文字转成 docx 存进 Store（段首全角空格保留在文字里） */
    private static String docx(String name) {
        return Store.put(name, "application/octet-stream",
            OfficiaWords.markdownToDocx(MESSY.getBytes(StandardCharsets.UTF_8))).id();
    }

    private static long num(Object o) {
        return ((Number) o).longValue();
    }

    private static List<String> zipNames(byte[] zip) throws Exception {
        List<String> out = new ArrayList<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                out.add(e.getName());
            }
        }
        return out;
    }

    private Map<String, Object> post(String path) throws Exception {
        Res r = call(path);
        assertThat(r.code).as(r.body).isEqualTo(200);
        return JsonReader.parseObject(r.body);
    }

    private static final class Res {
        int code;
        String body;
    }

    private Res call(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("POST");
        Res r = new Res();
        r.code = c.getResponseCode();
        try (InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            r.body = new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
        return r;
    }
}
