package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载接线：点「存为 docx」就该下载，链接点下去也该下载。
 *
 * <h2>🔴 两条都是实测复现出来的，而且都不报错</h2>
 *
 * <ul>
 *   <li><b>游离的 {@code <a>}</b>：{@code fire()} 建了个 anchor 就直接 {@code click()}，
 *       没挂进 DOM。游离 anchor 的 click 在 Firefox 等浏览器上<b>不触发下载</b>，
 *       只有 Chrome 容忍——于是「存为 docx」在一部分浏览器上点完什么也没发生，
 *       控制台干干净净。</li>
 *   <li><b>下载链接没有 {@code download} 属性</b>：靠导航 + 服务端
 *       {@code Content-Disposition} 下载。嵌入式浏览器 / webview 拦掉导航型下载时
 *       静默失败；而且下载名要靠浏览器解析响应头，不如直接写进属性——
 *       服务端明明已经算好名字了（「源名_导出.docx」）。</li>
 * </ul>
 *
 * <h2>为什么值得机检</h2>
 *
 * <p>这类错误<b>写的时候看不出来</b>：代码读着完全正常，在 Chrome 里点也正常，
 * 换个浏览器才失灵。靠人记得「anchor 要挂进 DOM」不现实，钉在这里。</p>
 *
 * @author officia-demo
 */
@DisplayName("下载接线")
class DownloadWiringTest {

    private static String page() throws IOException {
        try (InputStream in = DownloadWiringTest.class.getClassLoader()
                .getResourceAsStream("web/index.html")) {
            assertThat(in).as("取不到测试台页面 web/index.html").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("🔴 fire() 的 anchor 必须挂进 DOM 再 click——游离的在 Firefox 上不触发下载")
    void fire_must_attach_the_anchor() throws IOException {
        String html = page();
        int at = html.indexOf("function fire(");
        assertThat(at).as("找不到 fire()").isGreaterThan(0);
        String body = html.substring(at, html.indexOf("\n}", at));

        assertThat(body).as("fire() 没把 anchor 挂进 DOM").contains("appendChild");
        assertThat(body.indexOf("appendChild"))
            .as("appendChild 必须在 click() 之前")
            .isLessThan(body.indexOf(".click()"));
        assertThat(body).as("用完要摘掉，别在 body 里越堆越多").contains("remove()");
    }

    @Test
    @DisplayName("🔴 每个产物下载链接都要带 download 属性——只靠响应头会被静默拦掉")
    void every_result_link_carries_download_attribute() throws IOException {
        String html = page();
        // 形如 '<a href="/api/result/'+j.id+'?download=1" ... >下载</a>
        Matcher m = Pattern.compile("<a href=\"/api/result/[^\"]*\\?download=1\"([^>]*)>")
            .matcher(html);
        List<String> missing = new ArrayList<>();
        int total = 0;
        while (m.find()) {
            total++;
            if (!m.group(1).contains("download=")) {
                missing.add(m.group(0));
            }
        }
        assertThat(total).as("一处下载链接都没找到，正则该改了").isGreaterThan(4);
        assertThat(missing).as("这些下载链接没有 download 属性").isEmpty();
    }

    @Test
    @DisplayName("🔴 导出不留回执面板——点「另存为」就是下载，没有第二步")
    void export_leaves_no_receipt_panel() throws IOException {
        // 那块「✓ 文件名 · 体积 · [重新下载]」唯一的用处是让人再点一次下载，
        // 而那件事按钮本身已经做了。删掉它，页数与耗时折进 toast
        String html = page();

        assertThat(html).as("edOut 回执面板该删掉了").doesNotContain("edOut");
        assertThat(html).as("exOut 回执面板该删掉了").doesNotContain("exOut");
        // ⚠️ 判据扫【标签】不扫文字：注释里正在解释「为什么删掉重新下载」，
        // 扫全文会被那句注释误伤。本仓为同一类原因红过三次
        assertThat(html).as("「重新下载」按钮该没了").doesNotContain(">重新下载</a>");
    }

    @Test
    @DisplayName("页数与耗时折进 toast——测试台的读数不能跟着面板一起删掉")
    void export_toast_carries_the_readings() throws IOException {
        String html = page();
        int at = html.indexOf("function exportToast(");
        assertThat(at).as("找不到 exportToast()").isGreaterThan(0);
        String body = html.substring(at, html.indexOf("\n}", at));

        assertThat(body).as("没带文件名").contains("j.name");
        assertThat(body).as("没带体积").contains("j.size");
        assertThat(body).as("没带页数").contains("j.pages");
        assertThat(body).as("没带服务端耗时").contains("j.ms");
        assertThat(body).as("没带往返耗时").contains("roundTripMs");
    }

    @Test
    @DisplayName("两个编辑器都在导出后立刻触发下载，不用再点一次")
    void both_editors_fire_on_save() throws IOException {
        String html = page();
        int fires = 0;
        Matcher m = Pattern.compile("\\bfire\\(j\\.id, j\\.name\\)").matcher(html);
        while (m.find()) {
            fires++;
        }
        assertThat(fires).as("Words 与 Cells 各该有一处导出即下载").isEqualTo(2);
    }
}
