package plus.ruoyi.officia.demo.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 Blob 仓：上传的源文件与生成的结果统一按 id 寻址。
 *
 * <p>测试台是本机临时工具，不落盘、不入库——重启即清空。前端上传得到 id，
 * 后续所有操作只传 id（天然支持"多文件合并"这类多输入场景），结果同样返回 id 供预览/下载。</p>
 *
 * @author officia-demo
 */
public final class Store {

    /** 一条 Blob：字节 + 展示名 + MIME + 附带指标。 */
    public static final class Blob {
        public final String id;
        public final String name;
        public final String mime;
        public final byte[] data;
        /** 生成耗时（毫秒，上传件为 0）。 */
        public long ms;
        /** PDF 页数（非 PDF 为 -1）。 */
        public int pages = -1;

        Blob(String id, String name, String mime, byte[] data) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.data = data;
        }

        /** 转成前端要的 JSON 元信息（不含字节）。 */
        public Json toJson() {
            Json j = Json.obj().put("id", id).put("name", name).put("mime", mime).put("size", data.length);
            if (pages >= 0) {
                j.put("pages", pages);
            }
            if (ms > 0) {
                j.put("ms", ms);
            }
            return j;
        }
    }

    private static final Map<String, Blob> BLOBS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong(1);

    private Store() {
    }

    /** 存入一条 Blob，返回它（含新分配的 id）。 */
    public static Blob put(String name, String mime, byte[] data) {
        String id = "b" + SEQ.getAndIncrement();
        Blob b = new Blob(id, name, mime, data);
        BLOBS.put(id, b);
        return b;
    }

    /** 按 id 取；不存在返回 null。 */
    public static Blob get(String id) {
        return id == null ? null : BLOBS.get(id);
    }

    /** 按 id 取字节；不存在抛出可读异常（交由上层转 400）。 */
    public static byte[] bytes(String id) {
        Blob b = get(id);
        if (b == null) {
            throw new IllegalArgumentException("找不到文件 id=" + id + "（可能服务已重启，请重新上传）");
        }
        return b.data;
    }

    /** 当前条目数（供健康检查展示）。 */
    public static int size() {
        return BLOBS.size();
    }
}
