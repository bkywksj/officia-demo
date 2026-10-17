package plus.ruoyi.officia.demo.web;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * 「效果不佳的文件」反馈存储：一个反馈一个目录，元数据是 {@code meta.json}，原文件与当时的转换结果放在旁边。
 *
 * <pre>
 * &lt;根目录&gt;/admin.json                    后台账号的口令哈希（见 {@link AdminAuth}）
 * &lt;根目录&gt;/items/20261017-153012-a3f9c1/
 *     meta.json   source.docx   output.pdf
 * </pre>
 *
 * <p>为什么不用数据库：测试台自身零第三方依赖（Java 访问 SQLite 要引第三方驱动、还带本地库）；
 * 单账号、按条收集文件，目录 + JSON 足够，备份就是打包目录，排查就是看文件。</p>
 *
 * <p>安全：目录名由服务端生成并按固定格式校验，落盘文件名固定为 source / output 加白名单扩展名，
 * 用户给的原始文件名只记在 meta 里用于展示与下载——不让它参与任何路径拼接。</p>
 *
 * <p>只在设置了环境变量 {@code OFFICIA_FEEDBACK_DIR} 时启用（线上站点）；客户在本机 {@code java -jar} 时不落盘。</p>
 *
 * @author officia-demo
 */
final class Feedback {

    /** 状态取值 → 中文名；顺序即后台下拉框的顺序。 */
    static final Map<String, String> STATUS = statusLabels();
    /** 这两种状态的反馈超过保留期后自动删除；「新提交」「处理中」一直留着。 */
    private static final Set<String> CLOSED = new HashSet<>(Arrays.asList("fixed", "wontfix"));
    /** 允许反馈的源文件类型（扩展名小写，不含点）。 */
    static final Set<String> ALLOWED_EXT = new HashSet<>(Arrays.asList(
        "doc", "docx", "xls", "xlsx", "csv", "ppt", "pptx", "pdf", "eml",
        "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp"));
    private static final Pattern ID = Pattern.compile("^\\d{8}-\\d{6}-[0-9a-f]{6}$");
    private static final long HOUR = 3600_000L;

    private final Path items;
    private final long maxFileBytes;
    private final int perHour;
    private final long totalBytes;
    private final int retainDays;
    private final SecureRandom random = new SecureRandom();
    /** IP → 最近一小时内各次提交的时刻。 */
    private final Map<String, Deque<Long>> recent = new HashMap<>();

    Feedback(Path root, long maxFileBytes, int perHour, long totalBytes, int retainDays) throws IOException {
        this.items = root.resolve("items");
        this.maxFileBytes = maxFileBytes;
        this.perHour = perHour;
        this.totalBytes = totalBytes;
        this.retainDays = retainDays;
        Files.createDirectories(items);
    }

    /**
     * 按环境变量创建；没设 {@code OFFICIA_FEEDBACK_DIR} 返回 null（功能关闭）。
     * 可调：{@code OFFICIA_FEEDBACK_MAX_MB}（50）、{@code OFFICIA_FEEDBACK_PER_HOUR}（10）、
     * {@code OFFICIA_FEEDBACK_TOTAL_GB}（5）、{@code OFFICIA_FEEDBACK_RETAIN_DAYS}（90）。
     */
    static Feedback fromEnv() throws IOException {
        String dir = System.getenv("OFFICIA_FEEDBACK_DIR");
        if (Http.blank(dir)) {
            return null;
        }
        return new Feedback(Paths.get(dir.trim()),
            envLong("OFFICIA_FEEDBACK_MAX_MB", 50) * 1024 * 1024,
            (int) envLong("OFFICIA_FEEDBACK_PER_HOUR", 10),
            envLong("OFFICIA_FEEDBACK_TOTAL_GB", 5) * 1024 * 1024 * 1024,
            (int) envLong("OFFICIA_FEEDBACK_RETAIN_DAYS", 90));
    }

    long maxFileBytes() {
        return maxFileBytes;
    }

    // ---------------------------------------------------------------- 提交

