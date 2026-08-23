---
name: officia-pdf
description: |
  PDF 工具箱（OfficiaPdf）：合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·加密·元数据·页面尺寸，
  以及多步操作的链式编辑器 PdfEditor（一次解析、少一轮序列化）。

  触发场景：
  - 合并 / 拆分 PDF、抽取或删除某几页、旋转页面
  - 给 PDF 加水印、加页码
  - 从 PDF 里提取文字或图片
  - 给 PDF 加密码、读加密 PDF
  - 读 PDF 页数 / 版本 / 标题作者等元数据
  - 要连续做好几步 PDF 操作

  触发词：PDF、合并、拆分、抽页、删页、旋转、水印、页码、抽文字、提取文字、抽图片、加密、解密、口令、密码、AES、元数据、页数、PdfEditor
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# PDF 工具箱（`OfficiaPdf`）

## 概述

`OfficiaPdf` 是纯 PDF 操作入口（不负责"别的格式转 PDF"，那是 Words/Cells/Slides/Imaging 的活）。
两种用法：**单次静态方法**，或**链式 `PdfEditor`**（多步操作首选）。

另外它还提供**反向转换 `toWord()`**：把电子版 PDF 转成可编辑的 Word（见第六节）。

## 一、读取 / 信息

```java
import plus.ruoyi.officia.pdf.OfficiaPdf;

int    pages = OfficiaPdf.pageCount(pdf);
int    pages = OfficiaPdf.pageCount(pdf, "口令");        // 加密 PDF
String ver   = OfficiaPdf.version(pdf);                  // 如 "1.7"
boolean enc  = OfficiaPdf.isEncrypted(pdf);
List<float[]> sizes = OfficiaPdf.pageSizes(pdf);         // 每页尺寸
```

### 元数据

```java
PdfMetadata md = OfficiaPdf.metadata(pdf);
PdfMetadata md = OfficiaPdf.metadata(pdf, "口令");
md.getTitle(); md.getAuthor(); md.getSubject(); md.getKeywords();
md.getCreator(); md.getProducer(); md.getCreationDate(); md.getModDate();
md.get("自定义键");  md.all();   // Map<String,String>

byte[] out = OfficiaPdf.setMetadata(pdf, Map.of("Title", "月度报表", "Author", "财务部"));
```

### 提取内容

```java
String text = OfficiaPdf.extractText(pdf);
String text = OfficiaPdf.extractText(pdf, "口令");           // 加密 PDF
String text = OfficiaPdf.extractText(inputStream);
String text = OfficiaPdf.extractText(new File("in.pdf"));
List<String> byPage = OfficiaPdf.extractTextByPage(pdf);     // 逐页文本
List<byte[]> images = OfficiaPdf.extractImages(pdf);         // 内嵌图片
```

## 二、页面操作

```java
byte[] merged = OfficiaPdf.merge(List.of(pdfA, pdfB, pdfC));       // 合并
OfficiaPdf.merge(List.of(pdfA, pdfB), outputStream);               // 合并直写流
OfficiaPdf.mergeToFile(List.of(pdfA, pdfB), new File("out.pdf"));  // 合并直写文件

List<byte[]> pages = OfficiaPdf.split(pdf);                        // 拆成单页

byte[] some = OfficiaPdf.extractPages(pdf, 0, 2, 4);               // 抽页（0-based）
byte[] rest = OfficiaPdf.removePages(pdf, 1, 3);                   // 删页
byte[] rot  = OfficiaPdf.rotate(pdf, 90);                          // 旋转（度）
```

> 页码索引是 **0-based**（第 1 页 = `0`）。

## 三、水印与页码

```java
byte[] wm = OfficiaPdf.watermark(pdf, "内部资料");
byte[] wm = OfficiaPdf.watermark(pdf, "内部资料", fontTtfBytes);   // 中文必须传 TTF

byte[] pn = OfficiaPdf.addPageNumbers(pdf);
byte[] pn = OfficiaPdf.addPageNumbers(pdf, "第 %d 页");
byte[] pn = OfficiaPdf.addPageNumbers(pdf, "第 %d 页 / 共 %d 页", fontTtfBytes);
```

