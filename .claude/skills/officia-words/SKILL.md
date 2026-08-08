---
name: officia-words
description: |
  用 OfficiaWords 把 Word 转成 PDF：docx / doc（自动识别）、四种入参形态、流式转换省内存、
  取页数与耗时、ConvertOptions 配置（纸张 / 字体目录 / 字体嵌入 / 图像降采样 / 超时）。

  触发场景：
  - Word 转 PDF、docx 转 PDF、doc 转 PDF
  - 要知道转换出来几页、耗时多少
  - 大文档转换想省内存 / 直接写进 HTTP 响应流
  - 要改纸张大小、指定字体目录
  - .doc 老格式能不能转、有什么限制

  触发词：Word、docx、doc、转PDF、word转换、文档转换、toPdf、流式、页数、耗时、ConvertOptions、纸张、A4、图像降采样、maxImageDpi、PDF体积
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# Word → PDF（`OfficiaWords`）

## 概述

`OfficiaWords` 是 Word 处理的唯一入口，两类能力：

1. **转换**：`toPdf` / `convert` —— docx、doc 都走同一入口，**自动识别格式**（本技能）
2. **模板填充 / 邮件合并**：`fillTemplate*` —— 见 `officia-template`

底层链路 `WordParser → DocModel(IR) → LayoutEngine → PdfRenderer` 已被门面完全屏蔽，你只管调静态方法。

## 最短用法

```java
import plus.ruoyi.officia.words.OfficiaWords;
import java.nio.file.Files;
import java.nio.file.Path;

byte[] docx = Files.readAllBytes(Path.of("in.docx"));
byte[] pdf  = OfficiaWords.toPdf(docx);          // docx / doc 都用这个，自动识别
Files.write(Path.of("out.pdf"), pdf);
```

## 全部转换方法（核实自 `OfficiaWords` 源码）

### 返回 `byte[]`（普通场景）

```java
byte[] toPdf(byte[] docx)
byte[] toPdf(byte[] docx, ConvertOptions options)
byte[] toPdf(InputStream in)      // 读取后不主动关闭调用方的流
byte[] toPdf(File file)
```

### 返回富结果（要页数 / 耗时）

```java
ConvertResult r = OfficiaWords.convert(docx, ConvertOptions.defaults());
byte[] pdf   = r.getData();
int    pages = r.getPageCount();
long   ms    = r.getCostMillis();
```

### 流式（省内存，返回页数）

```java
int toPdf(byte[] docx, OutputStream out)                          // 不关闭 out
int toPdf(byte[] docx, ConvertOptions options, OutputStream out)  // options 传 null 用默认
int toPdf(InputStream in, OutputStream out)                       // 两端都不主动关闭
int toPdf(File inDocx, File outPdf)                               // 边生成边写盘，覆盖写
```

流式的收益与边界（照抄源码 Javadoc 的诚实说法）：

> 省去整份输出 PDF 的中间 `byte[]` 与二次拷贝，降低峰值内存。
> **输入 docx 与排版仍需整篇在内存**——这是格式与排版的本质，不是这个方法能优化的。

选型：输出 PDF 大 / 要直写文件或 HTTP 响应 → 用流式；否则用 `byte[]` 版本更简单。详见 `officia-performance`。

## `ConvertOptions` 配置

```java
import plus.ruoyi.officia.engine.api.ConvertOptions;
import plus.ruoyi.officia.engine.api.enums.PageSize;

ConvertOptions opts = ConvertOptions.defaults()
    .setDefaultPageSize(PageSize.A4)        // 默认 A4
    .setFontDirectory("/usr/share/fonts")   // 字体目录（中文关键，见 officia-chinese-font）
    .setEmbedFonts(true)                    // 默认 true
    .setMaxImageDpi(150)                    // 位图降采样上限，0 = 不降采样
    .setTimeoutMillis(30_000)               // 0 = 不限制（默认）
    .setReportFontSubstitutions(true);      // 记录字体替换，默认 false

byte[] pdf = OfficiaWords.toPdf(docx, opts);
```

