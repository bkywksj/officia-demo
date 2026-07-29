package plus.ruoyi.officia.demo.web;

import java.io.File;
import java.nio.file.Files;

/**
 * 中文字体探测：PDF 水印/页码要写中文必须给 TTF（officia 门面的 fontTtf 入参）。
 *
 * <p>测试台按常见路径找一个系统 TTF（Windows/Linux/macOS 各试几个），找到就缓存复用；
 * 找不到返回 {@code null}，调用方退回不带字体的重载（仅 ASCII 可正常显示）。
 * 用户也可在界面上传自己的 TTF，优先级高于本探测。</p>
 *
 * @author officia-demo
 */
final class Fonts {

    /** 只探测一次；null 表示未找到。 */
    private static volatile byte[] cached;
    private static volatile boolean probed;

    private Fonts() {
    }

    /** 取一份可用的中文 TTF 字节；无则 null。 */
    static synchronized byte[] systemCjk() {
        if (probed) {
            return cached;
        }
        probed = true;
        String[] candidates = {
            // Windows：优先纯 TTF（.ttc 集合体解析支持有限）
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyh.ttf",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simkai.ttf",
            "C:/Windows/Fonts/arial.ttf",
            // Linux
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            // macOS
            "/System/Library/Fonts/PingFang.ttc",
            "/Library/Fonts/Arial.ttf"
        };
        for (String p : candidates) {
            File f = new File(p);
            if (f.isFile() && f.length() > 0) {
                try {
                    cached = Files.readAllBytes(f.toPath());
                    return cached;
                } catch (Exception ignore) {
                    // 读不了就试下一个候选
                }
            }
        }
        return null;
    }

    /** 供启动日志展示是否探测到中文字体。 */
    static boolean available() {
        return systemCjk() != null;
    }
}
