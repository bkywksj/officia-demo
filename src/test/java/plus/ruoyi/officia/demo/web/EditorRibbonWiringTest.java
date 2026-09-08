package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两个编辑器面板必须用 officia-editor 的真功能区，而不是自己手写一小排按钮。
 *
 * <h2>为什么要机检</h2>
 *
 * <p>测试台的工具栏一度是手写的十个按钮——能用，但它对客户说的是一句错话：
 * 「Officia.Editor 就这么点能力」。换成 {@code OfficiaEditor.mountRibbon} 之后，
 * 界面上的 145 个控件与三态由 officia 仓的能力清单机检守着，
 * 谁改了能力边界，这边跟着变，不需要 demo 再维护第二份名单。</p>
 *
 * <p>但这条接线**很容易被无声地改回去**：以后有人为了"临时加个按钮"在 HTML 里
 * 写死一个 onclick，或者把 mountRibbon 那几行注释掉，页面照样能跑、单测照样全绿，
 * 只有客户看到的能力清单悄悄退回旧版本。本测试就是钉住这条接线。</p>
 *
 * <p>只断言"接线在不在"，不断言控件数与三态——那是 officia 仓
 * {@code ToolbarCapabilityTest} 与前端 {@code ribbon.test.ts} 的职责，
 * 在这里再抄一份名单，就又制造了一处会漂的真相源。</p>
 *
 * @author officia-demo
 */
@DisplayName("编辑器面板的功能区接线")
class EditorRibbonWiringTest {

    private static String page() throws IOException {
        try (InputStream in = EditorRibbonWiringTest.class.getClassLoader()
                .getResourceAsStream("web/index.html")) {
            assertThat(in).as("取不到测试台页面 web/index.html").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("两个面板都挂真功能区，并各自接上命令派发")
    void both_panels_mount_the_real_ribbon() throws IOException {
        String html = page();
        for (String symbol : new String[]{
                "OfficiaEditor.mountRibbon",          // 功能区本体
                "surface:'words'", "surface:'cells'", // 两个界面各挂一次
                "OfficiaEditor.runWordsCommand",      // Words 命令派发
                "OfficiaEditor.runCellsCommand",      // Cells 命令派发
                "id=\"edRibbon\"", "id=\"exRibbon\"", // 承载容器
        }) {
            assertThat(html).as("测试台缺少 %s——功能区接线被改回手写按钮了？", symbol).contains(symbol);
        }
    }

    @Test
    @DisplayName("不再有手写的编辑命令按钮——那是被功能区取代掉的旧接线")
    void no_hand_written_command_buttons() throws IOException {
        String html = page();
        for (String gone : new String[]{"edCmd(", "edAlign(", "exCmd("}) {
            assertThat(html)
                    .as("%s 又出现了：手写按钮与功能区并存，两份能力清单必然漂", gone)
                    .doesNotContain(gone);
        }
    }

    @Test
    @DisplayName("库说「归宿主管」的控件，测试台真的接了")
    void host_capabilities_are_actually_handled() throws IOException {
        // officia-editor 把这类控件列在 WORDS_HOST_CAPS / CELLS_HOST_CAPS 里：
        // 不是做不了，而是不该在库里做（视图开关、要服务端往返、要弹文件框）。
        // 库返回 false 之后若测试台也不管，用户看到的就是「点了没反应」。
        //
        // 名单在这里重复了一份，是有意的：它是**测试台对自己的承诺**，
        // 库那边加了新的 HOST_CAP 而这边没跟上时，人工对一次即可——
        // 从压缩后的产物里刮名单反而更脆（第一版正则连 UNWIRED 表一起捞了进来）。
        String html = page();
        List<String> missing = new ArrayList<>();
        for (String cap : new String[]{
                "缩放", "页面视图", "图片",                       // Words 侧
                "立即重算", "错误检查", "网格线", "行列标题", "编辑栏", "普通", "导入 CSV",
        }) {
            if (!html.contains("e.cap==='" + cap + "'")) {
                missing.add(cap);
            }
        }
        assertThat(missing)
                .as("这些控件库明说交给宿主，测试台却没有对应分支，点下去会没反应")
                .isEmpty();
    }

    @Test
    @DisplayName("库不接的控件也要给出原因，而不是静默吞掉一次点击")
    void unwired_capabilities_explain_themselves() throws IOException {
        String html = page();
        assertThat(html)
                .as("Words 侧没读 WORDS_UNWIRED，用户点了只会看到一句没信息量的提示")
                .contains("OfficiaEditor.WORDS_UNWIRED");
        assertThat(html)
                .as("Cells 侧没读 CELLS_UNWIRED")
                .contains("OfficiaEditor.CELLS_UNWIRED");
    }

    /**
     * 对齐组的激活态必须回灌，否则原型里静态高亮的「两端对齐」永远清不掉。
     *
     * <p>这不是「少个高亮」这么轻。功能区和右侧属性面板都显示当前对齐，
     * 前者不刷新时会**停在原型 HTML 写死的那个高亮上**——用户把段落设成居中，
     * 右侧面板写「中」、工具栏亮「两端」，两处读数当场打架，比不显示还糟。
     *
     * <p>2026-09-08 实测确认过这个失效：{@code edOnChange} 当时只回灌了
     * 加粗/斜体/下划线/删除线四个字符样式，对齐组不在名单里。</p>
     */
    @Test
    @DisplayName("对齐组的激活态跟着光标回灌，且与属性面板同一套判定")
    void alignment_toggles_are_pushed_back() throws IOException {
        String html = page();
        assertThat(html)
                .as("没从 st.paragraph.align 取当前对齐，功能区高亮会停在原型写死的那个上")
                .contains("st.paragraph&&st.paragraph.align");
        List<String> missing = new ArrayList<>();
        for (String pair : new String[]{"'左对齐','LEFT'", "'居中','CENTER'",
                "'右对齐','RIGHT'", "'两端对齐','JUSTIFY'"}) {
            if (!html.replace(" ", "").contains("[" + pair.replace(" ", "") + "]")) {
                missing.add(pair);
            }
        }
        assertThat(missing)
                .as("对齐组回灌漏了这些，对应按钮的高亮会与右侧属性面板不一致")
                .isEmpty();
        assertThat(html)
                .as("功能区挂完没主动回灌一次当前状态，首屏就会停在原型写死的高亮上——"
                        + "只在后续变化里回灌治不了第一帧")
                .contains("if(edView)edOnChange(edView.state())");
    }
}
