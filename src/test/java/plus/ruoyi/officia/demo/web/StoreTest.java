package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Blob 仓测试：寻址、元信息、以及<b>有界性</b>（LRU 淘汰）。
 *
 * <p>有界性是这个类存在的关键约束——不淘汰就会把每次上传与转换结果全留在内存里，
 * 长时间使用必然 OOM，故必须有测试锁住。</p>
 *
 * @author officia-demo
 */
@DisplayName("Store Blob 仓测试")
class StoreTest {

    @BeforeEach
    void clean() {
        Store.clear();
    }

    @Test
    @DisplayName("存取往返：id 可寻址，字节与元信息一致")
    void putAndGet() {
        byte[] data = "hello".getBytes();
        Store.Blob b = Store.put("a.txt", "text/plain", data);
        assertThat(b.id()).isNotBlank();
        assertThat(Store.get(b.id())).isSameAs(b);
        assertThat(Store.bytes(b.id())).isEqualTo(data);
        assertThat(b.name()).isEqualTo("a.txt");
        assertThat(b.mime()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("找不到 id → 抛 IllegalArgumentException（上层转 400）")
    void missingId() {
        assertThatThrownBy(() -> Store.bytes("nope"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("找不到文件");
    }

    @Test
    @DisplayName("元信息 JSON 含 size / pages / ms")
    void toJson() {
        Store.Blob b = Store.put("x.pdf", "application/pdf", new byte[10]).withPages(3).withMs(42);
        String json = b.toJson().end();
        assertThat(json).contains("\"size\":10").contains("\"pages\":3").contains("\"ms\":42");
    }

    @Test
    @DisplayName("超过条目上限 → 按 LRU 淘汰最久未访问者（防无界增长 OOM）")
    void evictsByLru() {
        // 存 210 条（上限 200）
        String firstId = Store.put("first.bin", "application/octet-stream", new byte[1]).id();
        for (int i = 0; i < 209; i++) {
            Store.put("f" + i + ".bin", "application/octet-stream", new byte[1]);
        }
        assertThat(Store.size()).as("条目数不超上限").isLessThanOrEqualTo(200);
        assertThat(Store.get(firstId)).as("最早的条目已被淘汰").isNull();

        List<String> ids = Store.ids();
        assertThat(Store.get(ids.get(ids.size() - 1))).as("最新条目仍在").isNotNull();
    }

    @Test
    @DisplayName("访问会刷新 LRU 序：被读过的条目更晚被淘汰")
    void accessRefreshesLru() {
        String keep = Store.put("keep.bin", "application/octet-stream", new byte[1]).id();
        for (int i = 0; i < 150; i++) {
            Store.put("f" + i + ".bin", "application/octet-stream", new byte[1]);
        }
        Store.get(keep);                       // 刷新为最近访问
        for (int i = 0; i < 60; i++) {         // 再塞满触发淘汰
            Store.put("g" + i + ".bin", "application/octet-stream", new byte[1]);
        }
        assertThat(Store.get(keep)).as("最近访问过的条目不该被先淘汰").isNotNull();
    }

    @Test
    @DisplayName("bytesUsed 随存入累加、随淘汰回落")
    void tracksBytes() {
        assertThat(Store.bytesUsed()).isZero();
        Store.put("a", "application/octet-stream", new byte[1000]);
        assertThat(Store.bytesUsed()).isEqualTo(1000);
        Store.clear();
        assertThat(Store.bytesUsed()).isZero();
    }
}
