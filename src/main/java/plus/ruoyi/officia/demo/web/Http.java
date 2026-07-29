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

    /** 单次请求体上限（100 MB）：防止误传超大文件把测试台 JVM 撑爆。 */
    static final int MAX_BODY_BYTES = 100 * 1024 * 1024;

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
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BODY_BYTES) {
                    throw new PayloadTooLargeException(
                        "请求体超过上限 " + (MAX_BODY_BYTES / 1024 / 1024) + " MB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
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
