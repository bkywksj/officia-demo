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

    /**
     * 含中文字形的候选，<b>必须排在拉丁兜底之前</b>。
     *
     * <p>顺序不是随意的：拉丁字体（DejaVu/Arial）在很多 Linux 与容器镜像里都存在，
     * 一旦排在前面就会先被命中，结果是"探测到字体了"但中文照样是方块——
     * 比彻底没字体更难排查。</p>
     */
    private static final String[] CJK_CANDIDATES = {
        // Windows：优先纯 TTF（.ttc 集合体解析支持有限）
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/msyh.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        "C:/Windows/Fonts/simkai.ttf",
        // Linux：Debian/Ubuntu 的 fonts-noto-cjk（新旧两种文件名）与文泉驿
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-VF.otf.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf",
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        // macOS
        "/System/Library/Fonts/PingFang.ttc"
    };

    /** 无中文字形，仅在完全找不到中文字体时兜底（ASCII 水印总比不画强）。 */
    private static final String[] ASCII_FALLBACK = {
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/Library/Fonts/Arial.ttf"
    };

    /** 只探测一次；null 表示未找到。 */
    private static volatile byte[] cached;
    private static volatile boolean probed;
    /** 命中的是否为含中文字形的字体（决定启动横幅的措辞，避免给出"中文可用"的假承诺）。 */
    private static volatile boolean cjk;

    private Fonts() {
    }

    /** 取一份可用的 TTF 字节（优先中文）；无则 null。 */
    static synchronized byte[] systemCjk() {
        if (probed) {
            return cached;
        }
        probed = true;
        if (load(CJK_CANDIDATES)) {
            cjk = true;
            return cached;
        }
        load(ASCII_FALLBACK);
        return cached;
    }

    /** 依次尝试候选路径，读到第一个可用的即写入缓存并返回 true。 */
    private static boolean load(String[] candidates) {
        for (String p : candidates) {
            File f = new File(p);
            if (f.isFile() && f.length() > 0) {
                try {
                    cached = Files.readAllBytes(f.toPath());
                    return true;
                } catch (Exception ignore) {
                    // 读不了就试下一个候选
                }
            }
        }
        return false;
    }

    /** 供启动日志展示是否探测到可用字体。 */
    static boolean available() {
        return systemCjk() != null;
    }

    /** 探测到的字体是否含中文字形；false 时中文水印会是方块。 */
    static boolean hasCjk() {
        systemCjk();
        return cjk;
    }
}
