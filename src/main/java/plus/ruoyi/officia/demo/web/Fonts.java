package plus.ruoyi.officia.demo.web;

import plus.ruoyi.officia.engine.font.TrueTypeFont;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 中文字体探测：PDF 水印/页码要写中文必须给 TTF（officia 门面的 fontTtf 入参）。
 *
 * <p>测试台按常见路径找一个系统 TTF（Windows/Linux/macOS 各试几个），找到就缓存复用；
 * 找不到返回 {@code null}，调用方退回不带字体的重载（仅 ASCII 可正常显示）。
 * 用户也可在界面上传自己的 TTF，优先级高于本探测。</p>
 *
 * <p>🔴 <b>光看路径存在不算数，必须验字体本身能用</b>——见 {@link #usableForCjk}。
 * 曾经只判"文件在不在"，结果在容器里命中了 CFF 轮廓的 Noto CJK，
 * 启动横幅报"中文水印可用"，实际调用直接抛 {@code 字体无 glyf/loca}。</p>
 *
 * @author officia-demo
 */
final class Fonts {

    /**
     * 含中文字形的候选，<b>必须排在拉丁兜底之前</b>。
     *
     * <p>顺序有两条约束：</p>
     * <ol>
     *   <li>拉丁字体（DejaVu/Arial）在很多 Linux 与容器镜像里都存在，
     *       一旦排在前面就会先被命中，结果是"探测到字体了"但中文照样是方块——
     *       比彻底没字体更难排查。</li>
     *   <li>🔴 <b>glyf 轮廓的排在 CFF 轮廓的前面</b>：officia 的 PDF 子集器只支持 TrueType
     *       {@code glyf}，遇到 CFF/OTTO（Noto CJK 的 .ttc、macOS 的 PingFang.ttc、
     *       思源黑体 .otf）会抛异常。这些路径保留在表里只是为了"万一哪天换成 glyf 版本"，
     *       实际能不能用一律由 {@link #usableForCjk} 说了算。</li>
     * </ol>
     */
    private static final String[] CJK_CANDIDATES = {
        // Windows：优先纯 TTF（.ttc 集合体解析支持有限）
        "C:/Windows/Fonts/simhei.ttf",
        "C:/Windows/Fonts/msyh.ttf",
        "C:/Windows/Fonts/simsun.ttc",
        "C:/Windows/Fonts/simkai.ttf",
        // Linux：文泉驿是 glyf 轮廓，officia 能用——必须排在 Noto 之前
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
        "/usr/share/fonts/truetype/arphic/uming.ttc",
        "/usr/share/fonts/truetype/arphic/ukai.ttc",
        // Linux：Debian/Ubuntu 的 fonts-noto-cjk 目前是 CFF 轮廓，官方包用不了；
        // 留在表里仅为兼容"自行放了 glyf 版 Noto"的环境，校验不过会自动跳到下一个
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-VF.otf.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        // macOS：PingFang 是 CFF，同样靠校验兜底
        "/System/Library/Fonts/PingFang.ttc"
    };

    /** 无中文字形，仅在完全找不到中文字体时兜底（ASCII 水印总比不画强）。 */
    private static final String[] ASCII_FALLBACK = {
        "C:/Windows/Fonts/arial.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
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
        // 运维字体目录排在硬编码候选之前：它是部署方显式配的，优先级本就该最高
        //（与 FontLoader 的口径一致——那边 OFFICIA_FONTS_DIR 也排在系统扫描之后、用户目录之前）
        if (load(operatorFontFiles(), true) || load(CJK_CANDIDATES, true)) {
            cjk = true;
            return cached;
        }
        load(ASCII_FALLBACK, false);
        return cached;
    }

    /**
     * 运维字体目录（{@code -Dofficia.fonts.dir} / 环境变量 {@code OFFICIA_FONTS_DIR}）里的字体文件。
     *
     * <p><b>为什么必须认它</b>：容器里的中文字体常常既不在系统路径、也不随镜像装，而是
     * <b>挂载</b>进来再用这个变量指过去（msi 上就是 {@code /opt/officia/fonts}）。
     * 只探 {@link #CJK_CANDIDATES} 那张硬编码表会漏掉它们，于是启动横幅报
     * 「系统无中文字体，走内置兜底」——而实际渲染早已用上挂载的字体，措辞与事实不符、误导排查。</p>
     *
     * <p>解析规则刻意与 {@code FontLoader.scanExtraFromEnv} 保持一致：{@code -D} 优先于环境变量，
     * 多目录按 {@link File#pathSeparator} 分隔（Windows 是 {@code ;}，故 {@code C:/…} 的冒号不会被误切）。</p>
     *
     * @return 目录下的字体文件路径（按文件名排序，保证同一环境每次探测结果一致）；未配置时空数组
     */
    // 包内可见（非 private）：探测结果是进程级缓存，单测无法重新触发 systemCjk()，
    // 只能直接测这段解析逻辑——它正是此前漏掉挂载目录的那处
    static String[] operatorFontFiles() {
        String cfg = System.getProperty("officia.fonts.dir");
        if (cfg == null || cfg.isBlank()) {
            cfg = System.getenv("OFFICIA_FONTS_DIR");
        }
        if (cfg == null || cfg.isBlank()) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        for (String dir : cfg.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            File d = new File(dir.trim());
            if (dir.isBlank() || !d.isDirectory()) {
                continue;
            }
            File[] files = d.listFiles(f -> {
                String n = f.getName().toLowerCase(Locale.ROOT);
                return f.isFile() && (n.endsWith(".ttf") || n.endsWith(".ttc"));
            });
            if (files == null) {
                continue;
            }
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                out.add(f.getPath());
            }
        }
        return out.toArray(new String[0]);
    }

    /**
     * 依次尝试候选路径，读到第一个<b>校验通过</b>的即写入缓存并返回 true。
     *
     * @param candidates 候选路径
     * @param needCjk    是否要求含中文字形（CJK 候选要求，ASCII 兜底不要求）
     */
    private static boolean load(String[] candidates, boolean needCjk) {
        for (String p : candidates) {
            File f = new File(p);
            if (!f.isFile() || f.length() == 0) {
                continue;
            }
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                if (needCjk ? usableForCjk(data) : usableForAscii(data)) {
                    cached = data;
                    return true;
                }
            } catch (Exception ignore) {
                // 读不了 / 解析不了就试下一个候选
            }
        }
        return false;
    }

    /**
     * 这份字体字节能不能用来画中文水印。
     *
     * <p>两个条件缺一不可，正好对应线上踩过的两种"看起来有字体、实际画不出中文"：</p>
     * <ul>
     *   <li><b>有 glyf 表</b>——CFF/OTTO 轮廓（Noto CJK 官方包、PingFang）会让
     *       officia 的子集器抛 {@code 字体无 glyf/loca（可能为 CFF/OTTO 轮廓）}；</li>
     *   <li><b>真有中文字形</b>——纯拉丁字体（DejaVu/Arial）有 glyf 但中文码位映射到
     *       .notdef，子集器不报错，中文<b>静默画成空白</b>，比报错更难发现。</li>
     * </ul>
     *
     * @param data 字体文件字节
     * @return 能画中文返回 true
     */
    static boolean usableForCjk(byte[] data) {
        try {
            TrueTypeFont f = new TrueTypeFont(data);
            return f.getTable("glyf") != null
                && f.hasGlyph('中') && f.hasGlyph('国') && f.hasGlyph('权');
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 这份字体能不能至少画 ASCII（同样要求 glyf，否则子集器一样抛异常）。 */
    static boolean usableForAscii(byte[] data) {
        try {
            TrueTypeFont f = new TrueTypeFont(data);
            return f.getTable("glyf") != null && f.hasGlyph('A');
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 供启动日志展示是否探测到可用字体。 */
    static boolean available() {
        return systemCjk() != null;
    }

    /** 探测到的字体是否含中文字形；false 时中文水印会是空白。 */
    static boolean hasCjk() {
        systemCjk();
        return cjk;
    }
}
