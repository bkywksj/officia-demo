package plus.ruoyi.officia.demo.web;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存 Blob 仓：上传的源文件与生成的结果统一按 id 寻址。
 *
 * <p>测试台是本机临时工具，不落盘、不入库——重启即清空。前端上传得到 id，
 * 后续所有操作只传 id（天然支持"多文件合并"这类多输入场景），结果同样返回 id 供预览/下载。</p>
 *
 * <p><b>有界</b>：条目数与总字节都设上限，超限按 <b>LRU</b>（最久未访问优先）逐出。
 * 否则长时间使用会把上传件与每次转换结果全部常驻内存，最终 OOM。</p>
 *
 * @author officia-demo
 */
public final class Store {

    /** 最多保留的条目数。 */
    private static final int MAX_ENTRIES = 200;
    /**
     * 最多占用的总字节：按 JVM 最大堆的 1/4 取，至少 512 MB。
     *
     * <p>写死 512 MB 会在大文件场景立刻打满——一份 150 MB 的设计型 PPTX 加上转换产出与后续 PDF 操作的
     * 中间产物就逼近上限，导致刚上传的源文件被 LRU 逐出、下一步操作报「找不到文件 id」。跟着堆走更合理。</p>
     */
    private static final long MAX_BYTES = Math.max(512L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);

    /** 一条 Blob：字节 + 展示名 + MIME + 附带指标。 */
    public static final class Blob {
        private final String id;
        private final String name;
        private final String mime;
        private final byte[] data;
        /** 生成耗时（毫秒，上传件为 0）。 */
        private long ms;
        /** PDF 页数（非 PDF 为 -1）。 */
        private int pages = -1;

        Blob(String id, String name, String mime, byte[] data) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.data = data;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String mime() {
            return mime;
        }

        public byte[] data() {
            return data;
        }

        public int pages() {
            return pages;
        }

        /** 记录生成耗时（毫秒）。 */
        public Blob withMs(long millis) {
            this.ms = millis;
            return this;
        }

        /** 记录 PDF 页数。 */
        public Blob withPages(int pageCount) {
            this.pages = pageCount;
            return this;
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

    /** accessOrder=true 的 LinkedHashMap 即 LRU 访问序；全部访问都在同步块内，故用非并发容器。 */
    private static final Map<String, Blob> BLOBS = new LinkedHashMap<>(64, 0.75f, true);
    private static final AtomicLong SEQ = new AtomicLong(1);
    private static long totalBytes;

    private Store() {
    }

    /** 存入一条 Blob，返回它（含新分配的 id）；必要时按 LRU 逐出旧条目。 */
    public static synchronized Blob put(String name, String mime, byte[] data) {
        String id = "b" + SEQ.getAndIncrement();
        Blob b = new Blob(id, name, mime, data);
        BLOBS.put(id, b);
        totalBytes += data.length;
        evictIfNeeded(id);
        return b;
    }

    /** 按 id 取（同时刷新 LRU 访问序）；不存在返回 null。 */
    public static synchronized Blob get(String id) {
        return id == null ? null : BLOBS.get(id);
    }

    /** 按 id 取字节；不存在抛出可读异常（上层转 400）。 */
    public static byte[] bytes(String id) {
        Blob b = get(id);
        if (b == null) {
            throw new IllegalArgumentException("找不到文件 id=" + id + "（可能已被清理或服务重启，请重新上传）");
        }
        return b.data;
    }

    /** 当前条目数（供健康检查展示）。 */
    public static synchronized int size() {
        return BLOBS.size();
    }

    /** 当前占用字节（供健康检查展示）。 */
    public static synchronized long bytesUsed() {
        return totalBytes;
    }

    /** 清空（测试与"重置"场景用）。 */
    public static synchronized void clear() {
        BLOBS.clear();
        totalBytes = 0;
    }

    /**
     * 超出条目数或总字节上限时，按 LRU 逐出最久未访问的条目。
     *
     * @param keepId 本次刚存入的 id，永不逐出——否则单个大文件就能把自己挤掉，
     *               调用方拿着刚返回的 id 下一步就报「找不到文件」
     */
    private static void evictIfNeeded(String keepId) {
        Iterator<Map.Entry<String, Blob>> it = BLOBS.entrySet().iterator();
        while (it.hasNext() && (BLOBS.size() > MAX_ENTRIES || totalBytes > MAX_BYTES)) {
            Map.Entry<String, Blob> eldest = it.next();
            if (eldest.getKey().equals(keepId)) {
                continue;
            }
            totalBytes -= eldest.getValue().data.length;
            it.remove();
        }
    }

    /** 现存条目的 id 列表（按 LRU 序，测试用）。 */
    static synchronized List<String> ids() {
        return new ArrayList<>(BLOBS.keySet());
    }
}
