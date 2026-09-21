package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在线编辑「另存为」的下载名清洗。
 *
 * <h2>🔴 为什么导出名要跟着源文件走</h2>
 *
 * <p>此前服务端写死 {@code editor-saved.docx} / {@code editor-saved.xlsx}：
 * 同一个人导出三份不同的文档，下载目录里就是 editor-saved.docx、
 * editor-saved (1).docx、editor-saved (2).docx，根本分不清哪个是哪个。
 * 现在由前端按「源基名 + {@code _导出} + 目标扩展名」算好传过来。</p>
 *
 * <h2>这是对外端点的入参，一律当不可信处理</h2>
 *
 * <p>名字最终进 {@code Content-Disposition} 响应头。不清洗的话，
 * 路径分隔符能做目录穿越、换行能往响应头里注入新字段。
 * 中文不动——下载头走 RFC 5987 编码（{@code DemoServer.serveResult}）。</p>
 *
 * @author officia-demo
 */
@DisplayName("导出下载名")
class ExportNameTest {

    private static final String FALLBACK = "editor-saved.docx";

    @Test
    @DisplayName("🔴 正常名字原样用——中文与空格都不动")
    void normal_name_passes_through() {
        assertThat(ApiRoutes.downloadName("合同_导出.docx", FALLBACK)).isEqualTo("合同_导出.docx");
        assertThat(ApiRoutes.downloadName("2026 年报_导出.pdf", FALLBACK))
            .isEqualTo("2026 年报_导出.pdf");
    }

    @Test
    @DisplayName("没传 / 空白一律回退到固定名")
    void blank_falls_back() {
        assertThat(ApiRoutes.downloadName(null, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(ApiRoutes.downloadName("", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(ApiRoutes.downloadName("   ", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("🔴 路径分隔符剔掉——不能让入参做目录穿越")
    void path_separators_are_stripped() {
        assertThat(ApiRoutes.downloadName("../../etc/passwd", FALLBACK)).isEqualTo("....etcpasswd");
        assertThat(ApiRoutes.downloadName("a\\b\\c.docx", FALLBACK)).isEqualTo("abc.docx");
    }

    @Test
    @DisplayName("🔴 换行与控制字符剔掉——它要进 Content-Disposition 响应头")
    void control_chars_are_stripped() {
        String injected = "x.docx\r\nX-Evil: 1";

        String clean = ApiRoutes.downloadName(injected, FALLBACK);

        assertThat(clean).doesNotContain("\r").doesNotContain("\n");
        assertThat(clean).isEqualTo("x.docxX-Evil: 1");
    }

    @Test
    @DisplayName("双引号剔掉——它是响应头里的定界符")
    void quotes_are_stripped() {
        assertThat(ApiRoutes.downloadName("a\"b.docx", FALLBACK)).isEqualTo("ab.docx");
    }

    @Test
    @DisplayName("清洗后为空、或只剩 . / .. 时回退")
    void degenerate_names_fall_back() {
        assertThat(ApiRoutes.downloadName("///", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(ApiRoutes.downloadName(".", FALLBACK)).isEqualTo(FALLBACK);
        assertThat(ApiRoutes.downloadName("..", FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    @DisplayName("超长名字截断——不让一个长入参撑爆响应头")
    void overlong_name_is_truncated() {
        String name = "长".repeat(500) + ".docx";

        assertThat(ApiRoutes.downloadName(name, FALLBACK)).hasSize(120);
    }
}
