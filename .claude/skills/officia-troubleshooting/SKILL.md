---
name: officia-troubleshooting
description: |
  Officia 报错与结果异常的系统排查：OfficiaException 的异常消息前缀就是故障域路标
  （MS-DOC / CFB / MS-ODRAW / OPC / License / PDF …），按前缀直接定位问题层，
  外加"没报错但结果不对"（空白、水印、版式跑偏）的判别流程。

  触发场景：
  - 抛了 OfficiaException，看不懂什么意思
  - 转换失败 / 打不开 / 结果为空
  - 没报错但输出不对劲
  - 想知道该怎么给officia 提问题、附什么信息

  触发词：报错、异常、OfficiaException、失败、错误、打不开、空白、结果不对、排查、调试、debug、堆栈、stacktrace
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 报错与结果异常排查

## 概述

Officia 只对外抛一种异常：**`OfficiaException`**（继承 `RuntimeException`，在 `plus.ruoyi.officia.common.exception`）。
它**不抛** `IOException` / 其它受检异常——底层异常都被包成 `OfficiaException.of(msg, cause)`。

```java
try {
    byte[] pdf = OfficiaWords.toPdf(docx);
} catch (OfficiaException e) {
    log.error("转换失败: {}", e.getMessage(), e);   // getCause() 里有底层原因
}
```

## 🔴 第一步：看异常消息**前缀**，它就是故障域路标

officia 的异常消息用统一前缀标注问题发生在哪一层（核实自源码中的实际消息）：

| 消息前缀 | 故障域 | 通常意味着 |
|---|---|---|
| `MS-DOC …` | `.doc` 二进制格式解析 | 老 Word 格式的结构问题，**最常见的一类** |
| `CFB …` | 复合文档容器（.doc 的外壳） | 文件损坏、非法结构，或超出安全上限 |
| `MS-ODRAW …` | Office 绘图对象 | doc 内图形/图片负载 |
| `MS-OLEPS …` / `MS-OSHARED …` | OLE 属性集 / 共享结构 | doc 元数据部分 |
| `OPC …` / `document.xml …` / `presentation.xml …` / `XML …` | OOXML（docx/xlsx/pptx）解包与解析 | zip 包结构或 XML 内容问题 |
| `DOCX …` | docx 特定环节 | 如内嵌字体缺 fontKey |
| `PDF …` | PDF 读写 | 结构、加密字典等 |
| `License …` | 授权 | 令牌为空 / 无效 |
| `JSON …` | 模板 JSON 数据源 | 数据格式问题 |
| `条码` / `QR` / `UPC-A` / `ITF-14` / `Code39` / `Code93` … | 条码 | 输入不符合码制约束 |
| `源文档为空` | 输入校验 | 传进来的 `byte[]` 是空的 |
| `读取输入流失败` / `读取文件失败:` / `写出文件失败:` | I/O | 路径、权限、流已关闭 |
| `无法解码图片（ImageIO …` | 图像 | 格式不被 `javax.imageio` 支持 |

> 💡 看到 `MS-DOC` / `CFB` 开头 → 一定是在处理 `.doc`。先确认这份 `.doc` 是不是加密的（**加密 DOC 明确不支持**）。

## 第二步：按现象定位

### A. 抛异常了

| 异常消息 | 原因 | 处置 |
|---|---|---|
| `源文档为空` | 传入 `byte[]` 长度 0 | 检查文件读取是否成功、上传是否为空 |
| `CFB Header Signature 无效` | 不是 CFB 文件（可能是 docx 被改了扩展名，或文件损坏） | 检查真实格式：docx 头是 `PK`，doc 头是 `D0 CF 11 E0` |
| `MS-DOC …`（各种） | `.doc` 结构问题或触及不支持项 | 若是**加密 doc**：明确不支持，先用 Word 另存为解密版；否则用测试台复现并留样本 |
| `CFB … 超过安全上限` | 触发 `CfbLimits` 内存安全边界 | 这是**保护机制**不是 bug，见 `officia-performance` |
| `License 无效` / `License 为空` | 显式 `setLicense` 传了坏令牌 | 重新下载 `.lic`，见 `officia-license` |
| `Code39 不支持的字符: 'X'` | 该码制字符集不含此字符 | 换 Code128 或 QR，见 `officia-barcode` |
| `无法解码图片（ImageIO …` | WebP/AVIF/TIFF 等不支持的格式 | 先转成 PNG/JPEG |
| `文档不含表格` | `toCsv` 的输入 xlsx 没有可导出的表 | 确认工作表内容 |
| 循环引用 / 求值错误 | 公式互相引用 | 见 `officia-cells` |

### B. 没报错，但结果不对

这类**不是异常**，逐条排除：

