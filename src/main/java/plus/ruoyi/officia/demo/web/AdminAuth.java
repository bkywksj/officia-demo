package plus.ruoyi.officia.demo.web;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反馈后台的单账号登录：口令哈希存在数据目录的 {@code admin.json}，会话只在内存。
 *
 * <ul>
 *   <li>口令用 PBKDF2-HMAC-SHA256 加盐迭代（JDK 8 自带）存成哈希，文件里没有明文；</li>
 *   <li>首次启动用初始口令生成 {@code admin.json}，之后在后台改；</li>
 *   <li>会话凭证 32 字节随机数，8 小时过期，进程重启即失效（只放内存，不落盘）；</li>
 *   <li>同一 IP 连续输错 {@value #MAX_FAILS} 次锁定 15 分钟；比较哈希用定长时间比较，不泄露时序。</li>
 * </ul>
 *
 * @author officia-demo
 */
final class AdminAuth {

    static final String USERNAME = "admin";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final long SESSION_MILLIS = 8L * 3600 * 1000;
    static final int MAX_FAILS = 5;
    private static final long LOCK_MILLIS = 15L * 60 * 1000;

    private final Path file;
    private final SecureRandom random = new SecureRandom();
    /** 会话凭证 → 过期时刻。 */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    /** IP → [连续失败次数, 锁定到期时刻]。 */
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();
    /** 可注入的时钟（测试用），默认系统时间。 */
    private final Clock clock;

    interface Clock {
        long now();
    }

    AdminAuth(Path file, String initialPassword) throws IOException {
        this(file, initialPassword, System::currentTimeMillis);
    }

    AdminAuth(Path file, String initialPassword, Clock clock) throws IOException {
        this.file = file;
        this.clock = clock;
        if (!Files.exists(file)) {
            writeHash(initialPassword);
        }
    }

    /** 登录结果。 */
    static final class Login {
        final String token;     // 成功时的会话凭证
        final String error;     // 失败原因（给用户看的）

        private Login(String token, String error) {
            this.token = token;
            this.error = error;
        }
    }

    /**
     * 校验账号口令；成功发放会话凭证。
     *
     * @param ip 客户端 IP，用于失败计数与锁定
     */
    Login login(String username, String password, String ip) throws IOException {
        long now = clock.now();
        long[] f = fails.get(ip);
        if (f != null && f[1] > now) {
            long minutes = (f[1] - now + 59_999) / 60_000;
            return new Login(null, "尝试次数过多，请 " + minutes + " 分钟后再试");
        }
        boolean ok = USERNAME.equals(username) && password != null && verify(password);
        if (!ok) {
            long[] cur = fails.computeIfAbsent(ip, k -> new long[2]);
            synchronized (cur) {
                cur[0]++;
                if (cur[0] >= MAX_FAILS) {
                    cur[0] = 0;
                    cur[1] = now + LOCK_MILLIS;
                    return new Login(null, "账号或口令错误；已连续错 " + MAX_FAILS + " 次，锁定 15 分钟");
                }
            }
            return new Login(null, "账号或口令错误");
        }
        fails.remove(ip);
        String token = newToken();
        sessions.put(token, now + SESSION_MILLIS);
        return new Login(token, null);
    }

    /** 会话凭证是否有效（顺手清掉过期的）。 */
    boolean valid(String token) {
        long now = clock.now();
        for (Iterator<Map.Entry<String, Long>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() <= now) {
                it.remove();
            }
        }
        return token != null && sessions.containsKey(token);
    }

    void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * 改口令：先验旧口令；成功后其他会话全部作废（只保留当前这个），防止旧会话被人继续用。
     *
     * @return null 表示成功，否则为失败原因
     */
    String changePassword(String oldPassword, String newPassword, String keepToken) throws IOException {
        if (oldPassword == null || !verify(oldPassword)) {
            return "原口令不对";
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 128) {
            return "新口令长度应为 6～128 个字符";
        }
        writeHash(newPassword);
        Long keep = keepToken == null ? null : sessions.get(keepToken);
        sessions.clear();
        if (keep != null) {
            sessions.put(keepToken, keep);
        }
        return null;
    }

    // ---------------------------------------------------------------- 哈希与文件

    private boolean verify(String password) throws IOException {
        Map<String, Object> m = JsonReader.parseObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        byte[] salt = Base64.getDecoder().decode((String) m.get("salt"));
        byte[] expected = Base64.getDecoder().decode((String) m.get("hash"));
        int iterations = ((Number) m.get("iterations")).intValue();
        return MessageDigest.isEqual(pbkdf2(password, salt, iterations), expected);
    }

    /** 写入新口令的哈希；先写临时文件再原子替换，避免写到一半断电留下坏文件、谁都登不进。 */
    private void writeHash(String password) throws IOException {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String json = Json.obj()
            .put("username", USERNAME)
            .put("algorithm", ALGORITHM)
            .put("iterations", ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("hash", Base64.getEncoder().encodeToString(pbkdf2(password, salt, ITERATIONS)))
            .put("updatedAt", Feedback.isoNow())
            .end();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("口令哈希失败：" + e.getMessage(), e);
        }
    }

    private String newToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
