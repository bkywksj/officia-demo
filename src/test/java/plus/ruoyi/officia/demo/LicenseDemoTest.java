package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.common.exception.OfficiaException;
import plus.ruoyi.officia.license.OfficiaLicense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 授权门面消费示例：验证 officia-license 作为依赖被引入后可正常使用。
 *
 * <p>演示"未授权默认行为"——真实客户不配 License 时 officia 处于评估态。
 * "授权解锁"由客户放入自己的 officia.lic 生效（见 EnforcementDemoTest#licensedIfProvided，
 * demo 不内置任何令牌）。</p>
 *
 * @author officia-demo
 */
@DisplayName("授权门面消费示例")
class LicenseDemoTest {

    @BeforeEach
    void reset() {
        OfficiaLicense.reset();
    }

    @Test
    @DisplayName("默认未授权 → isEvaluation=true、任何模块 hasModule=false")
    void evaluationByDefault() {
        assertThat(OfficiaLicense.isLicensed()).isFalse();
        assertThat(OfficiaLicense.isEvaluation()).isTrue();
        assertThat(OfficiaLicense.hasModule("words")).isFalse();
        assertThat(OfficiaLicense.hasModule("cells")).isFalse();
    }

    @Test
    @DisplayName("无效 License 串 → 抛 OfficiaException（配置性错误），不 System.exit")
    void invalidLicenseThrows() {
        assertThatThrownBy(() -> OfficiaLicense.setLicense("not-a-valid-token"))
            .isInstanceOf(OfficiaException.class);
        // 仍为评估态
        assertThat(OfficiaLicense.isEvaluation()).isTrue();
    }

    @Test
    @DisplayName("空 License → 抛 OfficiaException")
    void emptyLicenseThrows() {
        assertThatThrownBy(() -> OfficiaLicense.setLicense(""))
            .isInstanceOf(OfficiaException.class);
    }
}
