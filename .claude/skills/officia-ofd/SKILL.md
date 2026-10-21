---
name: officia-ofd
description: |
  用 OfficiaOfd 处理 OFD（GB/T 33190 国产版式文档）：电子发票解析成结构化数据、
  电子签章验证（SM2 + SM3）、OFD 转 PDF、文本 / 页数 / 元数据 / 附件读取；
  以及用 OfficiaWords.toOfd 把 Word 转成 OFD。

  触发场景：
  - 要从 OFD 电子发票里取发票号码、金额、购销方、明细（报销 / 入账）
  - 要校验 OFD 签章是否有效、文件有没有被改过
  - 要把 OFD 转成 PDF 给不认 OFD 的系统或人看
  - 政务 / 档案场景要求交 OFD，要把 Word 转成 OFD
  - 要抽 OFD 的文字、读元数据或附件

  触发词：OFD、ofd、版式文档、国产版式、电子发票、数电票、全电发票、发票解析、发票识别、验签、签章验证、电子签章、SM2、国密、OFD转PDF、Word转OFD、docx转ofd、GB/T 33190
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# OFD 版式文档（`OfficiaOfd` + `OfficiaWords.toOfd`）

## 概述

OFD（GB/T 33190-2016）是国产版式文档标准，电子发票、政务公文、电子档案普遍使用。officia 覆盖两个方向：

| 方向 | 门面 | 能力 |
|---|---|---|
| **读 OFD** | `OfficiaOfd` | 发票解析、签章验证、转 PDF、文本 / 页数 / 页面尺寸 / 元数据 / 附件 |
| **写 OFD** | `OfficiaWords.toOfd` | Word（docx / doc）→ OFD，与转 PDF 共用排版，两者版面逐字一致 |

纯 JDK 实现，国密 SM2 / SM3 自研，`officia-all` 里已包含，不用另加依赖。

```java
import plus.ruoyi.officia.ofd.OfficiaOfd;
import plus.ruoyi.officia.ofd.invoice.OfdInvoice;
import plus.ruoyi.officia.ofd.sign.OfdSignatureVerification;
import plus.ruoyi.officia.words.OfficiaWords;

OfdInvoice inv = OfficiaOfd.parseInvoice(ofdBytes);
OfdSignatureVerification v = OfficiaOfd.verify(ofdBytes);
byte[] pdf = OfficiaOfd.toPdf(ofdBytes);
byte[] ofd = OfficiaWords.toOfd(docxBytes);
```

## 一、电子发票 → 结构化数据

```java
OfdInvoice inv = OfficiaOfd.parseInvoice(byte[] ofd);
OfdInvoice inv = OfficiaOfd.parseInvoice(File file);
OfdInvoice inv = OfficiaOfd.parseInvoice(InputStream in);   // 不关闭调用方的流

if (inv.isRecognized()) {           // 不是发票时为 false，不抛异常——混杂文件可直接批量喂
    inv.getInvoiceNumber();
    inv.getIssueDate();             // 统一为 yyyy-MM-dd
    inv.getTotalAmount();           // 价税合计，已去掉 ¥ 与千分位
    for (OfdInvoice.Item it : inv.getItems()) { it.getName(); it.getAmount(); it.getTaxRate(); }
    inv.getRawFields();             // 发票里的全部原始字段：路径 → 值
}
```

| 字段（getter） | 含义 |
|---|---|
| `getInvoiceType()` / `getInvoiceCode()` / `getInvoiceNumber()` | 票种 / 发票代码（数电票无，为 null）/ 号码 |
| `getIssueDate()` / `getCheckCode()` / `getMachineNumber()` | 开票日期 / 校验码 / 机器编号 |
| `getBuyerName()` / `getBuyerTaxId()` / `getBuyerAddressPhone()` | 购买方 |
| `getSellerName()` / `getSellerTaxId()` / `getSellerAddressPhone()` / `getSellerBankAccount()` | 销售方 |
| `getAmountExcludingTax()` / `getTaxAmount()` / `getTotalAmount()` / `getTotalAmountInWords()` | 金额 / 税额 / 价税合计 / 大写 |
| `getPayee()` / `getReviewer()` / `getDrawer()` / `getRemark()` | 收款人 / 复核 / 开票人 / 备注 |
| `getItems()` | 明细：`getName` / `getSpecification` / `getUnit` / `getQuantity` / `getUnitPrice` / `getAmount` / `getTaxRate` / `getTaxAmount` |
| `getSources()` / `getRawFields()` / `isEvaluation()` | 实际用到的数据来源 / 原始字段 / 是否评估态 |

