---
name: officia-imaging
description: |
  用 OfficiaImaging 处理图片：格式互转（PNG/JPEG/BMP/GIF）、缩放裁剪旋转翻转、
  灰度模糊锐化亮度对比度反色怀旧二值化色调分离、文字与图片水印、单张/多张图片转 PDF。

  触发场景：
  - 图片格式转换、压缩、缩放、裁剪、旋转
  - 加图片水印 / 文字水印
  - 图片转 PDF、多张图合成一份 PDF
  - 做灰度图、模糊、锐化、二值化（OCR 预处理）
  - JPEG 转出来有黑块

  触发词：图片、图像、缩放、裁剪、旋转、翻转、滤镜、灰度、模糊、锐化、亮度、对比度、反色、怀旧、二值化、水印、格式转换、图片转PDF、PNG、JPEG
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# 图像处理（`OfficiaImaging`）

## 概述

全部 `byte[]` 进 `byte[]` 出，基于 JDK `javax.imageio`，零第三方。

```java
import plus.ruoyi.officia.imaging.OfficiaImaging;

byte[] small = OfficiaImaging.resize(src, 800, 600);
byte[] jpg   = OfficiaImaging.convert(src, "jpg");
byte[] pdf   = OfficiaImaging.toPdf(src);
```

## 一、格式转换

```java
byte[] out = OfficiaImaging.convert(src, "png");   // 目标格式：png / jpg / jpeg / bmp / gif
                                                   // 不区分大小写，可带前导点（".png" 也行）
```

**支持的格式**：PNG / JPEG / BMP / GIF（`javax.imageio` 内置）。WebP / AVIF / TIFF **不在其中**。

> 💡 **透明通道处理**：目标格式不支持透明（JPEG / BMP）时，若源图含 alpha，officia **自动铺白底转 RGB**——不会出现黑块或转换失败。这是常见坑，officia 已内置处理。

## 二、几何变换

```java
byte[] r = OfficiaImaging.resize(src, width, height);
byte[] c = OfficiaImaging.crop(src, x, y, width, height);
byte[] t = OfficiaImaging.rotate(src, 90.0);          // 角度（double）
byte[] h = OfficiaImaging.flipHorizontal(src);
byte[] v = OfficiaImaging.flipVertical(src);
```

## 三、滤镜 / 调色

```java
byte[] g = OfficiaImaging.grayscale(src);              // 灰度
byte[] b = OfficiaImaging.blur(src, 3);                // 模糊（radius）
byte[] s = OfficiaImaging.sharpen(src);                // 锐化
byte[] br= OfficiaImaging.brightness(src, 30);         // 亮度（delta，可负）
byte[] co= OfficiaImaging.contrast(src, 1.2f);         // 对比度（factor，1.0 = 不变）
byte[] iv= OfficiaImaging.invert(src);                 // 反色
byte[] se= OfficiaImaging.sepia(src);                  // 怀旧
byte[] bi= OfficiaImaging.binarize(src, 128);          // 二值化（threshold 0-255）
byte[] po= OfficiaImaging.posterize(src, 4);           // 色调分离（levels）
```

## 四、水印

```java
// 文字水印：x, y 位置，字号，颜色 RGB（如 0xFF0000 红），不透明度 0.0~1.0
byte[] w1 = OfficiaImaging.textWatermark(src, "内部资料", 20, 40, 24f, 0xFF0000, 0.35f);

// 图片水印（盖 Logo）：x, y 位置，不透明度
byte[] w2 = OfficiaImaging.imageWatermark(src, logoBytes, 20, 20, 0.8f);
```

## 五、图片 → PDF

```java
byte[] pdf  = OfficiaImaging.toPdf(imageBytes);              // 单张
byte[] pdfs = OfficiaImaging.toPdf(List.of(img1, img2, img3)); // 多张合成一份（每张一页）
```

典型用途：扫描件归档、拍照凭证合成 PDF、图片合同存档。

## 常见配方

```java
// 1) 上传图统一处理：限尺寸 + 转 JPEG（省体积）
byte[] normalized = OfficiaImaging.convert(OfficiaImaging.resize(upload, 1920, 1080), "jpg");

// 2) 二维码盖 Logo
byte[] qr = OfficiaBarCode.qrPng(url, QrEcc.H, 8, 4);         // 必须 H 级纠错
byte[] withLogo = OfficiaImaging.imageWatermark(qr, logo, 120, 120, 1.0f);

// 3) OCR 预处理（灰度 → 二值化）
byte[] forOcr = OfficiaImaging.binarize(OfficiaImaging.grayscale(scan), 140);

// 4) 多张扫描件合成一份归档 PDF
byte[] archive = OfficiaImaging.toPdf(List.of(p1, p2, p3));

// 5) 带版权水印的对外图
byte[] published = OfficiaImaging.textWatermark(src, "(C) 2026", 20, 30, 18f, 0xFFFFFF, 0.5f);
```

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 转 JPEG 后透明区变**黑块** | 一般库的通病 | officia **已自动铺白底**；仍有黑块请确认源图确实含 alpha 且格式被正确识别 |
| WebP / AVIF / TIFF 读不了 | `javax.imageio` 不支持 | 先用其它工具转成 PNG/JPEG 再进 officia |
| `resize` 后变形 | 传的是绝对宽高，不保持比例 | 自己按原图比例算目标尺寸 |
| 水印看不见 | `opacity` 太低 / 颜色与背景同色 / x,y 在画布外 | 调 `opacity`、换 `rgb`、确认坐标在图内 |
| `blur` 很慢 | radius 大 + 图大 | 先 `resize` 再滤镜 |
| 图片转 PDF 后很大 | 原图分辨率高 | 先 `resize` / 转 JPEG 再 `toPdf` |

## 完整示例：批量出缩略图 + 水印 + 归档 PDF

```java
import plus.ruoyi.officia.imaging.OfficiaImaging;
import java.nio.file.*;
import java.util.*;

public class ImagePipeline {
    public static void main(String[] args) throws Exception {
        List<byte[]> processed = new ArrayList<>();
        for (String f : List.of("1.png", "2.png", "3.png")) {
            byte[] src = Files.readAllBytes(Path.of(f));
            byte[] out = OfficiaImaging.textWatermark(
                             OfficiaImaging.resize(src, 1200, 900),
                             "样张", 20, 40, 28f, 0xFFFFFF, 0.4f);
            Files.write(Path.of("out-" + f.replace(".png", ".jpg")),
                        OfficiaImaging.convert(out, "jpg"));
            processed.add(out);
        }
        Files.write(Path.of("album.pdf"), OfficiaImaging.toPdf(processed));
    }
}
```

## 在测试台里实测

面板 **「Imaging · 图像」**：滤镜/变换全项 + 格式转换 + 图片→PDF，**处理前后并排对比**。
端点 `/api/imaging/op`、`/api/imaging/topdf`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 生成二维码再加工 | `officia-barcode` |
| 出的 PDF 要合并加密 | `officia-pdf` |
| 图片放进 Word 模板 | `officia-template`（`TemplateImage`） |
| 做上传处理接口 | `officia-spring-integration` |