    /** 本次提交是否在频率限制内；在限内则记一次。 */
    synchronized boolean allow(String ip, long now) {
        Deque<Long> q = recent.get(ip);
        if (q == null) {
            q = new ArrayDeque<>();
            recent.put(ip, q);
        }
        while (!q.isEmpty() && q.peekFirst() <= now - HOUR) {
            q.pollFirst();
        }
        if (q.size() >= perHour) {
            return false;
        }
        q.addLast(now);
        return true;
    }

    /**
     * 存一条反馈。
     *
     * @param meta 调用方给的字段（备注、能力、环境等），这里补上 id / 时间 / 状态 / 文件信息
     * @return 新反馈的 id
     * @throws IllegalArgumentException 文件类型不允许、超限、空间不足（上层转 400）
     */
    synchronized String create(String sourceName, byte[] source, String outputName, byte[] output,
                               Map<String, Object> meta) throws IOException {
        String ext = ext(sourceName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("不支持反馈这种文件类型：" + (ext.isEmpty() ? "（无扩展名）" : "." + ext));
        }
        if (source.length == 0) {
            throw new IllegalArgumentException("文件是空的");
        }
        if (source.length > maxFileBytes) {
            throw new IllegalArgumentException("文件 " + Http.humanSize(source.length) + " 超过反馈上限 " + Http.humanSize(maxFileBytes));
        }
        // 结果文件超限时不收结果、只收原文件——原文件才是复现的关键
        if (output != null && output.length > maxFileBytes) {
            output = null;
        }
        long incoming = source.length + (output == null ? 0 : output.length);
        if (usedBytes() + incoming > totalBytes) {
            throw new IllegalArgumentException("反馈空间已满，暂时不能接收，请稍后再试");
        }
        File disk = items.toFile();
        if (disk.getUsableSpace() < incoming * 2 + 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("服务器磁盘空间不足，暂时不能接收，请稍后再试");
        }

        String id = newId();
        Path dir = items.resolve(id);
        Files.createDirectories(dir);
        String srcFile = "source." + ext;
        Files.write(dir.resolve(srcFile), source);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("createdAt", isoNow());
        m.put("status", "new");
        m.put("statusChangedAt", m.get("createdAt"));
        m.put("source", fileInfo(sourceName, srcFile, source));
        if (output != null) {
            String outFile = "output." + safeExt(outputName);
            Files.write(dir.resolve(outFile), output);
            m.put("output", fileInfo(outputName, outFile, output));
        } else {
            m.put("output", null);
        }
        m.putAll(meta);
        m.put("remark", "");
        writeMeta(dir, m);
        return id;
    }

    // ---------------------------------------------------------------- 后台

