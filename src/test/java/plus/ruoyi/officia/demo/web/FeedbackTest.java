package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 反馈存储：目录 + meta.json 的读写、类型白名单、各项限额、路径安全、过期清理。
 *
 * @author officia-demo
 */
@DisplayName("Feedback 反馈存储")
class FeedbackTest {

    @TempDir
    Path root;

    private Feedback fb;

    @BeforeEach
    void setUp() throws Exception {
        // 单文件 1 KB、每小时 3 次、总量 5000 字节（已用空间也算 meta.json，每条几百字节，留出余量）
        fb = new Feedback(root, 1024, 3, 5000, 90);
    }

    private static Map<String, Object> meta(String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capability", "Word → PDF / 图片");
        m.put("note", note);
        return m;
    }

    @Test
    @DisplayName("存一条：目录名合法，原文件与结果按固定名落盘，meta 记原始文件名与摘要")
    void createsAnItem() throws Exception {
        byte[] src = "docx-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] out = "%PDF-1.7".getBytes(StandardCharsets.UTF_8);
        String id = fb.create("报告 v2.docx", src, "报告 v2.pdf", out, meta("表格边框丢了"));

        assertThat(id).matches("\\d{8}-\\d{6}-[0-9a-f]{6}");
        Path dir = root.resolve("items").resolve(id);
        assertThat(dir.resolve("source.docx")).hasBinaryContent(src);
        assertThat(dir.resolve("output.pdf")).hasBinaryContent(out);
        Map<String, Object> m = fb.get(id);
        assertThat(m.get("status")).isEqualTo("new");
        assertThat(m.get("note")).isEqualTo("表格边框丢了");
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) m.get("source");
        assertThat(s.get("name")).isEqualTo("报告 v2.docx");
        assertThat(String.valueOf(s.get("sha256"))).hasSize(64);
        assertThat(fb.file(id, "source")).isEqualTo(dir.resolve("source.docx"));
        assertThat(fb.list()).extracting(x -> x.get("id")).containsExactly(id);
    }

    @Test
    @DisplayName("文件类型白名单、空文件、单文件上限")
    void rejectsDisallowedOrOversizedFiles() {
        assertThatThrownBy(() -> fb.create("a.exe", new byte[10], null, null, meta("x")))
            .hasMessageContaining(".exe");
        assertThatThrownBy(() -> fb.create("noext", new byte[10], null, null, meta("x")))
            .hasMessageContaining("无扩展名");
        assertThatThrownBy(() -> fb.create("a.docx", new byte[0], null, null, meta("x")))
            .hasMessageContaining("空");
        assertThatThrownBy(() -> fb.create("a.docx", new byte[2048], null, null, meta("x")))
            .hasMessageContaining("超过反馈上限");
    }

    @Test
    @DisplayName("结果文件超限时只收原文件，不整条拒收")
    void oversizedOutputIsDroppedNotRejected() throws Exception {
        String id = fb.create("a.docx", new byte[100], "a.pdf", new byte[2048], meta("x"));
        assertThat(fb.get(id).get("output")).isNull();
        assertThat(fb.file(id, "output")).isNull();
    }

    @Test
    @DisplayName("总量上限：已用 + 本次超过就拒收")
    void totalQuota() throws Exception {
        fb.create("a.docx", new byte[1000], null, null, meta("1"));
        fb.create("b.docx", new byte[1000], null, null, meta("2"));
        fb.create("c.docx", new byte[1000], null, null, meta("3"));
        assertThatThrownBy(() -> fb.create("d.docx", new byte[1000], null, null, meta("4")))
            .hasMessageContaining("空间已满");
    }

    @Test
    @DisplayName("按 IP 限频：一小时内超过次数拒绝，过了一小时恢复，不同 IP 互不影响")
    void rateLimitPerIp() {
        long t = 1_000_000_000L;
        assertThat(fb.allow("1.1.1.1", t)).isTrue();
        assertThat(fb.allow("1.1.1.1", t + 1)).isTrue();
        assertThat(fb.allow("1.1.1.1", t + 2)).isTrue();
        assertThat(fb.allow("1.1.1.1", t + 3)).isFalse();
        assertThat(fb.allow("2.2.2.2", t + 3)).isTrue();
        assertThat(fb.allow("1.1.1.1", t + 3600_001L)).isTrue();
    }

    @Test
    @DisplayName("非法 id（含 ../）一律当不存在，碰不到目录外的文件")
    void idsCannotEscapeTheItemsDirectory() throws Exception {
        Files.write(root.resolve("admin.json"), "secret".getBytes(StandardCharsets.UTF_8));
        for (String bad : new String[]{"../admin.json", "..", "", "20261017-153012-zzzzzz", "a/b"}) {
            assertThat(fb.get(bad)).as(bad).isNull();
            assertThat(fb.file(bad, "source")).as(bad).isNull();
            assertThat(fb.delete(bad)).as(bad).isFalse();
        }
        String id = fb.create("a.docx", new byte[10], null, null, meta("x"));
        assertThat(fb.file(id, "../admin")).as("which 只认 source / output").isNull();
    }

    @Test
    @DisplayName("改状态与处理记录；未知状态报错；删除连文件一起删")
    void updateAndDelete() throws Exception {
        String id = fb.create("a.pdf", new byte[10], null, null, meta("x"));
        Map<String, Object> m = fb.update(id, "processing", "已复现，表格线宽为 0");
        assertThat(m.get("status")).isEqualTo("processing");
        assertThat(fb.get(id).get("remark")).isEqualTo("已复现，表格线宽为 0");
        assertThatThrownBy(() -> fb.update(id, "done", null)).hasMessageContaining("未知状态");
        assertThat(fb.update("20991231-000000-000000", "fixed", null)).isNull();

        assertThat(fb.delete(id)).isTrue();
        assertThat(Files.exists(root.resolve("items").resolve(id))).isFalse();
        assertThat(fb.list()).isEmpty();
    }

    @Test
    @DisplayName("过期清理：只删「已修复 / 不处理」且超过保留期的，「新提交 / 处理中」再老也留着")
    void purgeRemovesOnlyOldClosedItems() throws Exception {
        String oldFixed = fb.create("a.pdf", new byte[10], null, null, meta("1"));
        String oldNew = fb.create("b.pdf", new byte[10], null, null, meta("2"));
        String recentFixed = fb.create("c.pdf", new byte[10], null, null, meta("3"));
        fb.update(oldFixed, "fixed", null);
        fb.update(recentFixed, "wontfix", null);
        backdate(oldFixed, "2020-01-01T00:00:00+08:00");
        backdate(oldNew, "2020-01-01T00:00:00+08:00");

        assertThat(fb.purge(System.currentTimeMillis())).isEqualTo(1);
        List<Map<String, Object>> left = fb.list();
        assertThat(left).extracting(x -> x.get("id")).containsExactlyInAnyOrder(oldNew, recentFixed);
    }

    @Test
    @DisplayName("坏掉的 meta.json 不拖垮列表")
    void brokenMetaIsSkipped() throws Exception {
        String id = fb.create("a.pdf", new byte[10], null, null, meta("x"));
        Path bad = root.resolve("items").resolve("20200101-000000-abcdef");
        Files.createDirectories(bad);
        Files.write(bad.resolve("meta.json"), "{坏的".getBytes(StandardCharsets.UTF_8));
        assertThat(fb.list()).extracting(x -> x.get("id")).containsExactly(id);
    }

    /** 把状态改变时间改到过去，模拟「很久以前结案」。 */
    private void backdate(String id, String iso) throws Exception {
        Path meta = root.resolve("items").resolve(id).resolve("meta.json");
        Map<String, Object> m = JsonReader.parseObject(new String(Files.readAllBytes(meta), StandardCharsets.UTF_8));
        m.put("statusChangedAt", iso);
        StringBuilder sb = new StringBuilder();
        Json.writeValue(sb, m);
        Files.write(meta, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