```
输出不对
├─ 有"评估"水印 / 页数被截断
│   └─ 不是 bug → 未授权 + 门控已开 → officia-license
├─ 中文是方块 / 空白
│   └─ 字体没找到 → officia-chinese-font
├─ 中文是乱码（不是方块，是错字）
│   └─ 源文件编码问题，不是 officia → 确认源文档/CSV 的原始编码
├─ PDF 打开是空白页
│   ├─ 源文档本身就是空的？先 pageCount / extractText 验证
│   └─ 版式引擎未覆盖的元素类型 → 用测试台对比确认
├─ 版式与 Word/PPT 里不一致
│   ├─ PPT：可能用错了模式 → toPdfLayoutAware，见 officia-slides
│   └─ Word：自研排版引擎非逐像素复刻，复杂版式差异属预期
├─ 表格/循环没渲染
│   └─ 模板语法问题 → officia-template 的排查表
└─ 数值和 Excel 里显示的不一样
    └─ officia 实算公式不信缓存值 → 见 officia-cells
```

## 第三步：最小复现

把问题缩到最小，再判断是 officia 的问题还是用法问题：

```java
import plus.ruoyi.officia.words.OfficiaWords;
import plus.ruoyi.officia.common.exception.OfficiaException;
import java.nio.file.*;

public class Repro {
    public static void main(String[] args) throws Exception {
        byte[] src = Files.readAllBytes(Path.of("problem.docx"));

        // 1) 输入自检
        System.out.println("字节数 = " + src.length);
        System.out.printf("文件头 = %02X %02X %02X %02X%n",
            src[0], src[1], src[2], src[3]);   // PK.. = OOXML(zip)；D0CF11E0 = CFB(.doc)

        // 2) 授权/门控状态（排除"水印不是 bug"）
        System.out.println("enforced=" + plus.ruoyi.officia.license.OfficiaLicense.isEnforced()
                       + " licensed=" + plus.ruoyi.officia.license.OfficiaLicense.isLicensed());

        // 3) 转换 + 完整堆栈
        try {
            byte[] pdf = OfficiaWords.toPdf(src);
            System.out.println("成功，PDF " + pdf.length + " 字节, 页数="
                             + plus.ruoyi.officia.pdf.OfficiaPdf.pageCount(pdf));
        } catch (OfficiaException e) {
            System.out.println("消息 = " + e.getMessage());
            System.out.println("根因 = " + e.getCause());
            e.printStackTrace();
        }
    }
}
```

**文件头速查**：

| 头字节 | 真实格式 |
|---|---|
| `50 4B`（`PK`） | ZIP → docx / xlsx / pptx（OOXML） |
| `D0 CF 11 E0` | CFB → doc / xls / ppt（老二进制） |
| `25 50 44 46`（`%PDF`） | PDF |
| `89 50 4E 47` | PNG |

> 扩展名不可信——用户把 `.docx` 改名成 `.doc` 是最常见的"格式不对"来源。

## 第四步：确认是否属于已知不支持项

报问题之前先对照 `officia-capability-map` 的"明确不支持"表：加密 DOC、WMF/EMF/PICT/CMYK JPEG、宏/OLE/签名负载解析、竖排与 RTL、DOC 内嵌字体。

命令行速查当前版本的实测事实：

```bash
cat ../officia/status.json          # capabilities / explicitly_rejected / known_gaps / not_verified
```

## 提问题时该附什么

| 必附 | 说明 |
|---|---|
| 完整异常消息 + 堆栈 | 消息前缀决定故障域 |
| 输入文件的**文件头 4 字节**与大小 | 判断真实格式 |
| officia 版本 | `mvn dependency:tree -Dincludes=plus.ruoyi` |
| JDK 版本 | `java -version` |
| 最小复现样本 | 脱敏后的文件（若不能提供，说明文档特征：多少页、有无表格/图片/公式） |
| 是否已加载授权 | `isEnforced()` / `isLicensed()` 输出 |

## 用测试台快速二分

测试台能上传真实文件逐能力点测，是最快的"是我的代码还是 officia"判别方式：

```bash
mvn -o package && java -jar target/officia-demo-1.0.0.jar
```

- 测试台里**能转成功** → 你的调用代码有问题（参数、流已关闭、字体没配…）
- 测试台里**也失败** → 是文档本身或 officia 的边界，带样本反馈

还可以点**「批量回归」**跑全能力自检（用 officia 自造输入 + 断言），确认整体环境正常。见 `officia-testbench`。

## 相关技能

| 症状 | 用 |
|---|---|
| 有水印 | `officia-license` |
| 中文方块 | `officia-chinese-font` |
| 慢 / OOM / 触发安全上限 | `officia-performance` |
| 不确定是否支持 | `officia-capability-map` |
| 升级后才出现的问题 | `officia-upgrade` |
