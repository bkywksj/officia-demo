package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 小工具：查询串解析、请求体读取、JSON/字节响应、MIME 推断。
 *
 * <p>刻意不做 multipart 解析——前端用 <code>fetch(url, {body: file})</code> 直接发<b>原始字节</b>，
 * 参数走查询串。少一层协议解析，既省代码也更稳（大文件同样适用）。</p>
 *
 * @author officia-demo
 */
final class Http {

    /**
     * 单次请求体上限：按 JVM 最大堆的 1/8 取，夹在 [64 MB, 1 GB] 之间。
     *
     * <p>不写死 100 MB 的原因：设计型 PPTX、高清图册动辄一两百 MB，写死会把正常文件挡在门外；
     * 但小堆机器上放任 1 GB 又会 OOM。上传件要在内存里驻留（{@link Store}）并参与转换，
     * 取 1/8 能给「源文件 + 中间态 + 产出」都留出余量。用 {@code -Xmx} 调大堆即同步放宽。</p>
     */
    static final long MAX_BODY_BYTES = maxBodyBytes();

    private static long maxBodyBytes() {
        long byHeap = Runtime.getRuntime().maxMemory() / 8;
        return Math.max(64L * 1024 * 1024, Math.min(1024L * 1024 * 1024, byHeap));
    }

    /** 扩展名 → MIME 静态表（比一长串 if 可读、可扩展）。 */
    private static final Map<String, String> MIME = new LinkedHashMap<>();

    static {
        MIME.put(".pdf", "application/pdf");
        MIME.put(".html", "text/html; charset=utf-8");
        MIME.put(".js", "application/javascript; charset=utf-8");
        MIME.put(".css", "text/css; charset=utf-8");
        MIME.put(".png", "image/png");
        MIME.put(".jpg", "image/jpeg");
        MIME.put(".jpeg", "image/jpeg");
        MIME.put(".gif", "image/gif");
        MIME.put(".bmp", "image/bmp");
        MIME.put(".csv", "text/csv; charset=utf-8");
        MIME.put(".txt", "text/plain; charset=utf-8");
        MIME.put(".eml", "message/rfc822");
        MIME.put(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME.put(".doc", "application/msword");
        MIME.put(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME.put(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MIME.put(".ttf", "font/ttf");
        MIME.put(".ttc", "font/ttf");
    }

    private Http() {
    }

    /** 解析查询串为 Map（已 URL 解码，支持中文参数）。 */
    static Map<String, String> query(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                map.put(dec(pair), "");
            } else {
                map.put(dec(pair.substring(0, i)), dec(pair.substring(i + 1)));
            }
        }
        return map;
    }

    private static String dec(String s) {
        // 用字符集名的重载：接 Charset 的那个要 JDK 10；UTF-8 是 JDK 必备字符集，异常不会发生
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 读完请求体字节，超过 {@link #MAX_BODY_BYTES} 即中止。
     *
     * <p>边读边计数而非先 readAllBytes 再判断——否则超大请求在判断之前就已经把内存吃掉了。</p>
     *
     * @throws PayloadTooLargeException 体积超限（上层转 413）
     */
    static byte[] body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY_BYTES) {
                    long actual = drain(in, total);
                    throw new PayloadTooLargeException(
                        "文件 " + humanSize(actual) + " 超过单次上传上限 " + humanSize(MAX_BODY_BYTES)
                            + "（启动时加 -Xmx 调大堆可同步放宽上限）");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 丢弃剩余请求体，只计数不留存。
     *
     * <p>🔴 超限后<b>必须</b>把客户端仍在发的字节读完再回 413：否则 HttpServer 收尾 exchange 时
     * 会直接断开底层连接，浏览器 fetch 拿到的是 <code>TypeError: Failed to fetch</code>，
     * 413 响应体里那句可读的错误根本送不到前端——用户只看见"Failed to fetch"，无从判断原因。</p>
     *
     * @param already 已读入的字节数
     * @return 请求体实际总字节（用于把真实体积写进错误消息）；触及兜底上限时为估算下界
     */
    private static long drain(InputStream in, long already) throws IOException {
        // 兜底：异常大的流不无限读下去，读满 4 倍上限就放弃（此时连接断开也认了）
        long limit = MAX_BODY_BYTES * 4;
        byte[] sink = new byte[8192];
        long total = already;
        int n;
        while (total < limit && (n = in.read(sink)) > 0) {
            total += n;
        }
        return total;
    }

    /**
     * 是否为 null、空串或只含空白。按 {@link Character#isWhitespace(int)} 逐码点判断，
     * 与 Java 11 的 {@code String.isBlank} 一致——本测试台要跑在 JDK 8 上，没有那个方法；
     * 不用 {@code trim().isEmpty()} 代替：它只认 {@code <= ' '}，全角空格会判成「非空」。
     */
    static boolean blank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return true;
    }

    /** 读完输入流，不关闭（Java 8 没有 {@code InputStream.readAllBytes}）。 */
    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 字节数转易读文本（错误消息与启动横幅共用）：不足 1 GB 用 MB，否则用 GB。 */
    static String humanSize(long bytes) {
        double mib = bytes / 1024.0 / 1024.0;
        return mib < 1024 ? String.format("%.1f MB", mib) : String.format("%.1f GB", mib / 1024.0);
    }

    /** 请求头（大小写不敏感），没有返回 null。 */
    static String header(HttpExchange ex, String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    /**
     * 可信反代的地址段：直连地址落在这里面才采信转发头。默认回环 + 私有网段 + 100.64.0.0/10
     * （运营商级 NAT 段，Tailscale 用它——msi 的 HTTPS 反代正是经 Tailscale 从 100.100.100.10 转过来，
     * 2026-10-17 上线验收时发现不加这段，记下的 IP 全是反代、限频变成全站共用一个额度）。
     * 环境变量 {@code OFFICIA_TRUSTED_PROXIES} 可整体替换，逗号分隔的 IPv4 CIDR。
     */
    private static final List<int[]> TRUSTED_PROXIES = trustedProxies(System.getenv("OFFICIA_TRUSTED_PROXIES"));

    static List<int[]> trustedProxies(String cfg) {
        String spec = blank(cfg) ? "127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10" : cfg;
        List<int[]> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int slash = p.indexOf('/');
            int bits = slash < 0 ? 32 : Integer.parseInt(p.substring(slash + 1).trim());
            Integer addr = ipv4(slash < 0 ? p : p.substring(0, slash).trim());
            if (addr != null && bits >= 0 && bits <= 32) {
                out.add(new int[]{addr, bits});
            }
        }
        return out;
    }

    /** 直连地址是否落在可信反代段里（IPv6 只认回环）。 */
    static boolean trusted(java.net.InetAddress peer, List<int[]> proxies) {
        if (peer.isLoopbackAddress()) {
            return true;
        }
        Integer a = ipv4(peer.getHostAddress());
        if (a == null) {
            return false;
        }
        for (int[] net : proxies) {
            int mask = net[1] == 0 ? 0 : -1 << (32 - net[1]);
            if ((a & mask) == (net[0] & mask)) {
                return true;
            }
        }
        return false;
    }

    /** 点分 IPv4 → int；不是 IPv4 返回 null。 */
    private static Integer ipv4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) {
            return null;
        }
        int v = 0;
        for (String x : p) {
            int n;
            try {
                n = Integer.parseInt(x);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 0 || n > 255) {
                return null;
            }
            v = (v << 8) | n;
        }
        return v;
    }

