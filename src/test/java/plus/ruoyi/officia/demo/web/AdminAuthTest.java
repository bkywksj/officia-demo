package plus.ruoyi.officia.demo.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台单账号登录：口令只存哈希、连错锁定、会话过期、改口令后旧会话作废。
 *
 * @author officia-demo
 */
@DisplayName("AdminAuth 后台登录")
class AdminAuthTest {

    @TempDir
    Path root;

    /** 可拨的假时钟：锁定与过期不用真的等。 */
    private final long[] now = {1_000_000_000_000L};

    private AdminAuth auth() throws Exception {
        return new AdminAuth(root.resolve("admin.json"), "init-pass-1", () -> now[0]);
    }

    @Test
    @DisplayName("首次启动生成 admin.json：只有加盐哈希，没有明文口令")
    void storesOnlyAHash() throws Exception {
        auth();
        String file = new String(Files.readAllBytes(root.resolve("admin.json")), StandardCharsets.UTF_8);
        assertThat(file).contains("PBKDF2WithHmacSHA256").contains("\"salt\"").contains("\"hash\"")
            .doesNotContain("init-pass-1");
    }

    @Test
    @DisplayName("账号口令对才发凭证；账号或口令错都不发")
    void login() throws Exception {
        AdminAuth a = auth();
        AdminAuth.Login ok = a.login("admin", "init-pass-1", "1.1.1.1");
        assertThat(ok.token).isNotBlank();
        assertThat(a.valid(ok.token)).isTrue();
        assertThat(a.login("admin", "wrong", "1.1.1.1").token).isNull();
        assertThat(a.login("root", "init-pass-1", "1.1.1.1").token).isNull();
        assertThat(a.valid(null)).isFalse();
        assertThat(a.valid("forged")).isFalse();
    }

    @Test
    @DisplayName("同一 IP 连错 5 次锁 15 分钟：锁定期内口令对也不让进，到期后恢复；别的 IP 不受影响")
    void lockoutAfterRepeatedFailures() throws Exception {
        AdminAuth a = auth();
        for (int i = 0; i < AdminAuth.MAX_FAILS; i++) {
            a.login("admin", "bad" + i, "9.9.9.9");
        }
        AdminAuth.Login locked = a.login("admin", "init-pass-1", "9.9.9.9");
        assertThat(locked.token).isNull();
        assertThat(locked.error).contains("分钟后再试");
        assertThat(a.login("admin", "init-pass-1", "8.8.8.8").token).as("别的 IP").isNotNull();

        now[0] += 15 * 60 * 1000L + 1;
        assertThat(a.login("admin", "init-pass-1", "9.9.9.9").token).as("锁定到期").isNotNull();
    }

    @Test
    @DisplayName("会话 8 小时过期；退出后立即失效")
    void sessionExpiryAndLogout() throws Exception {
        AdminAuth a = auth();
        String t1 = a.login("admin", "init-pass-1", "1.1.1.1").token;
        String t2 = a.login("admin", "init-pass-1", "1.1.1.1").token;
        a.logout(t2);
        assertThat(a.valid(t2)).isFalse();
        now[0] += 8 * 3600 * 1000L + 1;
        assertThat(a.valid(t1)).isFalse();
    }

    @Test
    @DisplayName("改口令：原口令不对拒绝；成功后新口令生效、旧口令失效、其它会话作废但当前会话保留；重启后仍以文件为准")
    void changePassword() throws Exception {
        AdminAuth a = auth();
        String mine = a.login("admin", "init-pass-1", "1.1.1.1").token;
        String other = a.login("admin", "init-pass-1", "2.2.2.2").token;
        assertThat(a.changePassword("wrong", "new-pass-2", mine)).contains("原口令");
        assertThat(a.changePassword("init-pass-1", "123", mine)).contains("6～128");
        assertThat(a.changePassword("init-pass-1", "new-pass-2", mine)).isNull();

        assertThat(a.valid(mine)).isTrue();
        assertThat(a.valid(other)).isFalse();
        assertThat(a.login("admin", "init-pass-1", "3.3.3.3").token).isNull();
        assertThat(a.login("admin", "new-pass-2", "3.3.3.3").token).isNotNull();

        // 重启（重新 new）：admin.json 已存在，初始口令不再生效
        AdminAuth restarted = auth();
        assertThat(restarted.login("admin", "init-pass-1", "4.4.4.4").token).isNull();
        assertThat(restarted.login("admin", "new-pass-2", "4.4.4.4").token).isNotNull();
    }
}
