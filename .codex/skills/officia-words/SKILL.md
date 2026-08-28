---
name: officia-words
description: |
  用 OfficiaWords 把 Word 转成 PDF：docx / doc（自动识别）、四种入参形态、流式转换省内存、
  取页数与耗时、ConvertOptions 配置（纸张 / 字体目录 / 字体嵌入 / 图像降采样 / 超时）。
  也包含 Markdown → docx / PDF（Markdown 是 Words 的一种输入格式）。

  触发场景：
  - Word 转 PDF、docx 转 PDF、doc 转 PDF
  - Markdown 转 Word、md 转 docx、md 转 PDF
  - 要知道转换出来几页、耗时多少
  - 大文档转换想省内存 / 直接写进 HTTP 响应流
  - 要改纸张大小、指定字体目录
  - .doc 老格式能不能转、有什么限制

  触发词：Word、docx、doc、转PDF、word转换、文档转换、toPdf、流式、页数、耗时、ConvertOptions、纸张、A4、图像降采样、maxImageDpi、PDF体积、Markdown、md、markdownToDocx、markdownToPdf、md转word、md转docx、md转pdf、转图片、toImages、一页一张、文档预览、在线预览、PNG、DPI、ImageRenderOptions
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# Word → PDF（`OfficiaWords`）

## 概述

`OfficiaWords` 是 Word 处理的唯一入口，三类能力：

1. **转换**：`toPdf` / `convert` —— docx、doc 都走同一入口，**自动识别格式**（本技能）
2. **Markdown 输入**：`markdownToDocx` / `markdownToPdf` —— Markdown 是 Words 的一种输入格式，不是独立模块（本技能）
3. **模板填充 / 邮件合并**：`fillTemplate*` —— 见 `officia-template`

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

### 转图片（一页一张）

```java
List<byte[]> toImages(byte[] docx)                                          // 默认 96 DPI PNG
List<byte[]> toImages(byte[] docx, ConvertOptions options, ImageRenderOptions image)
List<byte[]> toImages(InputStream in)                                       // 不主动关闭
List<byte[]> toImages(File file)
```

**用途是文档在线预览**：图片在任何浏览器与移动端 webview 里都能直接 `<img>` 显示，
不依赖 PDF 阅读器。返回的顺序**就是页码顺序**。

```java
List<byte[]> pages = OfficiaWords.toImages(docx);       // 一页一张 PNG

// 指定 DPI 与格式
List<byte[]> jpgs = OfficiaWords.toImages(docx, null,
        ImageRenderOptions.defaults().dpi(150).format("jpg"));
```

`ImageRenderOptions`（`plus.ruoyi.officia.render.image`）：

| 方法 | 默认 | 说明 |
|---|---|---|
| `dpi(int)` | 96 | 取值 **36–600**，越界抛异常不静默降级。600dpi 下 A4 单页约 4958×7017 像素 |
| `format(String)` | `png` | `png` / `jpg` / `jpeg`。⚠️ JPEG 无透明通道且有压缩伪影，文档预览建议 PNG |
| `backgroundRgb(int)` | 白 | 页面底色。位图没有"白纸"这层默认，不铺底色会得到全黑 |
| `antiAlias(boolean)` | true | 关掉只在逐像素比对的测试场景有意义 |
| `watermark(String)` / `watermark(WatermarkOptions)` | 无 | 自定义水印，**烧进像素**，另存后仍在 |

**自定义水印** `WatermarkOptions`：

```java
// 最简：对角灰字
OfficiaWords.toImages(docx, null,
        ImageRenderOptions.defaults().watermark("机密 · 仅供内部"));

// 平铺：防止截图裁掉水印
OfficiaWords.toImages(docx, null, ImageRenderOptions.defaults()
        .watermark(WatermarkOptions.text("张三 · 2026-08-28 · 禁止外传")
                .tile(true)
                .fontSizePt(13)
                .opacity(0.13f)));
```

| 方法 | 默认 | 说明 |
|---|---|---|
| `text(String)` | 必填 | 空字符串抛异常 |
| `fontSizePt(float)` | `0`=自动 | 自动时：单个按页宽 1/14，平铺按 1/40。上限 400 |
| `colorRgb(int)` | `0x808080` 灰 | 0xRRGGBB |
| `opacity(float)` | `0.15` | 0–1，越界抛异常 |
| `rotationDegrees(float)` | `-38` | 逆时针为正；`0` 为水平 |
| `tile(boolean)` | `false` | true=整页平铺。**用途是防截图裁剪**——单个居中水印很容易被截掉 |
| `fontFamily(String)` | 自动 | 不指定时按水印文字走**字形级回退**挑字体；中文水印无需操心 |

> ℹ️ **水印为什么由渲染器画，而不是出图后叠**：officia 内置的中文兜底字体在库内部，
> 调用方拿不到。若走"出图后调 `OfficiaImaging.textWatermark`"，你必须自备中文 TTF，
> 否则水印是一片方框。渲染期画还让水印坐标按 **pt** 给（换 DPI 不用重算）、
> 省一轮编解码（JPEG 不会二次压缩）。
>
> 若你要给**非 officia 产出的图片**加水印，那才用 `OfficiaImaging.textWatermark`
> （注意它用 AWT 默认字体、不能指定字体文件）。