> 🔴 **中文水印 / 中文页码必须传 `fontTtf`**——PDF 内置字体没有中文字形。见 `officia-chinese-font`（含 demo 里自动探测系统字体的做法）。

## 四、加密

```java
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd");             // 默认 RC4-128
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd", 40);         // RC4-40  (/V1 /R2)
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd", 128);        // RC4-128 (/V2 /R3)
byte[] enc = OfficiaPdf.encrypt(pdf, "userPwd", "ownerPwd", 256);        // AES-256 (/V5 /R6)
byte[] enc = OfficiaPdf.encryptAes256(pdf, "userPwd", "ownerPwd");       // 同上，推荐
```

| `bits` | 算法 | 说明 |
|---|---|---|
| 40 | RC4-40（/V1 /R2） | 兼容老阅读器，**强度弱** |
| 128 | RC4-128（/V2 /R3） | `encrypt(3 参)` 的默认 |
| **256** | **AES-256（AESV3 /V5 /R6）** | **推荐**——现代阅读器通用，强于 RC4 |

> ⚠️ `bits` **只接受 40 / 128 / 256**，其它值抛 `OfficiaException`。别指望传 64 会得到"64 位加密"。

口令语义：

- `userPassword`：**打开所需**的口令。空串 = 无口令可打开，但内容仍加密
- `ownerPassword`：权限口令，**可空**，空则等同 user
- **两个口令都能用于打开文档**（读取与解密时任传其一即可），RC4 与 AES-256 一致

### 解密：加密文档要编辑必须先解密

加密 PDF 只能读（文本/元数据/页数），**不能直接编辑**——水印、页码、合并、删页都要先解密：

```java
byte[] plain = OfficiaPdf.decrypt(enc, "userPwd");        // 导出未加密副本（owner 口令同样可用）
byte[] out   = OfficiaPdf.edit(enc, "userPwd")            // 或直接带口令进入链式编辑
        .keepPages(0, 1)
        .watermark("REVIEWED")
        .encryptAes256("newPwd", "newOwner")              // 需要的话换口令重新加密
        .toBytes();
```

`decrypt` 对未加密文档是安全的恒等操作（口令参数被忽略），所以"不确定来源是否加密"时可以无脑先调它。

## 五、链式编辑器 `PdfEditor`（多步操作首选）

```java
byte[] out = OfficiaPdf.edit(pdf)           // 加密文档用 edit(pdf, password)
    .keepPages(0, 1, 2)                     // 只留前 3 页
    .rotate(90)
    .watermark("机密", fontTtf)
    .pageNumbers("第 %d 页", fontTtf)
    .metadata(Map.of("Title", "季度报告"))
    .encryptAes256("open", "owner")
    .toBytes();
```

全部链式方法：

```java
PdfEditor watermark(String text)                        PdfEditor watermark(String text, byte[] fontTtf)
PdfEditor pageNumbers()                                 PdfEditor pageNumbers(String format)
PdfEditor pageNumbers(String format, byte[] fontTtf)
PdfEditor rotate(int degrees)
PdfEditor keepPages(int... pageIndices)                 PdfEditor removePages(int... pageIndices)
PdfEditor append(byte[] other)                          // 追加另一份 PDF
PdfEditor metadata(Map<String,String> metadata)
PdfEditor encrypt(String user, String owner, int bits)  PdfEditor encryptAes256(String user, String owner)
```

三种收尾：

```java
byte[] b = editor.toBytes();
editor.writeTo(outputStream);       // 直写流
editor.toFile(new File("out.pdf")); // 直写文件
```

> **什么时候用链式**：两步以上就该用——少一轮解析与序列化，代码也更清楚。单步操作用静态方法即可。

## 六、PDF → Word（`toWord`）

把**电子版** PDF 转成可编辑的 `.docx`。

```java
byte[] docx = OfficiaPdf.toWord(pdf);
byte[] docx = OfficiaPdf.toWord(pdf, "口令");                 // 加密 PDF
byte[] docx = OfficiaPdf.toWord(new File("in.pdf"));
byte[] docx = OfficiaPdf.toWord(inputStream);
OfficiaPdf.toWord(new File("in.pdf"), new File("out.docx"));  // 直接写盘
```

### 选项 `WordConvertOptions`