数据来源按可信度合并：先读 OFD 附件里的**发票原始 XML**，再读**自定义标引 CustomTags**。
数电票常见字段名已内置别名；不同地区票面仍有差异，**接入前务必用真实票样跑一遍**，缺的字段先从 `getRawFields()` 取。

## 二、签章验证

```java
OfdSignatureVerification v = OfficiaOfd.verify(ofdBytes);
v.isSigned();        // 有没有签章
v.isValid();         // 全部签章都有效
v.getSummary();      // 人能读的一段结论：哪个签名、哪个文件被改
for (OfdSignatureVerification.Signature s : v.getSignatures()) {
    s.getSealName(); s.getSignerName(); s.getSignTime();
    s.isValid(); s.getModifiedFiles(); s.getProblem();
}
v.getUnprotectedFiles();   // 签完后才塞进包里、不受任何签名保护的文件
```

每个签名查三项：受保护文件逐个重算杂凑、签章里的原文杂凑与当前 `Signature.xml` 比对、用证书 SM2 公钥验签名值。
未签章的文件返回 `isSigned()=false`，**不抛异常**。

> ⚠️ **验证 ≠ 信任**：`verify` 只做密码学验证，**不校验证书链、吊销状态与印章制发方**。
> 要判断「是不是真的税务局章」，需结合业务系统的信任根与官方查验渠道——转告用户时不能省略这一句。

## 三、OFD → PDF

```java
byte[] pdf = OfficiaOfd.toPdf(byte[] ofd);
byte[] pdf = OfficiaOfd.toPdf(byte[] ofd, ConvertOptions options);
byte[] pdf = OfficiaOfd.toPdf(File file);
int pages  = OfficiaOfd.toPdf(File inOfd, File outPdf);

OfficiaOfd.OfdToPdfResult r = OfficiaOfd.convertToPdf(ofd, ConvertOptions.defaults().setFontDirectory("/fonts"));
r.getPdf(); r.getPageCount(); r.getElapsedMillis();
r.getWarnings();   // 没能完全还原的内容（如不支持的 JBIG2 编码），其余照常输出
```

- 字按 OFD 给的落点逐个定位，换了本机字体**位置也不变**；复制文字是原文
- 印章外观照画（PNG / JPEG / 矢量 OFD 印章）；超链接、大纲、文档属性带到 PDF
- 发票二维码（JBIG2 通用区域）自研解码，转出后**可直接扫码**

**当前限制**（如实转告）：JBIG2 的符号字典 / 文本区域 / 半调 / MMR 编码暂不支持（记入 `getWarnings()`）；
渐变与底纹按近似纯色；文字裁剪区不生效。**签章转到 PDF 后只是图片，不再有签名效力。**

## 四、文本与其它读取

```java
String text        = OfficiaOfd.extractText(ofd);         // 页间以 \f 分隔
List<String> pages = OfficiaOfd.extractTextByPage(ofd);
int n              = OfficiaOfd.pageCount(ofd);
List<double[]> wh  = OfficiaOfd.pageSizes(ofd);           // 每页 [宽, 高]，单位 mm
Map<String, String> meta  = OfficiaOfd.metadata(ofd);     // 自定义元数据键为 custom.名称
Map<String, byte[]> files = OfficiaOfd.attachments(ofd);
```

## 五、Word → OFD（`OfficiaWords`）

```java
byte[] ofd = OfficiaWords.toOfd(byte[] docx);                       // docx / doc 自动识别
byte[] ofd = OfficiaWords.toOfd(byte[] docx, ConvertOptions options);
byte[] ofd = OfficiaWords.toOfd(InputStream in);
byte[] ofd = OfficiaWords.toOfd(File file);
int pages  = OfficiaWords.toOfd(byte[] docx, OutputStream out);     // 流式，不关闭 out
int pages  = OfficiaWords.toOfd(byte[] docx, ConvertOptions options, OutputStream out);
int pages  = OfficiaWords.toOfd(InputStream in, OutputStream out);
int pages  = OfficiaWords.toOfd(File inDocx, File outOfd);          // 文件 → 文件，最省内存
ConvertResult r = OfficiaWords.convertToOfd(byte[] docx, ConvertOptions options);   // 页数 / 耗时 / 字体替换
```

与 `toPdf` 共用解析、排版与绘制，**同一份 Word 转出的 PDF 与 OFD 版面逐字一致**；字体子集嵌入并带 cmap，
数科、福昕、WPS 都能打开。`ConvertOptions` 的字体目录、图像 dpi 等配置与转 PDF 相同（见 `officia-words`）。