    /**
     * 客户端 IP。线上经 HTTPS 反代转发到本机端口，直连地址是反代自己，真实来源在
     * {@code X-Forwarded-For} / {@code X-Real-IP} 里——但这两个头谁都能伪造，
     * 所以只有直连地址落在可信反代段（{@link #TRUSTED_PROXIES}）里时才采信。
     */
    static String clientIp(HttpExchange ex) {
        java.net.InetAddress peer = ex.getRemoteAddress().getAddress();
        String direct = peer.getHostAddress();
        if (trusted(peer, TRUSTED_PROXIES)) {
            String fwd = header(ex, "X-Forwarded-For");
            if (!blank(fwd)) {
                return fwd.split(",")[0].trim();      // 最左边是最初的客户端
            }
            String real = header(ex, "X-Real-IP");
            if (!blank(real)) {
                return real.trim();
            }
        }
        return direct;
    }

    /** 请求是否经 HTTPS 到达（反代把原始协议放在 X-Forwarded-Proto 里）。决定 Cookie 要不要带 Secure。 */
    static boolean viaHttps(HttpExchange ex) {
        return "https".equalsIgnoreCase(header(ex, "X-Forwarded-Proto"));
    }

    /** 读一个小请求体（JSON 表单等），超过 max 字节判为请求过大。 */
    static String smallBody(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > max) {
                    drain(in, out.size() + n);
                    throw new PayloadTooLargeException("请求内容过长（上限 " + max / 1024 + " KB）");
                }
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 请求体超限（映射为 HTTP 413）。 */
    static final class PayloadTooLargeException extends RuntimeException {
        PayloadTooLargeException(String message) {
            super(message);
        }
    }

    /** 返回 JSON（UTF-8）。 */
    static void json(HttpExchange ex, String json) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回错误 JSON（前端统一展示 message）。 */
    static void error(HttpExchange ex, int code, String message) throws IOException {
        send(ex, code, "application/json; charset=utf-8",
            Json.obj().put("error", true).put("message", message).end().getBytes(StandardCharsets.UTF_8));
    }

    /** 返回任意字节（预览/下载）。 */
    static void send(HttpExchange ex, int code, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }

    /** 按扩展名推断 MIME（供上传件与静态资源共用）；未知返回二进制流。 */
    static String mimeOf(String name) {
        String n = name.toLowerCase();
        int dot = n.lastIndexOf('.');
        if (dot >= 0) {
            String mime = MIME.get(n.substring(dot));
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }
}
