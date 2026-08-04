package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
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

    /** 字节数转易读文本（错误消息与启动横幅共用）：不足 1 GB 用 MB，否则用 GB。 */
    static String humanSize(long bytes) {
        double mib = bytes / 1024.0 / 1024.0;
        return mib < 1024 ? String.format("%.1f MB", mib) : String.format("%.1f GB", mib / 1024.0);
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