    /** 全部反馈的元数据，新的在前。读坏了的条目跳过，不让一个坏文件拖垮列表。 */
    synchronized List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(items)) {
            for (Path d : ds) {
                if (!ID.matcher(d.getFileName().toString()).matches()) {
                    continue;
                }
                try {
                    out.add(readMeta(d));
                } catch (IOException | RuntimeException ignore) {
                    // 半截写入或手工改坏的 meta：列表里不显示，目录留着人工处理
                }
            }
        }
        Collections.sort(out, (a, b) -> String.valueOf(b.get("id")).compareTo(String.valueOf(a.get("id"))));
        return out;
    }

    /** 取一条；id 不合法或不存在返回 null。 */
    synchronized Map<String, Object> get(String id) throws IOException {
        Path d = dir(id);
        return d == null || !Files.isRegularFile(d.resolve("meta.json")) ? null : readMeta(d);
    }

    /**
     * 原文件或结果文件的磁盘路径；没有返回 null。
     *
     * @param which {@code source} 或 {@code output}
     */
    @SuppressWarnings("unchecked")
    synchronized Path file(String id, String which) throws IOException {
        if (!"source".equals(which) && !"output".equals(which)) {
            return null;
        }
        Map<String, Object> m = get(id);
        if (m == null || !(m.get(which) instanceof Map)) {
            return null;
        }
        String f = String.valueOf(((Map<String, Object>) m.get(which)).get("file"));
        // meta 里的文件名也按白名单格式校验一遍，即使 meta.json 被人改过也逃不出这个目录
        if (!f.matches("^(source|output)\\.[a-z0-9]{1,5}$")) {
            return null;
        }
        Path p = dir(id).resolve(f);
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * 改状态与处理记录。
     *
     * @return 改后的元数据；id 不存在返回 null
     */
    synchronized Map<String, Object> update(String id, String status, String remark) throws IOException {
        Map<String, Object> m = get(id);
        if (m == null) {
            return null;
        }
        if (status != null) {
            if (!STATUS.containsKey(status)) {
                throw new IllegalArgumentException("未知状态：" + status);
            }
            if (!status.equals(m.get("status"))) {
                m.put("status", status);
                m.put("statusChangedAt", isoNow());
            }
        }
        if (remark != null) {
            if (remark.length() > 5000) {
                throw new IllegalArgumentException("处理记录最多 5000 字");
            }
            m.put("remark", remark);
        }
        m.put("updatedAt", isoNow());
        writeMeta(dir(id), m);
        return m;
    }

    /** 删除一条（连同文件）；不存在返回 false。 */
    synchronized boolean delete(String id) throws IOException {
        Path d = dir(id);
        if (d == null || !Files.isDirectory(d)) {
            return false;
        }
        deleteDir(d);
        return true;
    }

    /**
     * 清理过期反馈：「已修复」「不处理」且状态改变已超过保留期的整条删除。
     *
     * @return 删除的条数
     */
    synchronized int purge(long now) throws IOException {
        int n = 0;
        long cutoff = now - retainDays * 24L * HOUR;
        for (Map<String, Object> m : list()) {
            if (!CLOSED.contains(String.valueOf(m.get("status")))) {
                continue;
            }
            Date changed = parseIso(String.valueOf(m.get("statusChangedAt")));
            if (changed != null && changed.getTime() < cutoff && delete(String.valueOf(m.get("id")))) {
                n++;
            }
        }
        return n;
    }

    /** 已占用的字节（所有反馈目录里的文件之和）。 */
    synchronized long usedBytes() throws IOException {
        long sum = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(items)) {
            for (Path d : ds) {
                if (Files.isDirectory(d)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(d)) {
                        for (Path f : files) {
                            sum += Files.size(f);
                        }
                    }
                }
            }
        }
        return sum;
    }

    // ---------------------------------------------------------------- 工具

    /** 合法 id 对应的目录；不合法返回 null（挡住 ../ 之类）。 */
    private Path dir(String id) {
        return id != null && ID.matcher(id).matches() ? items.resolve(id) : null;
    }

    private String newId() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        while (true) {
            String id = f.format(new Date()) + "-" + String.format("%06x", random.nextInt(0x1000000));
            if (!Files.exists(items.resolve(id))) {
                return id;
            }
        }
    }

    private static Map<String, Object> fileInfo(String name, String file, byte[] data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("file", file);
        m.put("size", data.length);
        m.put("sha256", sha256(data));
        return m;
    }

    private static Map<String, Object> readMeta(Path dir) throws IOException {
        return JsonReader.parseObject(new String(Files.readAllBytes(dir.resolve("meta.json")), StandardCharsets.UTF_8));
    }

    /** 先写临时文件再原子替换：写到一半断电也不会留下坏的 meta.json。 */
    private static void writeMeta(Path dir, Map<String, Object> m) throws IOException {
        StringBuilder sb = new StringBuilder();
        Json.writeValue(sb, m);
        Path tmp = dir.resolve("meta.json.tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, dir.resolve("meta.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteDir(Path d) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            for (Path f : ds) {
                Files.delete(f);
            }
        }
        Files.delete(d);
    }

    /** 文件扩展名（小写，不含点）；没有返回空串。 */
    static String ext(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 结果文件的扩展名只允许 1～5 位字母数字，否则记成 bin。 */
    private static String safeExt(String name) {
        String e = ext(name);
        return e.matches("^[a-z0-9]{1,5}$") ? e : "bin";
    }

    private static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 当前时刻，ISO 8601（东八区），meta 与口令文件共用。 */
    static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f.format(new Date());
    }

    private static Date parseIso(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(s);
        } catch (ParseException e) {
            return null;
        }
    }

    private static long envLong(String name, long def) {
        String v = System.getenv(name);
        if (Http.blank(v)) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Map<String, String> statusLabels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("new", "新提交");
        m.put("processing", "处理中");
        m.put("fixed", "已修复");
        m.put("wontfix", "不处理");
        return Collections.unmodifiableMap(m);
    }
}