> Word 里的数字签名不会随转换保留（任何格式转换都是如此）；以图片插入的印章照常输出。

## 授权（评估版）表现

| 操作 | 未加载 License 时 | 授权模块码 |
|---|---|---|
| OFD → PDF | 限 30 页 + 评估水印 | `ofd` |
| 文本抽取 | 只返回前 30 页，开头带评估提示；**若是发票**，文字里的号码、金额、税号等同样遮盖（普通公文不受影响） | `ofd` |
| 发票解析 | 字段齐全但关键值部分遮盖（`isEvaluation()=true`），可联调、**不可入账** | `ofd` |
| 附件 `attachments` | **返回空表**（发票附件就是原始 XML，原样给出等于绕过上一条的遮盖） | `ofd` |
| 元数据 `metadata` | 标准项（Title / Author / DocID …）照常；`custom.*` 自定义项的值遮盖（税务系统生成的发票把号码、金额都写在这里） | `ofd` |
| 验签 / 页数 / 页面尺寸 | 不受限 | — |
| Word → OFD | 与 Word → PDF 同口径：限 30 页 + 评估水印；**计入 `words` 模块码**，只授权 `ofd` 不解除 | `words` |

> 边界：**渲染可试用、结构化数据要授权**。OFD → PDF / 图片的画面是真实内容（带评估水印、限 30 页），
> 评估态下人眼可以看清发票；被遮盖的是可直接被程序拿去入账的数据（解析字段、元数据、文本、附件）。

## 完整示例：发票批量入账（验签 + 解析 + 留 PDF 底）

```java
for (File f : dir.listFiles((d, n) -> n.toLowerCase().endsWith(".ofd"))) {
    byte[] ofd = Files.readAllBytes(f.toPath());
    OfdSignatureVerification v = OfficiaOfd.verify(ofd);
    if (!v.isValid()) {                       // 未签章或被改动：不入账，留人工
        log.warn("{} 验签不通过：{}", f.getName(), v.getSummary());
        continue;
    }
    OfdInvoice inv = OfficiaOfd.parseInvoice(ofd);
    if (!inv.isRecognized()) {
        continue;                              // 不是发票
    }
    save(inv.getInvoiceNumber(), inv.getIssueDate(), inv.getSellerName(), inv.getTotalAmount());
    Files.write(Paths.get("archive", inv.getInvoiceNumber() + ".pdf"), OfficiaOfd.toPdf(ofd));
}
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 发票金额、号码带 `*` | 评估态遮盖 | 加载 License（`officia-license`），`ofd` 模块要在授权里 |
| `isRecognized()` 为 false | 不是发票，或票面结构没覆盖到 | 看 `getRawFields()` 有没有内容；有就是字段别名缺，反馈样本 |
| 验签报某文件被改 | 签完后文件真的被改过（含重新打包） | 以原始文件为准，别用转存 / 另存过的副本验 |
| 转出 PDF 的二维码是空白 | 用了暂不支持的 JBIG2 编码 | 看 `convertToPdf(...).getWarnings()` |
| 转出 PDF 的字体和原件不同 | OFD 没内嵌字形、本机缺该字体 | `setFontDirectory` 指到有对应字体的目录（见 `officia-chinese-font`）；字位置不受影响 |
| Word → OFD 只有 30 页且带水印 | 评估态（`words` 模块未授权） | 加载 License |
| `attachments()` 返回空 | 评估态刻意不给附件 | 加载 License；授权后照常返回 |
| 发票的 `extractText` / `metadata()` 里有 `*` | 评估态遮盖（防止绕过发票解析的遮盖） | 加载 License，`ofd` 模块要在授权里 |

## 在测试台里实测

测试台左侧「**OFD · 发票/版式**」面板：上半区 Word → OFD（产物可一键交给下半区继续验证），
下半区上传 `.ofd` 后可转 PDF、解析发票、签章验证、看文本与元数据。批量回归里有一条 Word → OFD → 文本 / PDF 闭环。
详见 `officia-testbench`。

## 相关技能

| 接下来 | 用 |
|---|---|
| Word 转 PDF / 图片、`ConvertOptions` 全表 | `officia-words` |
| 给 PDF 重新签名、PDF 加解密 | `officia-pdf` |
| 加载 License、模块码 | `officia-license` |
| 服务器缺中文字体 | `officia-chinese-font` |
| 不确定该用哪个能力 | `officia-capability-map` |
