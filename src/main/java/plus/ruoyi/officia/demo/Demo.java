package plus.ruoyi.officia.demo;

import plus.ruoyi.officia.cells.OfficiaCells;
import plus.ruoyi.officia.license.OfficiaLicense;

import java.nio.charset.StandardCharsets;

/**
 * officia 依赖消费演示（可 main 运行）。
 *
 * <p>展示：引入 officia-all + officia-license 后，直接调门面即可用；未配 License 处于评估态。</p>
 *
 * @author officia-demo
 */
public final class Demo {

    private Demo() {
    }

    public static void main(String[] args) {
        System.out.println("==== Officia 依赖消费演示 ====");

        // 1) CSV → PDF
        byte[] pdf = OfficiaCells.csvToPdf("Name,Dept\nAlice,R&D\nBob,Product");
        System.out.println("[Cells] CSV→PDF: " + pdf.length + " 字节, 头="
            + new String(pdf, 0, 8, StandardCharsets.ISO_8859_1).trim());

        // 2) 公式求值
        Object sum = OfficiaCells.evaluateFormula(java.util.Map.of("A1", "10", "A2", "32"), "=A1+A2");
        System.out.println("[Cells] =A1+A2 = " + sum);

        // 3) 授权门面（未配 License → 评估态）
        System.out.println("[License] isLicensed = " + OfficiaLicense.isLicensed());
        System.out.println("[License] isEvaluation = " + OfficiaLicense.isEvaluation());
        System.out.println("[License] hasModule(words) = " + OfficiaLicense.hasModule("words"));

        System.out.println("==== 演示结束（引入 officia 依赖即可用，零传递第三方依赖） ====");
    }
}
