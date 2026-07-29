package plus.ruoyi.officia.demo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import plus.ruoyi.officia.barcode.OfficiaBarCode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Officia.BarCode 消费示例：Code128 / QR → PNG 字节。
 *
 * @author officia-demo
 */
@DisplayName("BarCode 能力消费示例")
class BarcodeDemoTest {

    /** PNG 魔数：\x89PNG */
    private static void assertPng(byte[] png) {
        assertThat(png).isNotEmpty();
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }

    @Test
    @DisplayName("Code128 → PNG")
    void code128() {
        assertPng(OfficiaBarCode.code128Png("OFFICIA-2026"));
    }

    @Test
    @DisplayName("QR → PNG")
    void qr() {
        assertPng(OfficiaBarCode.qrPng("https://ruoyi.plus"));
    }
}
