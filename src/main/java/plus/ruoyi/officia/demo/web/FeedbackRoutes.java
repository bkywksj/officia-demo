package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 「效果不佳的文件」反馈的接口：用户提交 {@code /api/feedback}，后台 {@code /api/admin/*}（页面在 {@code /admin}）。
 *
 * <p>只在 {@link Feedback#fromEnv()} 启用时注册；没开时这些端点不存在，健康检查报 {@code feedback:false}，
 * 前端据此不显示反馈按钮——客户在本机跑测试台时不会出现「反馈给开发者」却没处可去的按钮。</p>
 *
 * <p>后台防护：会话凭证放 HttpOnly + SameSite=Strict 的 Cookie；所有改动类请求另要求自定义头
 * {@code X-Officia-Admin}——浏览器不允许其它站点跨站带这种头（没开 CORS），配合 SameSite 挡住跨站伪造请求。</p>
 *
 * @author officia-demo
 */
final class FeedbackRoutes {

    private static final String COOKIE = "officia_admin";
    private static final String CSRF_HEADER = "X-Officia-Admin";
    private static final int MAX_FORM_BYTES = 64 * 1024;

    private static Feedback feedback;
    private static AdminAuth auth;

    private FeedbackRoutes() {
    }

    /** 是否启用了反馈（健康检查与前端用）。 */
    static boolean enabled() {
        return feedback != null;
    }

    /**
     * 按环境变量初始化并注册端点。
     * 启用需要两个变量：{@code OFFICIA_FEEDBACK_DIR}（数据目录）与 {@code OFFICIA_ADMIN_PASSWORD}（后台初始口令，
     * 只在第一次生成 admin.json 时用；之后以文件里的哈希为准）。口令不写进代码与仓库。
     */
    static void register(Router r) {
        try {
            Feedback fb = Feedback.fromEnv();
            if (fb == null) {
                return;
            }
            String dir = System.getenv("OFFICIA_FEEDBACK_DIR").trim();
            Path adminFile = Paths.get(dir, "admin.json");
            String initial = System.getenv("OFFICIA_ADMIN_PASSWORD");
            if (!Files.exists(adminFile) && Http.blank(initial)) {
                System.err.println("反馈功能未启用：首次启动需要环境变量 OFFICIA_ADMIN_PASSWORD 作为后台初始口令");
                return;
            }
            register(r, fb, new AdminAuth(adminFile, initial));
            startPurger(fb);
        } catch (IOException e) {
            System.err.println("反馈功能未启用：数据目录不可用 —— " + e.getMessage());
        }
    }

    /** 注册端点（测试可直接注入）。 */
    static void register(Router r, Feedback fb, AdminAuth a) {
        feedback = fb;
        auth = a;
        r.add("/api/feedback", FeedbackRoutes::submit);
        r.add("/api/admin/session", (ex, q) -> Http.json(ex, Json.obj()
            .put("loggedIn", auth.valid(token(ex))).put("username", AdminAuth.USERNAME).end()));
        r.add("/api/admin/login", FeedbackRoutes::login);
        r.add("/api/admin/logout", (ex, q) -> {
            requirePost(ex);
            auth.logout(token(ex));
            ex.getResponseHeaders().add("Set-Cookie", cookie(ex, "", 0));
            Http.json(ex, Json.obj().put("ok", true).end());
        });
        r.add("/api/admin/items", (ex, q) -> {
            guard(ex, false);
            List<Object> rows = new ArrayList<>();
            for (Map<String, Object> m : feedback.list()) {
                rows.add(m);
            }
            Http.json(ex, Json.obj().put("items", rows).put("statuses", Feedback.STATUS)
                .put("usedBytes", feedback.usedBytes()).end());
        });
        r.add("/api/admin/item", (ex, q) -> {
            guard(ex, false);
            Map<String, Object> m = feedback.get(q.get("id"));
            if (m == null) {
                Http.error(ex, 404, "反馈不存在");
                return;
            }
            Http.json(ex, Json.obj().put("item", m).end());
        });
        r.add("/api/admin/file", FeedbackRoutes::download);
        r.add("/api/admin/update", (ex, q) -> {
            guard(ex, true);
            Map<String, Object> body = JsonReader.parseObject(Http.smallBody(ex, MAX_FORM_BYTES));
            Map<String, Object> m = feedback.update(q.get("id"), str(body.get("status")), str(body.get("remark")));
            if (m == null) {
                Http.error(ex, 404, "反馈不存在");
                return;
            }
            Http.json(ex, Json.obj().put("item", m).end());
        });
        r.add("/api/admin/delete", (ex, q) -> {
            guard(ex, true);
            if (!feedback.delete(q.get("id"))) {
                Http.error(ex, 404, "反馈不存在");
                return;
            }
            Http.json(ex, Json.obj().put("ok", true).end());
        });
        r.add("/api/admin/password", (ex, q) -> {
            guard(ex, true);
            Map<String, Object> body = JsonReader.parseObject(Http.smallBody(ex, MAX_FORM_BYTES));
            String err = auth.changePassword(str(body.get("old")), str(body.get("new")), token(ex));
            if (err != null) {
                Http.error(ex, 400, err);
                return;
            }
            Http.json(ex, Json.obj().put("ok", true).end());
        });
        r.add("/api/admin/reproduce", FeedbackRoutes::reproduce);
    }

    // ---------------------------------------------------------------- 用户提交

    /**
     * {@code POST /api/feedback?src=<上传件 id>&out=<结果 id，可选>}，请求体 JSON：
     * {@code {note, contact, capability, op, agree}}。文件取自测试台的结果仓，不用再传一遍。
     */
    private static void submit(HttpExchange ex, Map<String, String> q) throws Exception {
        requirePost(ex);
        Map<String, Object> body = JsonReader.parseObject(Http.smallBody(ex, MAX_FORM_BYTES));
        if (!Boolean.TRUE.equals(body.get("agree"))) {
            throw new IllegalArgumentException("请先勾选同意：确认文件不含涉密或个人敏感信息");
        }
        String note = str(body.get("note"));
        if (Http.blank(note)) {
            throw new IllegalArgumentException("请写一下哪里不对（备注必填）");
        }
        note = limit(note, 2000, "备注");
        Store.Blob src = Store.get(q.get("src"));
        if (src == null) {
            throw new IllegalArgumentException("找不到要反馈的文件（服务可能已重启），请重新上传");
        }
        Store.Blob out = Http.blank(q.get("out")) ? null : Store.get(q.get("out"));
        String ip = Http.clientIp(ex);
        if (!feedback.allow(ip, System.currentTimeMillis())) {
            throw new IllegalArgumentException("提交太频繁了，请一小时后再试");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("capability", limit(str(body.get("capability")), 60, "能力"));
        meta.put("op", sanitizeOp(str(body.get("op"))));
        meta.put("note", note);
        meta.put("contact", limit(str(body.get("contact")), 100, "联系方式"));
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("officia", ApiRoutes.officiaVersion());
        env.put("java", System.getProperty("java.version"));
        env.put("ip", ip);
        env.put("userAgent", limit(Http.header(ex, "User-Agent"), 300, "UA"));
        meta.put("env", env);
        String id = feedback.create(src.name(), src.data(),
            out == null ? null : out.name(), out == null ? null : out.data(), meta);
        Http.json(ex, Json.obj().put("ok", true).put("id", id).end());
    }

    // ---------------------------------------------------------------- 后台

    private static void login(HttpExchange ex, Map<String, String> q) throws Exception {
        requirePost(ex);
        Map<String, Object> body = JsonReader.parseObject(Http.smallBody(ex, MAX_FORM_BYTES));
        AdminAuth.Login r = auth.login(str(body.get("username")), str(body.get("password")), Http.clientIp(ex));
        if (r.token == null) {
            Http.error(ex, 401, r.error);
            return;
        }
        ex.getResponseHeaders().add("Set-Cookie", cookie(ex, r.token, 8 * 3600));
        Http.json(ex, Json.obj().put("ok", true).end());
    }

    private static void download(HttpExchange ex, Map<String, String> q) throws Exception {
        guard(ex, false);
        String which = q.getOrDefault("which", "source");
        Path p = feedback.file(q.get("id"), which);
        Map<String, Object> m = feedback.get(q.get("id"));
        if (p == null || m == null) {
            Http.error(ex, 404, "文件不存在");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) m.get(which);
        String name = String.valueOf(info.get("name"));
        String encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        Http.send(ex, 200, Http.mimeOf(name), Files.readAllBytes(p));
    }

    /**
     * 「在测试台复现」：把原文件放回结果仓，返回新的上传件信息与当时的操作路径（id 换成新的），
     * 后台页面照这个路径再调一次转换接口，就是用当前版本重跑一遍。
     */
    private static void reproduce(HttpExchange ex, Map<String, String> q) throws Exception {
        guard(ex, true);
        Map<String, Object> m = feedback.get(q.get("id"));
        Path p = feedback.file(q.get("id"), "source");
        if (m == null || p == null) {
            Http.error(ex, 404, "反馈不存在");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) m.get("source");
        String name = String.valueOf(info.get("name"));
        Store.Blob b = Store.put(name, Http.mimeOf(name), Files.readAllBytes(p));
        String op = str(m.get("op"));
        String replay = Http.blank(op) ? null : op + (op.contains("?") ? "&" : "?") + "id=" + b.id();
        Http.json(ex, Json.obj().put("blob", b.toJson()).put("op", replay).end());
    }

    /**
     * 后台接口的门：没登录 401；改动类请求还要带自定义头（防跨站伪造）并且必须是 POST。
     *
     * @throws Denied 由 {@link DemoServer} 转成 401 / 403 / 405
     */
    private static void guard(HttpExchange ex, boolean mutating) {
        if (!auth.valid(token(ex))) {
            throw new Denied(401, "请先登录");
        }
        if (mutating) {
            requirePost(ex);
            if (!"1".equals(Http.header(ex, CSRF_HEADER))) {
                throw new Denied(403, "请求来源不对");
            }
        }
    }

    /** 未登录 / 来源不对；{@link DemoServer} 把它转成对应状态码。 */
    static final class Denied extends RuntimeException {
        final int code;

        Denied(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void requirePost(HttpExchange ex) {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            throw new Denied(405, "只接受 POST");
        }
    }

    private static String token(HttpExchange ex) {
        String c = Http.header(ex, "Cookie");
        if (c == null) {
            return null;
        }
        for (String part : c.split(";")) {
            String kv = part.trim();
            if (kv.startsWith(COOKIE + "=")) {
                return kv.substring(COOKIE.length() + 1);
            }
        }
        return null;
    }

    private static String cookie(HttpExchange ex, String value, int maxAge) {
        return COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge
            + (Http.viaHttps(ex) ? "; Secure" : "");
    }

    /** 操作路径只认 /api/ 下的，并去掉 id 参数（复现时换成新 id）；其余参数原样保留。 */
    static String sanitizeOp(String op) {
        if (Http.blank(op) || !op.startsWith("/api/") || op.length() > 500) {
            return null;
        }
        int qi = op.indexOf('?');
        if (qi < 0) {
            return op;
        }
        StringBuilder sb = new StringBuilder(op.substring(0, qi));
        String sep = "?";
        for (String kv : op.substring(qi + 1).split("&")) {
            if (kv.isEmpty() || kv.startsWith("id=")) {
                continue;
            }
            sb.append(sep).append(kv);
            sep = "&";
        }
        return sb.toString();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String limit(String s, int max, String what) {
        if (s == null) {
            return null;
        }
        if (s.length() > max) {
            throw new IllegalArgumentException(what + "最多 " + max + " 个字");
        }
        return s;
    }

    /** 每 6 小时清理一次过期反馈（守护线程，不挡进程退出）；启动时先清一次。 */
    private static void startPurger(Feedback fb) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "officia-feedback-purge");
            t.setDaemon(true);
            return t;
        });
        s.scheduleAtFixedRate(() -> {
            try {
                int n = fb.purge(System.currentTimeMillis());
                if (n > 0) {
                    System.out.println("反馈清理：删除 " + n + " 条已结案且超过保留期的反馈");
                }
            } catch (Exception e) {
                System.err.println("反馈清理失败：" + e.getMessage());
            }
        }, 0, 6, TimeUnit.HOURS);
    }
}
