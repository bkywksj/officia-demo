---
name: officia-performance
description: |
  Officia 的性能与内存实践：流式 API 到底省什么（以及省不了什么）、CfbLimits 内存安全边界的
  真实默认值与调整、超时保护、线程安全边界、并发调用注意事项，以及官方未做压测这一诚实事实。

  触发场景：
  - 转换大文档慢 / OOM / 堆内存爆了
  - 想知道流式 API 能省多少内存
  - 报"CFB … 超过安全上限"
  - 多线程 / 高并发调用 officia 安全吗
  - 要给转换加超时保护

  触发词：性能、慢、内存、OOM、堆、大文件、大文档、流式、并发、多线程、线程安全、超时、限流、CfbLimits、安全上限、压测
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 性能与内存

## 🔴 先说诚实边界

`../officia/status.json` 的 `not_verified` 明确记录：

> **大文档吞吐与高并发未系统压测**（PLAN 7.3 未启动）；`CfbLimits` 默认值是**内存安全边界**，**不是产品容量承诺**。

所以：**不要向用户承诺具体的 QPS / 最大文件大小 / 吞吐数字**。要给结论，就在目标环境用真实文档实测。

## 一、流式 API：省什么，省不了什么

`OfficiaWords` 提供输出流版本：

```java
int toPdf(byte[] docx, OutputStream out)                          // 返回页数
int toPdf(byte[] docx, ConvertOptions options, OutputStream out)  // options 可传 null
int toPdf(InputStream in, OutputStream out)
int toPdf(File inDocx, File outPdf)                               // 边生成边写盘
```

源码 Javadoc 的诚实说法（不要夸大）：

| 省得掉 | 省不掉 |
|---|---|
| ✅ 整份输出 PDF 的中间 `byte[]` | ❌ **输入 docx 需整篇在内存** |
| ✅ 一次二次拷贝 | ❌ **排版过程需整篇在内存** |

> 结论：流式主要降低**输出侧**峰值内存。输入与排版的内存占用是**格式与排版的本质**，不是这个方法能优化的。

**什么时候一定要用流式**：输出 PDF 很大、直接写文件、直接写 HTTP 响应。

```java
// Web 下载：不要先 toPdf() 拿 byte[] 再写出去，直接流式
try (OutputStream os = response.getOutputStream()) {
    int pages = OfficiaWords.toPdf(docxBytes, os);
}
```

```java
// 文件转文件：最省内存的形态
int pages = OfficiaWords.toPdf(new File("in.docx"), new File("out.pdf"));
```

`OfficiaPdf` 也有流式收尾：

```java
OfficiaPdf.merge(pdfs, outputStream);
OfficiaPdf.mergeToFile(pdfs, new File("out.pdf"));
OfficiaPdf.edit(pdf).watermark("X").writeTo(outputStream);
OfficiaPdf.edit(pdf).watermark("X").toFile(new File("out.pdf"));
```

## 二、`CfbLimits`：内存安全边界（`.doc` 解析）

处理 `.doc` 时若报 `CFB … 超过安全上限`，是**保护机制被触发**，不是 bug。

`CfbLimits.defaults()` 的真实默认值（核实自源码）：

| 项 | 默认值 |
|---|---|
| `maxInputBytes` | **256 MiB** |
| `maxStreamBytes` | 256 MiB |
| `maxCumulativeStreamBytes` | 512 MiB |
| `maxSectorCount` | 1,048,576 |
| `maxChainSectors` | 1,048,576 |
| `maxDirectoryEntries` | 1,000,000 |
| `maxDirectoryTreeDepth` | 1,024 |
| `maxFatSectorCount` | 16,384 |
| `maxDifatSectorCount` | 4,096 |
| `maxMiniFatSectorCount` | 16,384 |

源码注释的定位说明：

> 这些值是内存安全边界，不是对产品最大容量的承诺。**调用方可为可信的大文件显式提供更高上限**；解析器始终还会受实际输入字节数和 MS-CFB 结构计数的双重约束。

> ⚠️ 处理**不可信来源**的文档时**不要放宽**这些上限——它们是防御恶意构造文档（解压炸弹式的资源耗尽）的第一道防线。

### 其它格式的安全上限（1.1.6 起，均可用系统属性调整，≤ 0 表示不限）

| 系统属性 | 默认 | 作用 |
|---|---|---|
| `officia.zip.maxRatio` | `100` | docx / xlsx / pptx 解压总量超过 64 MB 后，若超过文件本身的这么多倍即判为 ZIP 炸弹。真实 Office 文档约 9～25 倍（实测 43 MB 的 xlsx 解压 1.08 GB，不受影响） |
| `officia.zip.maxEntryMB` / `officia.zip.maxTotalMB` / `officia.zip.maxEntries` | `1024` / `2048` / `100000` | 单个部件 / 全包解压后大小、部件数 |
| `officia.image.maxPixels` | `160000000` | 图片按文件头宽 × 高超过它就不解码（按「这张图画不出、其余照常」处理）；PDF 转图片时整页超过它则等比降低分辨率 |
| `officia.pdf.maxInflateBytes` | 256 MiB | PDF 单个流解压上限（Flate、LZW、RunLength 共用） |