| 配置 | 默认 | 说明 |
|---|---|---|
| `defaultPageSize` | `PageSize.A4` | 可选 `A4` / `A5` / `A3` / `LETTER` / `LEGAL`。仅作**默认**——文档自身声明了纸张时以文档为准 |
| `fontDirectory` | `null` | 指定字体扫描目录。中文文档必看 `officia-chinese-font` |
| `embedFonts` | `true` | 嵌入字体子集，保证换机器显示一致 |
| `maxImageDpi` | `150` | 位图按它在页面上占的面积降采样，超出部分是纯浪费。设 `0` 关闭、按源图原始像素嵌入 |
| `timeoutMillis` | `0`（不限） | 超时保护，处理不可信来源文档时建议设 |
| `reportFontSubstitutions` | `false` | 记录字体替换/回退，经 `ConvertResult.getFontSubstitutions()` 取出。排查跨平台版式差异用，见 `officia-chinese-font` |

**`maxImageDpi` 什么时候要改**：默认 150 dpi 屏幕阅读与一般打印看不出差别，能大幅压体积——实测某 143 页设计模板 222 MB → 105 MB，耗时也从 140 s 降到 62 s。只有**高精度印刷**或产物还要**二次编辑**时才设 `0` 保留原始像素，代价是体积可能大出数倍。JPEG 源图始终走字节直通、不受该项影响。

`PageSize` 尺寸（PDF 点，1pt = 1/72 英寸）：`A4` 595.32×841.92、`A5` 419.58×595.32、`A3` 841.92×1190.7、`LETTER` 612×792、`LEGAL` 612×1008。

## `.doc`（老二进制格式）的真实状态

**同一个 `toPdf` 入口自动识别** docx（OOXML zip）与 doc（CFB 复合文档），你不需要分支判断：

```java
byte[] pdf = OfficiaWords.toPdf(bytes);   // 自动识别 DOCX(OOXML) / DOC(CFB)
```

但要如实告诉用户它的边界（核实自 `../officia/status.json` 的 `doc_binary_input`）：

| 项 | 状态 |
|---|---|
| 主链路 | 可用，绿测覆盖 |
| 生产就绪 | 🚧 **未宣布**——任务仍在收口 |
| 规范基线 | MS-DOC 12.5 / MS-CFB 12.0 / MS-ODRAW 12.4 / MS-OSHARED 11.1 / MS-OLEPS 9.0 |
| **加密的 .doc**（XOR / RC4 / CryptoAPI） | ❌ **明确不支持** |
| WMF / EMF / PICT / CMYK JPEG 图片负载 | ⚠️ 不解码，**退化为空白占位**（2026-07-31 起整篇照常转出，不再被一张图否决）|
| 宏 / OLE / 签名负载的内部解析 | ❌ 明确不支持（跳过，不解析） |
| 竖排 / RTL 文本框 | ⚠️ 已知缺口 |
| DOC 内嵌字体 | ⚠️ 已知缺口（字体数据是 W3C EOT 格式，该规范未归档） |

> 建议：生产上要批量处理 `.doc` 之前，先用测试台（`officia-testbench`）拿**真实样本**跑一批，确认还原度满足要求。

## 常见问题

| 现象 | 原因 | 处置 |
|---|---|---|
| 中文变方块 / 空白 | 找不到中文字体 | 设 `fontDirectory`，见 `officia-chinese-font` |
| PDF 有"评估"水印、页数被截断 | 未加载授权且门控已开 | 见 `officia-license`（**不是转换失败**） |
| 抛 `OfficiaException` | 输入为空 / 非 Word 文档 / 加密 doc | 见 `officia-troubleshooting` |
| 大文档 OOM | 输入与排版需整篇在内存 | 用流式输出 + 调 JVM 堆，见 `officia-performance` |
| 版式与 Word 里不完全一致 | 排版为自研引擎，非 Word 逐像素复刻 | 先用测试台实测评估；复杂版式差异属预期 |

## 完整示例：文件 → 文件（最省内存）

```java
import plus.ruoyi.officia.words.OfficiaWords;
import java.io.File;

public class DocxToPdf {
    public static void main(String[] args) {
        File in  = new File("in.docx");
        File out = new File("out.pdf");
        int pages = OfficiaWords.toPdf(in, out);   // 边生成边写盘
        System.out.println("转换完成，" + pages + " 页 → " + out.getAbsolutePath());
    }
}
```

## 在测试台里实测

```bash
mvn -o package && java -jar target/officia-demo-1.0.0.jar
```

面板 **「Words · DOC/DOCX」** 可直接上传 `.doc`/`.docx`，自动识别格式，并对比字节 / 流式两种输出。
对应端点 `/api/words/topdf`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 按数据生成 Word / 批量出合同 | `officia-template` |
| 中文乱码 | `officia-chinese-font` |
| 集成进 Web 接口 | `officia-spring-integration` |
| 大文档慢 / OOM | `officia-performance` |
