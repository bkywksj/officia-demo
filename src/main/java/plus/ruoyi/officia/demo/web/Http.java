package plus.ruoyi.officia.demo.web;

import com.sun.net.httpserver.HttpExchange;

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

    /** 读完请求体字节。 */
    static byte[] body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return in.readAllBytes();
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

    /** 按扩展名推断 MIME（供上传件与静态资源共用）。 */
    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".csv")) return "text/csv; charset=utf-8";
        if (n.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (n.endsWith(".eml")) return "message/rfc822";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".ttf") || n.endsWith(".ttc")) return "font/ttf";
        return "application/octet-stream";
    }
}
