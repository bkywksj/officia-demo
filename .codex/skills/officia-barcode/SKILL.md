---
name: officia-barcode
description: |
  用 OfficiaBarCode 生成条码与二维码：Code128 / Code39 / Code93 / EAN-13 / EAN-8 / UPC-A / ITF-14 / QR，
  各码制的输入约束与校验位规则、尺寸参数、QR 四档纠错、带文字标注版、PNG 与 BufferedImage 两种输出。

  触发场景：
  - 生成二维码 / 条形码 / 商品码 / 物流码
  - EAN-13、UPC-A 要传几位、校验位怎么办
  - 二维码扫不出来 / 太小 / 要更高容错
  - 条码下面要带数字标注
  - 要拿到 BufferedImage 再加工

  触发词：条码、条形码、二维码、QR、Code128、Code39、Code93、EAN、EAN-13、UPC、ITF、ITF-14、纠错、校验位、码制、扫码
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 条码 / 二维码（`OfficiaBarCode`）

## 概述

一行出图，返回 **PNG 字节**（或 `BufferedImage`）。基于 JDK `javax.imageio`，零第三方。

```java
import plus.ruoyi.officia.barcode.OfficiaBarCode;

byte[] qr   = OfficiaBarCode.qrPng("https://officia.ruoyi.plus");
byte[] c128 = OfficiaBarCode.code128Png("ABC-12345");
```

## 🔴 码制选型与输入约束（核实自源码 Javadoc）

| 码制 | 方法前缀 | 输入约束 | 校验位 |
|---|---|---|---|
| **Code128** | `code128` | 文本，**ASCII 32..126** | 内部自动 |
| **Code39** | `code39` | 文本，不区分大小写，Code39 字符集 | 内部自动 |
| **Code93** | `code93` | 文本，不区分大小写，Code93 字符集 | 内部自动 |
| **EAN-13** | `ean13` | **12 位**（自动补校验位）或 **13 位**（校验位须正确）纯数字 | 12 位时自动补 |
| **EAN-8** | `ean8` | **7 位**（自动补）或 **8 位**（须正确）纯数字 | 7 位时自动补 |
| **UPC-A** | `upca` | **11 位**（自动补）或 **12 位**（须正确）纯数字 | 11 位时自动补 |
| **ITF-14** | `itf14` | **13 位**（自动补）或 **14 位**（须正确）纯数字 | 13 位时自动补 |
| **QR** | `qr` | 任意文本；数字/ASCII 走高密度模式，其它走 UTF-8 字节模式，**支持中文** | — |

> 💡 **省心用法**：EAN/UPC/ITF 传"不含校验位"的位数（12/7/11/13），让 officia 自动补——传全长时校验位算错会直接失败。

## 三种输出形态

每个码制都有三个方法：

```java
byte[]        code128Png(String text)                                   // PNG，默认参数
byte[]        code128Png(String text, int moduleWidth, int heightPx, int quietModules)
BufferedImage code128Image(String text, int moduleWidth, int heightPx, int quietModules)
```

**带文字标注版**（条码下方标数字/文本），一维码专属：

```java
code128PngText(text)   code39PngText(text)   code93PngText(text)
ean13PngText(digits)   // 下方标注 13 位（含校验位）
ean8PngText(digits)    // 标注 8 位
upcaPngText(digits)    // 标注 12 位
itf14PngText(digits)   // 标注 14 位
```

## 尺寸参数与默认值（核实自源码常量）

| 参数 | 含义 | 一维码默认 | QR 默认 |
|---|---|---|---|
| `moduleWidth` / `moduleSize` | 单模块像素宽 | `2` | `4` |
| `heightPx` | 条码高度像素 | `60` | —（QR 是正方形，由模块数决定） |
| `quietModules` | 静区（留白）模块数 | `10`（**EAN/UPC 为 `11`**） | `4` |

> 静区是扫码成功的关键——**不要为了省空间把 `quietModules` 调到 0**。EAN/UPC 规范要求更宽的静区，officia 默认已按 11 处理。

## QR 码：纠错级别