```java
import plus.ruoyi.officia.pdf.word.WordConvertOptions;

byte[] docx = OfficiaPdf.toWord(pdf, WordConvertOptions.defaults()
    .setPassword("open")                  // 加密 PDF 口令
    .setExtractImages(true)               // 嵌入图片（默认 true）
    .setExtractVectors(true)              // 表格线 / 底纹（默认 true）
    .setRasterizeVectorPatterns(true));   // 公章 / 艺术字光栅化（默认 true）
```

| 选项 | 默认 | 关掉的效果 |
|---|---|---|
| `extractImages` | `true` | 产物无图片，体积显著变小 |
| `extractVectors` | `true` | 产物无表格线 / 底纹 |
| `rasterizeVectorPatterns` | `true` | 公章、签名、艺术字轮廓**不出现**在产物里 |

### 模式：`TEXTBOX`（当前唯一可用）

`WordConvertOptions.Mode` 有两个值，取舍是**明摆着的**、不会悄悄替你选：

| 模式 | 含义 | 状态 |
|---|---|---|
| `TEXTBOX`（默认） | 每个视觉文本块 = 一个绝对定位的文本框，**版式最像原文**；代价是产物在 Word 里编辑不便 | ✅ 可用 |
| `FLOW` | 推断段落 / 标题 / 表格，还原成可自由编辑的流式文档 | ❌ **尚未实现，传入即抛异常**（不会降级成 TEXTBOX） |

### 能还原什么

文字 · **加粗** · 字体名 · 逐行缩进（含中文首行缩进 2 字符）· 图片（含透明通道）·
表格线 / 单元格底纹 · **表格单元格切分**（靠竖线判断"这两段文字不在同一个格子里"）·
公章 / 艺术字（光栅化成位图）。

### 明确做不到的（不要向客户承诺）

| 做不到 | 说明 |
|---|---|
| **扫描件 / 图片型 PDF 走 `toWord`** | 无文字层，`toWord` 会**明确抛异常**并说明需要 OCR，**不会**返回空白文档。这不是缺陷——扫描件有专门的路径 `ScannedPdfConverter`（见下），别硬塞进 `toWord` |
| 产物是"真表格" | 表格线是逐条画出来的形状，位置精确但**不能插入行列**；真表格需要 `FLOW` 模式 |
| 虚线样式 | 虚线会被画成实线 |

### 扫描件走 `ScannedPdfConverter`（`officia-ocr` 模块）

```java
import plus.ruoyi.officia.ocr.recog.BuiltinModels.Language;
import plus.ruoyi.officia.ocr.scan.ScannedPdfConverter;

// 最简：整份扫描件 → 可编辑 DOCX（语种必填，见下方红字）
byte[] docx = new ScannedPdfConverter()
        .language(Language.CHINESE)
        .toWord(pdfBytes);

// 老书常见版面：扫描时页面横放、两页并排
byte[] docx = new ScannedPdfConverter()
        .language(Language.CHINESE)
        .rotate(ScannedPdfConverter.Rotation.CLOCKWISE_90)
        .splitFacingPages(true)   // 按最大空白带定位中缝，不是对半切（装订线未必居中）
        .minConfidence(0.01)      // 滤掉插图/印章/表格线被误当文本行的噪声
        .toWord(pdfBytes);
```

> 🔴 **`.language(...)` 是必填的，不填直接抛异常**。OCR 不做语种自动判别，
> 而 `OcrOptions.defaults()` 的默认值是**英文**——中文扫描件走英文模型时，
> CTC 在固定字符集上永远给某个类别，于是吐出满篇拉丁字母，**不报错、置信度还不低**。
> 上游踩过：整本 183 页书就这么静默产出 25 万字噪声。所以现在改成响亮失败。

| 能力 | 状态（2026-08-15 实测） |
|---|---|
| 中文识别 | ✅ **已支持**（自训 CRNN-lite，3885 类，随包内置 int8 权重 10.85 MB） |
| 现代扫描件（清晰印刷） | 实测基本逐字准确 |
| 1990 年代铅印扫描书 | 召回 69.6%——**能读懂大意，但不能当作可直接交付的转录** |
| 留出字体 CER | 0.0071（7 款训练未见过的字体） |
| 标点符号 | 书名号《》、引号“”、省略号…、破折号—、间隔号· 均可正确识别（183 页真实书实测，书名号左右各 343 完美配对） |

