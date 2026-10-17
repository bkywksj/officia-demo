package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反馈接口端到端：起一个真实的 JDK HttpServer，按 HTTP 走一遍提交 → 登录 → 列表 → 下载 → 改状态 → 复现 → 删除，
 * 并验证 Cookie 属性、跨站伪造防护、未登录拦截。
 *
 * <p>异常到状态码的映射与 {@link DemoServer} 一致（Denied → 401/403/405、入参错 → 400）。</p>
 *
 * @author officia-demo
 */
@DisplayName("反馈接口端到端")
class FeedbackHttpTest {

    @TempDir
    Path root;

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        Router r = new Router();
        Feedback fb = new Feedback(root, 1024 * 1024, 100, 10L * 1024 * 1024, 90);
        FeedbackRoutes.register(r, fb, new AdminAuth(root.resolve("admin.json"), "init-pass-1"));
        // 假装是一个转换接口：复现时后台照记录的操作路径再调它
        r.add("/api/test/topdf", (ex, q) -> Http.json(ex,
            Store.put("out.pdf", "application/pdf", ("%PDF re " + q.get("mode")).getBytes(StandardCharsets.UTF_8)).toJson().end()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            try {
                if (!r.dispatch(ex, ex.getRequestURI().getPath(), Http.query(ex.getRequestURI().getRawQuery()))) {
                    Http.error(ex, 404, "未知接口");
                }
            } catch (FeedbackRoutes.Denied e) {
                Http.error(ex, e.code, e.getMessage());
            } catch (IllegalArgumentException e) {
                Http.error(ex, 400, e.getMessage());
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

    /** 一次 HTTP 调用的结果。 */
    private static final class Res {
        int code;
        String body;
        String setCookie;
        byte[] bytes;
        String disposition;
    }

    private Res call(String method, String path, String json, String cookie, boolean csrf) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        if (cookie != null) {
            c.setRequestProperty("Cookie", cookie);
        }
        if (csrf) {
            c.setRequestProperty("X-Officia-Admin", "1");
        }
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        Res r = new Res();
        r.code = c.getResponseCode();
        r.setCookie = c.getHeaderField("Set-Cookie");
        r.disposition = c.getHeaderField("Content-Disposition");
        try (InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            r.bytes = b.toByteArray();
            r.body = new String(r.bytes, StandardCharsets.UTF_8);
        }
        return r;
    }

    private String loginCookie() throws Exception {
        Res r = call("POST", "/api/admin/login", "{\"username\":\"admin\",\"password\":\"init-pass-1\"}", null, false);
        assertThat(r.code).isEqualTo(200);
        return r.setCookie.split(";")[0];
    }

    @Test
    @DisplayName("完整流程：提交 → 登录 → 列表 → 下载 → 改状态 → 复现 → 删除")
    void fullFlow() throws Exception {
        Store.Blob src = Store.put("周报.docx", "application/octet-stream", "docx".getBytes(StandardCharsets.UTF_8));
        Store.Blob out = Store.put("周报.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        String body = "{\"note\":\"表格线丢了\",\"contact\":\"a@b.c\",\"capability\":\"Word\","
            + "\"op\":\"/api/test/topdf?id=" + src.id() + "&mode=layout\",\"agree\":true}";
        Res sub = call("POST", "/api/feedback?src=" + src.id() + "&out=" + out.id(), body, null, false);
        assertThat(sub.code).as(sub.body).isEqualTo(200);
        String id = String.valueOf(JsonReader.parseObject(sub.body).get("id"));

        String cookie = loginCookie();
        Res list = call("GET", "/api/admin/items", null, cookie, false);
        assertThat(list.code).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) JsonReader.parseObject(list.body).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("note")).isEqualTo("表格线丢了");
        assertThat(items.get(0).get("op")).as("操作路径去掉了 id，复现时换新的").isEqualTo("/api/test/topdf?mode=layout");

        Res file = call("GET", "/api/admin/file?id=" + id + "&which=source", null, cookie, false);
        assertThat(file.bytes).isEqualTo("docx".getBytes(StandardCharsets.UTF_8));
        assertThat(file.disposition).contains("filename*=UTF-8''%E5%91%A8%E6%8A%A5.docx");
        assertThat(call("GET", "/api/admin/file?id=" + id + "&which=output", null, cookie, false).bytes)
            .isEqualTo("%PDF".getBytes(StandardCharsets.UTF_8));

        Res upd = call("POST", "/api/admin/update?id=" + id, "{\"status\":\"fixed\",\"remark\":\"1.1.6 修\"}", cookie, true);
        assertThat(upd.code).as(upd.body).isEqualTo(200);
        assertThat(upd.body).contains("\"status\":\"fixed\"");

        Res rep = call("POST", "/api/admin/reproduce?id=" + id, "{}", cookie, true);
        assertThat(rep.code).as(rep.body).isEqualTo(200);
        String op = String.valueOf(JsonReader.parseObject(rep.body).get("op"));
        assertThat(op).startsWith("/api/test/topdf?mode=layout&id=");
        Res replay = call("POST", op, null, null, false);
        assertThat(replay.body).contains("out.pdf");

        assertThat(call("POST", "/api/admin/delete?id=" + id, "{}", cookie, true).code).isEqualTo(200);
        assertThat(call("GET", "/api/admin/item?id=" + id, null, cookie, false).code).isEqualTo(404);
    }

    @Test
    @DisplayName("登录 Cookie：HttpOnly + SameSite=Strict；经 HTTPS 反代时带 Secure")
    void cookieAttributes() throws Exception {
        Res r = call("POST", "/api/admin/login", "{\"username\":\"admin\",\"password\":\"init-pass-1\"}", null, false);
        assertThat(r.setCookie).contains("officia_admin=").contains("HttpOnly").contains("SameSite=Strict")
            .doesNotContain("Secure");
    }

    @Test
    @DisplayName("没登录：后台接口 401；登录了但改动类请求没带自定义头：403；GET 调改动接口：405")
    void guards() throws Exception {
        assertThat(call("GET", "/api/admin/items", null, null, false).code).isEqualTo(401);
        assertThat(call("GET", "/api/admin/items", null, "officia_admin=forged", false).code).isEqualTo(401);
        String cookie = loginCookie();
        assertThat(call("POST", "/api/admin/delete?id=20261017-000000-000000", "{}", cookie, false).code)
            .as("跨站伪造：没有 X-Officia-Admin").isEqualTo(403);
        assertThat(call("GET", "/api/admin/delete?id=20261017-000000-000000", null, cookie, true).code).isEqualTo(405);
        assertThat(call("GET", "/api/admin/session", null, cookie, false).body).contains("\"loggedIn\":true");
        assertThat(call("POST", "/api/admin/logout", "{}", cookie, true).code).isEqualTo(200);
        assertThat(call("GET", "/api/admin/items", null, cookie, false).code).as("退出后凭证作废").isEqualTo(401);
    }

    @Test
    @DisplayName("提交校验：没勾同意、没写备注、文件不在结果仓 → 400")
    void submitValidation() throws Exception {
        Store.Blob src = Store.put("a.docx", "application/octet-stream", new byte[10]);
        assertThat(call("POST", "/api/feedback?src=" + src.id(), "{\"note\":\"x\",\"agree\":false}", null, false).body)
            .contains("勾选同意");
        assertThat(call("POST", "/api/feedback?src=" + src.id(), "{\"note\":\"  \",\"agree\":true}", null, false).body)
            .contains("备注必填");
        Res gone = call("POST", "/api/feedback?src=nope", "{\"note\":\"x\",\"agree\":true}", null, false);
        assertThat(gone.code).isEqualTo(400);
        assertThat(gone.body).contains("找不到");
        assertThat(call("GET", "/api/feedback?src=" + src.id(), null, null, false).code).isEqualTo(405);
    }

    @Test
    @DisplayName("操作路径只认 /api/ 下的，并去掉 id 参数")
    void sanitizeOp() {
        assertThat(FeedbackRoutes.sanitizeOp("/api/words/topdf?id=7&mode=flow")).isEqualTo("/api/words/topdf?mode=flow");
        assertThat(FeedbackRoutes.sanitizeOp("/api/pdf/merge?ids=1,2")).isEqualTo("/api/pdf/merge?ids=1,2");
        assertThat(FeedbackRoutes.sanitizeOp("https://evil.example/api/x")).isNull();
        assertThat(FeedbackRoutes.sanitizeOp("/admin")).isNull();
        assertThat(FeedbackRoutes.sanitizeOp(null)).isNull();
    }
}