> ℹ️ **体积参考**（实测 A4 三页合同，含表格与中英混排）：96dpi 约 109 KB/页、
> 150dpi 约 198 KB/页；同内容 PDF 全文 145 KB。**图片按页计费体积远大于 PDF**，
> 在线预览要考虑懒加载。

> ⚠️ **尚未覆盖的图元**（如实标注，勿当已支持）：图案填充的真实平铺（当前按等效纯色近似）、
> 下划线 / 删除线 / 高亮、行内图片、图片旋转裁剪变换、水平分隔线。
> 文本、纯色填充、表格边框、内嵌图片、定位文本框已覆盖。

> ℹ️ **自定义水印**见下方 `WatermarkOptions`；它与未授权评估水印互不影响——
> 评估水印永远画在最上层，盖不掉。

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

## Markdown → docx / PDF（`markdownToDocx` / `markdownToPdf`）

Markdown 是 Words 的**一种输入格式**（对齐 Aspose.Words 把 Markdown 当 LoadFormat 的做法），不是独立模块——不用引新依赖，还是 `officia-words`。

```java
byte[] docx = OfficiaWords.markdownToDocx("# 标题\n\n正文**加粗**");
byte[] pdf  = OfficiaWords.markdownToPdf ("# 标题\n\n正文**加粗**");
```

### 全部方法（核实自 `OfficiaWords` 源码）

```java
// → docx（不排版，分页交给 Word 自己算）
byte[] markdownToDocx(String markdown)
byte[] markdownToDocx(byte[] markdown)     // 编码自动判定
byte[] markdownToDocx(File file)
byte[] markdownToDocx(InputStream in)      // 不主动关闭调用方的流

// → PDF（由排版引擎定版）
byte[] markdownToPdf(String markdown)
byte[] markdownToPdf(String markdown, ConvertOptions options)
byte[] markdownToPdf(byte[] markdown)
byte[] markdownToPdf(File file)
byte[] markdownToPdf(InputStream in)
ConvertResult convertMarkdown(String markdown, ConvertOptions options)   // 要页数 / 耗时

// → PDF 流式（省内存，返回页数）
int markdownToPdf(String markdown, OutputStream out)                     // 不关闭 out
int markdownToPdf(String markdown, ConvertOptions options, OutputStream out)
int markdownToPdf(File inMarkdown, File outPdf)                          // 边生成边写盘
```

**编码自动判定**（`byte[]` / `File` / `InputStream` 三种入参）：先认 BOM，无 BOM 则严格试解 UTF-8，失败退到 GBK（中文 Windows 记事本存盘的 .md 常见）。已知编码时更推荐自己解码后调 `String` 版。

### 支持的语法

标题（ATX `#` 与 Setext 下划线，**带大纲级别** → Word 导航窗格 / PDF 书签）、段落（中文软换行不插空格、西文插空格；行尾两空格或反斜杠为硬换行）、有序无序**任意层级嵌套列表**、围栏代码块（``` 与 ~~~）、引用块（含嵌套与惰性延续）、GFM 表格（`:---:` 三种对齐）、分隔线、YAML front matter（`title`/`author`/`subject`/`keywords`/`lang` → 文档属性）、**加粗 / 斜体 / 删除线 / 行内代码 / 链接 / 自动链接 / 反斜杠转义**。

### 诚实边界（务必如实转告用户）

| 项 | 状态 |
|---|---|
| **→ PDF 的版式** | ✅ 缩进、行距、表格边框、代码块外框**当下即可见**（PDF 由本引擎排版定版） |
| **→ docx 的版式** | ⚠️ 段落缩进 / 边框 / 表格结构**暂不体现**——IR 里已正确表达，但 docx 写侧当前只写字符级样式（表格会被平铺为段落）。文字与字符样式（加粗 / 斜体 / 字号 / 等宽字体 / 底纹）完整。写侧补齐后自动生效，**调用方代码无需改** |
| 列表编号 | ⚠️ 展平为文本前缀 + 悬挂缩进（不是 Word 自动编号） |
| 图片 `![]()` | ⚠️ 降级为「[图片] 替代文字」+ 指向原地址的链接（不下载外链图，文字与地址不丢） |
| 缩进代码块（4 空格） | ❌ 不支持，按普通段落处理（与列表嵌套缩进判定冲突；用围栏写法 ``` ） |
| 引用式链接 `[x][ref]` / 脚注 / HTML 块 | ❌ 按字面文本输出（不丢字） |
| 强调的交叉嵌套 | ⚠️ 用就近配对而非 CommonMark 的 delimiter run 栈；`*a **b* c**` 式写法有分歧（常规文本一致） |

> 选型建议：**要成品直接看的用 `markdownToPdf`**（版式完整）；要交付可继续编辑的文稿再用 `markdownToDocx`（当前版式较素）。

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
| `toImages` 出的图带对角水印、只有 30 页 | 同上——**图片与 PDF 同一套门控口径** | 见 `officia-license`；加载授权后即完整输出 |
| `toImages` 返回的图片比预期大很多 | 位图按页计费体积，与 PDF 不是一个量级 | 属预期。降 DPI 或改 jpg；在线预览建议懒加载 |
| `toImages` 抛"DPI 越界" | 传了 &lt;36 或 &gt;600 | 刻意抛异常而非静默钳位——600dpi 下 A4 单页已近 140MB，再高会撑爆堆 |
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
