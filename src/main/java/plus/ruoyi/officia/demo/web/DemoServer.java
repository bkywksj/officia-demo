package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.common.exception.OfficiaException;
import plus.ruoyi.officia.license.OfficiaLicense;
import plus.ruoyi.officia.pdf.OfficiaPdf;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Officia 能力测试台 —— 启动入口。
 *
 * <p>用 JDK 内置 {@link HttpServer}（零第三方依赖，与 officia 同样的克制）起一个本机服务：
 * 静态页从 classpath <code>/web/index.html</code> 读，<code>/api/*</code> 转发给 {@link ApiRoutes}
 * 调用真实的 OfficiaXxx 门面。启动后控制台打印可点击链接，并尝试自动拉起浏览器。</p>
 *
 * <pre>{@code
 *   java -jar officia-demo-1.0.0.jar          # 默认 8080，被占用则自动顺延
 *   java -jar officia-demo-1.0.0.jar 9090     # 指定端口
 *   java -jar officia-demo-1.0.0.jar 9090 --no-open   # 不自动开浏览器
 *   java -jar officia-demo-1.0.0.jar --host 0.0.0.0   # 监听所有网卡（容器内必须）
 * }</pre>
 *
 * @author officia-demo
 */
public final class DemoServer {

    private static final int DEFAULT_PORT = 8080;

    /**
     * 默认只监听本机回环。
     *
     * <p>测试台<b>没有任何鉴权</b>，且能上传文件、加载 License、跑重 CPU/内存的转换——
     * 默认对外监听等于把这些能力白送给同网段任何人，所以默认值必须是回环地址。
     * 容器内没有"宿主本机"的概念（回环只在容器内可达，端口映射转发到 eth0 会连不上），
     * 必须显式传 {@code --host 0.0.0.0}，再由 compose 把宿主端口只发布到 127.0.0.1。</p>
     */
    private static final String DEFAULT_HOST = "127.0.0.1";

    private DemoServer() {
    }