另有不可调的结构上限：PDF 数组 / 字典嵌套 256 层、Word 表格 / 文本框嵌套 64 层、邮件 multipart 嵌套 32 层——
超出时抛 `OfficiaException` 说明结构异常，而不是 `StackOverflowError`。1.1.5 及以前这几项都没有上限。

## 三、超时保护

```java
ConvertOptions opts = ConvertOptions.defaults()
    .setTimeoutMillis(30_000);     // 默认 0 = 不限制
byte[] pdf = OfficiaWords.toPdf(docx, opts);
```

**建议**：任何处理用户上传文档的在线接口都设超时，避免单个畸形文档拖死线程。

| 版本 | 行为 |
|---|---|
| **1.1.6 起** | 生效。超时抛 `ConversionTimeoutException`（`OfficiaException` 子类，可单独 catch 区分「超时」与「文档有问题」）。协作式检查：排版逐块、表格逐行、逐页绘制时判断，不另起线程；单张超大图片解码这类原子步骤不会被中途打断，实际耗时可能略超设定值 |
| **1.1.5 及以前** | 🔴 **此选项不生效**（设了也不会中断转换）。需要超时保护的话，只能在你这一侧用有界线程池限制并发 + 接口层超时返回；注意那个工作线程会继续跑完 |

作用范围：Word / Excel / PPT / Markdown / 邮件 → PDF、Word → OFD / 图片、OFD → PDF、编辑器保存 PDF / 出图。
PDF 转 Word、文本抽取、发票解析、验签不受它约束。

## 四、线程安全边界

| 组件 | 状态 | 并发结论 |
|---|---|---|
| `OfficiaWords` / `OfficiaCells` / `OfficiaSlides` / `OfficiaPdf` / `OfficiaBarCode` / `OfficiaImaging` / `OfficiaEmail` | 全静态方法，转换器为**无可变实例状态**的单例字段 | 可并发调用 |
| `ConvertOptions` | 普通可变对象 | **每次调用新建**，不要跨线程共享同一个实例 |
| `PdfEditor` | 链式可变构建器 | **不要跨线程共享**，一个线程用一个 |
| `OfficiaLicense` | **有状态、进程全局**（对齐 Aspose 语义） | 启动时加载一次；**不要**每请求 `setLicense`；进程内只能有一份授权态 |

🔴 **1.1.5 及以前的两处并发缺陷（1.1.6 已修）**，用旧版的客户需要在自己这一侧规避：

| 调用 | 问题 | 1.1.5 的规避 |
|---|---|---|
| `OfficiaWords.markdownToDocx` | 内部 docx 写出器是静态共用的有状态单例，**并发调用会把一份文档的内容写进另一份**（实测 8 线程必现） | 对这个方法加锁串行调用 |
| `OfficiaEditor.save(json, EditorFormat.DOCX)` | 同上 | 同上 |
| 含 emoji / 冷僻符号的 Excel、PPT、邮件、OFD 转换 | 进程级字体缓存里的一张字形回退表不是线程安全的（代码审计发现，真实语料并发回归中**未观察到**出错） | 风险低；高并发服务建议升级 |

1.1.6 的验证：643 份真实文档（1161 项转换）8 线程并发与串行**逐字节一致**，并发单测覆盖上面两处。

安全的并发写法：

```java
// ✅ 每次新建 options
private static byte[] convert(byte[] docx) {
    ConvertOptions opts = ConvertOptions.defaults()
        .setFontDirectory(FONT_DIR)
        .setTimeoutMillis(30_000);
    return OfficiaWords.toPdf(docx, opts);
}

// ❌ 不要这样：静态共享可变 options
// private static final ConvertOptions SHARED = ConvertOptions.defaults();
```

## 五、高并发场景的工程建议

### 单机参考数字（不是容量承诺）

2026-10-21 用 643 份真实文档（1161 项转换）在一台 i9-13900HX 上量过一次，**数字只对这台机器成立**，
用来判断数量级，定堆与并发前请拿你自己的样本重测：

| 操作 | p50 耗时 | p90 耗时 | 峰值堆 p50 / p90 / 最大 |
|---|---|---|---|
| Word → PDF | 0.46 s | 1.5 s | 0.70 / 0.86 / 2.4 GB |
| Word → OFD | 0.17 s | 0.74 s | — |
| Excel → PDF | 0.98 s | 7.3 s | 1.0 / 2.5 / 5.7 GB（2966 页的表） |
| PPT → PDF | 0.37 s | 5.0 s | 0.70 / 0.86 / 1.7 GB |
| OFD → PDF | 0.29 s | 0.77 s | 0.71 / 0.72 / 0.73 GB |