**能力边界（如实告知客户，不要夸大）**：只做「行 → 段落」的线性还原，
**不**还原表格、多栏、图文混排——扫描件的版面分析属另一层能力，尚未实现。
模型也**没有「认不出」这个输出**：CTC 在固定字符集上永远给某个类别，
超出字符集的输入会硬凑结果，**且置信度反而更高**，所以
`minConfidence` 能滤掉纯噪声区域（实测 0.0001 量级），但滤不掉"认错字"。

```java
// 纯文本层判断仍用 toWord：它抛异常是在告诉你"这份该走 OCR"
try {
    byte[] docx = OfficiaPdf.toWord(pdf);
} catch (OfficiaException e) {
    // 消息里已包含"没有文字层""需要 OCR"——此时改走 ScannedPdfConverter
    byte[] docx2 = new ScannedPdfConverter().language(Language.CHINESE).toWord(pdf);
}
```

## 完整示例：批量合并 + 加水印 + 加密归档

```java
import plus.ruoyi.officia.pdf.OfficiaPdf;
import java.nio.file.*;
import java.util.*;

public class ArchivePdf {
    public static void main(String[] args) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        for (String f : List.of("a.pdf", "b.pdf", "c.pdf")) {
            parts.add(Files.readAllBytes(Path.of(f)));
        }
        byte[] font = Files.readAllBytes(Path.of("C:/Windows/Fonts/simhei.ttf")); // 中文水印必需

        byte[] out = OfficiaPdf.edit(OfficiaPdf.merge(parts))
            .watermark("内部资料 请勿外传", font)
            .pageNumbers("第 %d 页 / 共 %d 页", font)
            .metadata(Map.of("Title", "归档合集"))
            .encryptAes256("open2026", "owner2026")
            .toBytes();

        Files.write(Path.of("archive.pdf"), out);
        System.out.println("归档完成，" + OfficiaPdf.pageCount(out, "open2026") + " 页");
    }
}
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 中文水印 / 页码是方块或空白 | 没传 `fontTtf` | 传中文 TTF 字节，见 `officia-chinese-font` |
| 读加密 PDF 抛异常 | 没传口令 | 用 `extractText(pdf, pwd)` / `pageCount(pdf, pwd)` / `metadata(pdf, pwd)` |
| 抽页抽错了 | 索引以为是 1-based | **0-based**：第 1 页传 `0` |
| 加密后老阅读器打不开 | 用了 AES-256 | 兼容优先降到 128；安全优先保持 256 |
| `extractImages` 返回空 | PDF 里是矢量图形不是位图 | 属预期 |
| 合并后体积很大 | 各源 PDF 的字体/图片资源叠加 | 属预期；需要精简请先在源头压 |
| 输出带评估水印 | 未授权 + 门控开 | `officia-license`（与你自己加的 `watermark` 无关） |
| `toWord` 抛"没有文字层" | 扫描件 / 图片型 PDF | 属**预期行为**，不是 bug；改走 `ScannedPdfConverter`（见第六节），不必再找外部 OCR 工具 |
| `toWord` 抛"FLOW 模式尚未实现" | 传了 `Mode.FLOW` | 一期只有 `TEXTBOX`；刻意抛异常而非静默降级 |
| 转出的 Word 里表格不能插入行列 | Textbox 模式把表格线画成形状 | 属既定取舍，见第六节 |

## 在测试台里实测

面板 **「PDF 工具箱」**：合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·RC4-128·AES-256·信息·**转 Word**，逐项可点。
「转 Word」下方有三个开关（嵌入图片 / 表格线底纹 / 图案光栅化）与加密口令输入框，可现场对比开关效果。
端点：`/api/pdf/info`、`/merge`、`/split`、`/pages`、`/rotate`、`/watermark`、`/pagenumbers`、`/text`、`/images`、`/encrypt`、`/toword`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 别的格式转 PDF | `officia-capability-map` |
| 中文字体 | `officia-chinese-font` |
| 大 PDF 内存 | `officia-performance` |
| 做成下载接口 | `officia-spring-integration` |
