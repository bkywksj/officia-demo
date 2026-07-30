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

## 在测试台里实测

面板 **「PDF 工具箱」**：合并·拆分·抽页·删页·旋转·水印·页码·抽文字·抽图片·RC4-128·AES-256·信息，逐项可点。
端点：`/api/pdf/info`、`/merge`、`/split`、`/pages`、`/rotate`、`/watermark`、`/pagenumbers`、`/text`、`/images`、`/encrypt`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 别的格式转 PDF | `officia-capability-map` |
| 中文字体 | `officia-chinese-font` |
| 大 PDF 内存 | `officia-performance` |
| 做成下载接口 | `officia-spring-integration` |