- 峰值堆**含进程常驻的字体缓存（约 650 MB，首次转换时加载系统字体）**，单份文档自身的增量约为表中数字减去它；
- 8 线程并发总耗时是串行的 1/7.3；这台是大小核混合 CPU，线程再多收益很小，按物理大核数定并发更合适；
- 大 Excel 是内存大户，并发处理大表时按「并发数 × 2.5 GB」量级预留堆，或单独排队。

officia 未做系统压测，所以**保护措施要放在你这一侧**：

```java
// 1) 用有界线程池 + 有界队列限制并发转换数，避免内存被同时进行的转换叠爆
ExecutorService pool = new ThreadPoolExecutor(
    4, 4, 60L, TimeUnit.SECONDS,
    new ArrayBlockingQueue<>(50),
    new ThreadPoolExecutor.CallerRunsPolicy());

// 2) 入口限制文件大小
if (upload.length > 50 * 1024 * 1024) {
    throw new IllegalArgumentException("文件过大");
}

// 3) 每次转换都设超时
ConvertOptions opts = ConvertOptions.defaults().setTimeoutMillis(30_000);

// 4) 大任务异步化：接口只收文件返回任务号，后台转完再取
```

JVM 侧：

```bash
java -Xmx2g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/app.hprof -jar app.jar
```

> 内存估算的经验法则：**峰值 ≈ 输入文档大小 × 若干倍**（排版 IR + 页面模型），具体倍数因文档结构差异很大——用你的真实样本压一遍再定堆大小，别照抄别人的数字。

### 图多的文档：先看 `maxImageDpi`

产物体积异常大、转换又慢时，先怀疑**图片按原始像素嵌入**——素材图的像素数常远超它在页面上占的面积
（实测一张 8684×8684 的底图只画到 724pt 宽，等于 863 dpi）。`ConvertOptions.maxImageDpi` 默认 150，
会按显示面积降采样；若被显式设成 `0`（不降采样），图多的文档体积和耗时都会成倍上去。

实测同一份 143 页设计模板（图像占产物 98%）：

| `maxImageDpi` | 产物 | 耗时 | 内存峰值 |
|---|---|---|---|
| `0`（不降采样） | 222 MB | 140 s | 3.0 GB |
| `150`（默认） | 105 MB | 62 s | 1.7 GB |

肉眼看不出差别。只有高精度印刷 / 产物要二次编辑时才关它。JPEG 源图始终字节直通，不受该项影响。

## 六、自己做基准测试

`convert(...)` 返回的 `ConvertResult` 自带耗时，做基准最方便：

```java
import plus.ruoyi.officia.words.OfficiaWords;
import plus.ruoyi.officia.engine.api.ConvertOptions;
import plus.ruoyi.officia.engine.api.ConvertResult;

public class Bench {
    public static void main(String[] args) throws Exception {
        byte[] docx = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("sample.docx"));
        ConvertOptions opts = ConvertOptions.defaults();

        for (int i = 0; i < 3; i++) OfficiaWords.convert(docx, opts);   // 预热（JIT）

        long peakBefore = used();
        for (int i = 0; i < 10; i++) {
            ConvertResult r = OfficiaWords.convert(docx, opts);
            System.out.println(r.getPageCount() + " 页, " + r.getCostMillis() + " ms, "
                             + r.getData().length + " 字节");
        }
        System.out.println("堆增量约 " + (used() - peakBefore) / 1024 / 1024 + " MB");
    }
    static long used() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }
}
```

> 测的是**你的文档 + 你的环境**——这才是有意义的数字。

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 单次转换 OOM | 文档大 / 堆太小 | 加 `-Xmx`；输出侧改流式；先用小文件确认基线 |
| 并发时 OOM | 多个转换同时占内存 | 有界线程池限并发数（见上） |
| `CFB … 超过安全上限` | 触发 `CfbLimits` | 可信文件可提高上限；不可信来源**不要**放宽 |
| 某个文档卡住很久 | 结构复杂或畸形 | 设 `setTimeoutMillis`；留样本反馈 |
| 首次调用特别慢 | JVM 类加载 + JIT 预热 | 基准测试要预热；服务启动时可做一次暖机转换 |
| 多线程结果串了 | 共享了 `ConvertOptions` / `PdfEditor` | 改为每次新建 |

## 相关技能

| 接下来 | 用 |
|---|---|
| 报了具体异常 | `officia-troubleshooting` |
| Web 接口怎么写 | `officia-spring-integration` |
| 流式方法清单 | `officia-words` / `officia-pdf` |