    public static void main(String[] args) throws Exception {
        int wanted = DEFAULT_PORT;
        boolean autoOpen = true;
        // 优先级：命令行 --host > 环境变量 OFFICIA_DEMO_HOST > 默认回环
        // 环境变量入口是给容器编排用的（compose 里改 environment 比改 ENTRYPOINT 方便）
        String host = trimToNull(System.getenv("OFFICIA_DEMO_HOST"));
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--no-open".equals(a)) {
                autoOpen = false;
            } else if (a.startsWith("--host=")) {
                host = trimToNull(a.substring("--host=".length()));
            } else if ("--host".equals(a) && i + 1 < args.length) {
                host = trimToNull(args[++i]);
            } else if (a.matches("\\d+")) {
                wanted = Integer.parseInt(a);
            }
        }
        if (host == null) {
            host = DEFAULT_HOST;
        }

        java.net.InetAddress bind;
        try {
            bind = java.net.InetAddress.getByName(host);
        } catch (java.net.UnknownHostException e) {
            // 提前失败并说清怎么改，否则 HttpServer.create 只会抛 "unresolved address"
            System.err.println("无法解析监听地址：" + host + "（本机用 127.0.0.1，容器内用 0.0.0.0）");
            return;
        }
        int port = freePortFrom(bind, wanted);

        // 默认打开强制门控，让测试台的"开箱行为"与真实发布版一致：
        // 未授权即降级（水印 + 限页）。想看完整输出，在「授权门控与对比」面板取消勾选即可。
        // （发布版 jar 里该开关恒开且关不掉，见 BuildFlags.ENFORCED_BY_DEFAULT）
        OfficiaLicense.enableEnforcement(true);

        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", DemoServer::handle);
        // 用线程池，支持并发上传/转换（大文档转换较慢，避免串行阻塞界面）
        ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()));
        server.setExecutor(pool);
        server.start();

        // 优雅停机：Ctrl+C 时给在途请求 1 秒收尾，再关线程池，避免半截响应与线程泄漏
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            pool.shutdown();
        }, "officia-demo-shutdown"));

        // 监听 0.0.0.0 时不能拿它当访问地址（浏览器打不开通配地址），回落到回环
        String url = "http://" + (bind.isAnyLocalAddress() ? "127.0.0.1" : host) + ":" + port + "/";
        banner(url, port, bind);
        if (autoOpen) {
            openBrowser(url);
        }
    }

    /** 去空白后为空则返回 null，便于"未设置"与"设了空串"统一处理。 */
    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 请求分发：/api/* → ApiRoutes；/api/result/{id} → 字节；其余 → 静态资源。 */
    private static void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = Http.query(ex.getRequestURI().getRawQuery());
        try {
            if (path.startsWith("/api/result/")) {
                serveResult(ex, path.substring("/api/result/".length()), q);
                return;
            }
            if (path.startsWith("/api/")) {
                if (!ApiRoutes.dispatch(ex, path, q)) {
                    Http.error(ex, 404, "未知接口: " + path);
                }
                return;
            }
            serveStatic(ex, path);
        } catch (Http.PayloadTooLargeException e) {
            // 请求体超限 → 413
            safeError(ex, 413, e.getMessage());
        } catch (IllegalArgumentException e) {
            // 入参问题（找不到 id、参数非法）→ 400
            safeError(ex, 400, e.getMessage());
        } catch (OfficiaException e) {
            // officia 业务异常绝大多数是"输入不合规"（如条码校验位错、文档格式不支持、口令错），
            // 属调用方可修正 → 400，而非 500。报 500 会让用户误以为服务故障。
            safeError(ex, 400, e.getMessage() == null ? "输入不合规" : e.getMessage());
        } catch (Throwable t) {
            // 真正意外 → 500，带类型便于定位
            String msg = t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "无详情" : t.getMessage());
            safeError(ex, 500, msg);
        } finally {
            ex.close();
        }
    }

    /** 输出结果字节：默认内联预览，?download=1 触发下载。 */
    private static void serveResult(HttpExchange ex, String id, Map<String, String> q) throws IOException {
        Store.Blob b = Store.get(id);
        if (b == null) {
            Http.error(ex, 404, "结果不存在（服务可能已重启）");
            return;
        }
        String disp = "1".equals(q.get("download")) ? "attachment" : "inline";
        // 文件名用 RFC 5987 编码，保证中文名下载正常
        String encoded = java.net.URLEncoder.encode(b.name(), StandardCharsets.UTF_8).replace("+", "%20");
        ex.getResponseHeaders().set("Content-Disposition", disp + "; filename*=UTF-8''" + encoded);
        Http.send(ex, 200, b.mime(), b.data());
    }

    /** classpath 静态资源（打进 jar，随 jar 分发）。 */
    private static void serveStatic(HttpExchange ex, String path) throws IOException {
        String res = "/".equals(path) || path.isEmpty() ? "/web/index.html" : "/web" + path;
        try (InputStream in = DemoServer.class.getResourceAsStream(res)) {
            if (in == null) {
                Http.error(ex, 404, "资源不存在: " + path);
                return;
            }
            Http.send(ex, 200, Http.mimeOf(res), in.readAllBytes());
        }
    }

    private static void safeError(HttpExchange ex, int code, String msg) {
        try {
            Http.error(ex, code, msg);
        } catch (IOException ignore) {
            // 连接已断开，无需处理
        }
    }

    /**
     * 从 wanted 起找一个可用端口（最多顺延 20 个）。
     *
     * <p>探测必须绑<b>实际监听地址</b>：绑 127.0.0.1 探通不代表 0.0.0.0 也空闲
     * （别的进程可能正占着同端口的其它网卡）。</p>
     */
    private static int freePortFrom(java.net.InetAddress bind, int wanted) {
        for (int p = wanted; p < wanted + 20; p++) {
            try (ServerSocket s = new ServerSocket(p, 1, bind)) {
                return s.getLocalPort();
            } catch (IOException ignore) {
                // 被占用，试下一个
            }
        }
        return wanted;
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable ignore) {
            // 无图形环境等，退回让用户手点链接
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Throwable ignore) {
            // 打不开就算了，控制台已有链接
        }
    }

    /**
     * 启动横幅：把访问链接打得足够醒目（多数终端可直接 Ctrl/⌘+点击打开）。
     *
     * <p>用 {@code native.encoding}（JDK17+ 给出的 OS 默认字符集）重建 stdout——
     * Windows 控制台是 GBK 而 JVM 默认 UTF-8，直接 println 中文会乱码。</p>
     */
    private static void banner(String url, int port, java.net.InetAddress bind) {
        try {
            // 有真实控制台 → 用控制台字符集（Windows 多为 GBK，直接显示中文正常）；
            // 被重定向到文件/管道 → 用 UTF-8（日志文件按 UTF-8 读才正常）。
            java.io.Console con = System.console();
            String enc = con != null ? con.charset().name() : "UTF-8";
            System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out), true, enc));
        } catch (Exception ignore) {
            // 拿不到编码就按默认输出，URL 是 ASCII 不受影响
        }
        String line = "──────────────────────────────────────────────────────────────";
        System.out.println();
        System.out.println(line);
        System.out.println("  Officia 能力测试台已启动");
        System.out.println(line);
        System.out.println("  访问地址： " + url);
        System.out.println("            http://localhost:" + port + "/");
        System.out.println("  监听地址： " + bind.getHostAddress() + ":" + port
            + (bind.isLoopbackAddress() ? "（仅本机可达）" : "（本机之外也可达）"));
        if (!bind.isLoopbackAddress()) {
            // 测试台无鉴权：非回环监听时把风险摆到最显眼处，别让人稀里糊涂暴露到公网
            System.out.println("  ⚠ 安全：  测试台无鉴权，任何能访问该地址的人都能上传文件并消耗本机 CPU/内存。");
            System.out.println("            容器内属正常（宿主端口应只发布到 127.0.0.1）；裸机上请确认这是你要的。");
        }
        System.out.println();
        System.out.println("  授权状态： " + (OfficiaLicense.isLicensed() ? "已授权" : "评估版（未加载 License）")
            + "    强制门控：" + (OfficiaLicense.isEnforced() ? "开" : "关"));
        // 判据必须是「officia 渲染中文时真正会用到的那个字体」，而不是"系统装了什么"——
        // 系统没有中文字体时 officia 会用<b>内置兜底字体</b>，水印照样画得出来。
        // 只看系统字体会误报：实测某容器只装了 DejaVu，横幅说"中文水印会是空白"，
        // 而实际转换出的 PDF 中文与水印全部正常（14776 个可解码汉字）。
        System.out.println("  中文字体： " + cjkStatus());
        System.out.println("  运行依赖： 仅 JDK + officia（运行时零第三方依赖）");
        // 大文件（设计型 PPTX 常上百 MB）撞上限时，用户第一反应是"传不上去"，先把边界摆出来
        System.out.println("  内存上限： 最大堆 " + Http.humanSize(Runtime.getRuntime().maxMemory())
            + "，单次上传上限 " + Http.humanSize(Http.MAX_BODY_BYTES) + "（java -Xmx4g -jar … 可调大）");
        System.out.println();
        System.out.println("  按 Ctrl+C 停止");
        System.out.println(line);
        System.out.println();
    }

    /**
     * 中文可渲染性：真的转一页含中文的 PDF 再读回来，并说明字形来自系统字体还是内置兜底。
     *
     * <p><b>为什么改成端到端实测</b>：此前探 officia 的 {@code FontRegistry/FontLoader} 内部注册表，
     * 但 Maven Central 上的 officia-all 经 ProGuard 混淆只保留公开门面，内部类不可用——
     * demo 作为消费方示例必须只依赖公开 API，否则客户照抄就编译不过。
     * 换个角度反而更准：与其问"注册表里有没有这个字形"，不如直接转一页看中文能不能读回来，
     * 这正是用户真正关心的结果，也顺带验证了整条渲染链。</p>
     *
     * <p>探针字取评估水印文案里的「授」。系统字体缺中文时 officia 会落到内置兜底字体，
     * 此时中文<b>可渲染</b>，只是字面变成内置字体的样子；只有连内置带系统都没有中文字形，
     * 才是真的会画空白。</p>
     *
     * @return 供启动横幅显示的一句话
     */
    private static String cjkStatus() {
        try {
            // 一页 CSV → PDF → 抽文本：能读回「授」即证明这条链真的画得出中文
            byte[] pdf = OfficiaCells.csvToPdf("授权,探针\n");
            boolean renderable = OfficiaPdf.extractText(pdf).contains("授");
            if (!renderable) {
                return "不可用（中文会画成空白，请在界面上传中文 TTF）";
            }
            return Fonts.hasCjk()
                ? "可用 —— 系统/挂载的中文 TTF"
                : "可用 —— 未探测到中文字体，走 officia 内置兜底（字面为内置字体）";
        } catch (RuntimeException e) {
            // 探测失败不该拦住服务启动，如实降级显示
            return "探测失败（" + e.getClass().getSimpleName() + "）";
        }
    }
}