```java
import plus.ruoyi.officia.barcode.QrEcc;

byte[] qr = OfficiaBarCode.qrPng("内容", QrEcc.H, 6, 4);
BufferedImage img = OfficiaBarCode.qrImage("内容", QrEcc.M, 4, 4);
```

| 级别 | 纠错能力 | 说明 |
|---|---|---|
| `QrEcc.L` | 约 7% | 容量最大，环境干净时用 |
| `QrEcc.M` | 约 15% | **默认** |
| `QrEcc.Q` | 约 25% | 印刷品 / 可能磨损 |
| `QrEcc.H` | 约 30% | 中心要盖 Logo、易脏易损场景 |

> 权衡：**容错越高，可容纳的数据越少**（ISO/IEC 18004）。内容长 + 高纠错 会让码变得很密，需同时调大 `moduleSize`。

## 常见场景配方

```java
// 1) 网页展示的二维码（清晰、够小）
byte[] web = OfficiaBarCode.qrPng("https://example.com/p/123");

// 2) 印刷品二维码（大模块 + 高纠错，抗磨损）
byte[] print = OfficiaBarCode.qrPng("https://example.com/p/123", QrEcc.Q, 8, 4);

// 3) 中间要盖 Logo 的二维码（必须 H 级）
byte[] withLogoBase = OfficiaBarCode.qrPng(url, QrEcc.H, 8, 4);
//   盖 Logo 用 OfficiaImaging.imageWatermark(...)，见 officia-imaging

// 4) 商品条码（传 12 位，自动补校验位，下方带数字）
byte[] ean = OfficiaBarCode.ean13PngText("690123456789");

// 5) 物流箱码
byte[] itf = OfficiaBarCode.itf14PngText("6901234567890");

// 6) 内部资产编号（Code128 通吃 ASCII）
byte[] asset = OfficiaBarCode.code128PngText("ASSET-2026-00123");
```

## 拿到 `BufferedImage` 做二次加工

需要拼版、加边框、叠 Logo 时用 `*Image` 方法拿 `BufferedImage`：

```java
BufferedImage qr = OfficiaBarCode.qrImage("内容", QrEcc.H, 8, 4);
// 用 Graphics2D 自行绘制，或写出后交给 OfficiaImaging 处理
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| EAN-13 抛异常 | 传了 13 位但校验位不对 | 改传 12 位让它自动补 |
| 二维码扫不出 | 模块太小 / 静区太窄 / 内容太长 + 纠错太高 | 调大 `moduleSize`，`quietModules` ≥ 4，缩短内容或降纠错 |
| Code128 抛异常 | 含非 ASCII 32..126 字符（如中文） | 中文用 **QR**，一维码不支持 |
| 条码打印出来扫不动 | 打印分辨率下模块被压得太细 | 增大 `moduleWidth`；印刷建议 ≥ 4 |
| 图片背景透明有问题 | 输出为 PNG | 需要白底可用 `OfficiaImaging` 转 JPEG（自动铺白底） |

## 完整示例：批量出商品码

```java
import plus.ruoyi.officia.barcode.OfficiaBarCode;
import java.nio.file.*;
import java.util.List;

public class BatchBarcode {
    public static void main(String[] args) throws Exception {
        List<String> skus = List.of("690123456789", "690123456790", "690123456791");
        for (String sku : skus) {
            Files.write(Path.of("ean-" + sku + ".png"),
                        OfficiaBarCode.ean13PngText(sku));   // 12 位自动补校验位 + 下方标注
        }
        System.out.println("已生成 " + skus.size() + " 张商品码");
    }
}
```

## 在测试台里实测

面板 **「BarCode · 条码」**：Code128/39/93、EAN-13/8、UPC-A、ITF-14、QR（4 档纠错），还有**全码制一键预览**。
端点 `/api/barcode`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 二维码上盖 Logo / 加边框 | `officia-imaging` |
| 把码图放进 PDF | `officia-imaging`（图片→PDF）或 `officia-pdf` |
| 放进 Word 模板 | `officia-template`（`TemplateImage`） |
