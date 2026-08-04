---
name: officia-slides
description: |
  用 OfficiaSlides 把 PPTX 转成 PDF：版式保真转换的做法、能力边界（画得出什么 / 画不出什么）、
  ConvertOptions 配置与常见问题排查。

  触发场景：
  - PPT 转 PDF、pptx 转 PDF
  - 转出来的版式跑掉了 / 文字位置不对
  - 要幻灯片一张一页
  - 设计型 PPT 转出来体积过大

  触发词：PPT、pptx、幻灯片、演示文稿、转PDF、版式、保真、母版、占位符
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# PPTX → PDF（`OfficiaSlides`）

## 概述

`OfficiaSlides.toPdf` 做**版式保真**转换：解析形状绝对定位几何（`a:xfrm`）与占位符 layout/master 继承，
按形状坐标绝对摆放，**每张幻灯片一页**。用法与 `OfficiaWords` / `OfficiaCells` 完全一致。

```java
import plus.ruoyi.officia.slides.OfficiaSlides;

byte[] pdf = OfficiaSlides.toPdf(pptxBytes);
```

## 全部方法（核实自 `OfficiaSlides` 源码）

```java
byte[] toPdf(byte[] pptx)
byte[] toPdf(byte[] pptx, ConvertOptions options)
byte[] toPdf(InputStream in)          // 读取后不主动关闭调用方的流
byte[] toPdf(File file)
ConvertResult convert(byte[] pptx, ConvertOptions options)   // 富结果：页数 + 耗时
```

页数即幻灯片数（每张一页），`convert(...).getPageCount()` 可直接拿到。

## 只要纯文本时

不需要另一条转换链——对产物再抽一次即可，而且拿到的文字比"只读文本框"更全
（保真链解析了 layout/master 占位符继承）：

```java
String text = OfficiaPdf.extractText(OfficiaSlides.toPdf(pptx));
```

## 内部流程（理解它的能力边界）

```
ooxml 解包
  → SlidesLayoutParser 解析形状几何与占位符继承 → PresentationModel
  → SlidesPdfRenderer 按绝对坐标渲染（不经 engine 流式排版）
```

含义：

- **占位符继承**（layout / master）已处理——母版上的标题位、页码位会被正确继承
- 走的是**绝对坐标渲染**，不做流式重排——所以位置准，但也不会自动避让/回流
- 中文经 render-pdf 的 CID 子集嵌入

> 幻灯片是「绝对定位画布」，形状位置由 `a:xfrm` 直接给定，没有 Word/Excel 那种文字流与分页语义。
> 因此 slides 是三条产品线里唯一不经 engine 排版、直接从自有模型渲染的一条。

## `ConvertOptions`

与 Words / Cells 通用（纸张 / 字体目录 / 嵌入字体 / 图像降采样 / 超时），见 `officia-words`。中文场景务必配 `fontDirectory`。

```java
ConvertOptions opts = ConvertOptions.defaults().setFontDirectory("/usr/share/fonts");
byte[] pdf = OfficiaSlides.toPdf(pptx, opts);
```

**设计型 PPT 尤其注意 `maxImageDpi`（默认 150）**：模板素材的图片像素数常远超它在页面上占的面积
（实测一张 8684×8684 的底图只画到 724pt 宽 = 863 dpi）。按原始像素嵌入会让产物大得离谱——
实测某 143 页模板 222 MB，开默认降采样后 105 MB、耗时也从 140 s 降到 62 s，肉眼看不出差别。
要原始像素时 `setMaxImageDpi(0)`。

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 某块内容整个空白 | 该元素类型不在支持范围（见下表） | 先用测试台实测确认是哪类元素 |
| 某张图偏暗 / 颜色不对 / 挡住内容 | SVG、WMF/EMF 元文件、WDP 等 JDK 解不开的格式会被跳过（少一张，不影响其余） | 用测试台实测；把该图另存为 PNG/JPEG 再放回 PPT |
| 产物体积过大 / 转换很慢 | 素材图按原始像素嵌入 | 保持默认 `maxImageDpi=150`，别设 0 |
| 中文方块 | 字体 | `officia-chinese-font` |
| 有水印 | 未授权 + 门控开 | `officia-license` |

### 画得出什么

以真实模板逐页比对 PowerPoint 导出的 PDF 得出（上游 `docs/fidelity-benchmark/small-modules-review.md`）：

| 画得出 | 画不出（整块空白或退化） |
|---|---|
| 文本（含母版/版式继承、主题色与主题字体） | **SmartArt**（`dgm`）|
| 图片（PNG/JPEG/GIF/BMP）+ 图片效果：源图裁切 `srcRect`、镜像 `flipH/flipV`、整体不透明度 `alphaModFix`、双色调重着色 `duotone` | **嵌入视频 / 音频 / 动画** |
| 形状与预设几何、自定义几何、渐变、阴影、透明度 | **数学公式**（OMML）|
| 表格 | 文字的发光/描边等文本效果 |
| 图表：折线 / 柱状 / 饼 / 圆环（含 3D 变体，按平面画）| 面积图**按折线**近似（不填充）|
| 弧形艺术字（`prstTxWarp`）| 竖排书写方向（`vert="eaVert"`）|

> ⚠️ 上表是**实测结论**，不是承诺清单。PPT 元素类型极多，遇到没列到的类型仍以**测试台上传真实文件实测**为准；
> 要看细节就读 `../officia/officia-slides/src/main/java/plus/ruoyi/officia/slides/` 下
> `SlidesLayoutParser` / `SlidesPdfRenderer` 的实现。

## 完整示例

```java
import plus.ruoyi.officia.slides.OfficiaSlides;
import plus.ruoyi.officia.engine.api.ConvertResult;
import plus.ruoyi.officia.engine.api.ConvertOptions;
import java.nio.file.*;

public class PptxToPdf {
    public static void main(String[] args) throws Exception {
        byte[] pptx = Files.readAllBytes(Path.of("deck.pptx"));
        ConvertResult r = OfficiaSlides.convert(pptx, ConvertOptions.defaults());
        Files.write(Path.of("deck.pdf"), r.getData());
        System.out.println(r.getPageCount() + " 页，耗时 " + r.getCostMillis() + " ms");
    }
}
```

## 在测试台里实测

面板 **「Slides · PPTX」**：上传 pptx 直接转换，看页数 / 耗时 / 体积并内嵌预览。
端点 `/api/slides/topdf`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 合并多份 PDF / 加页码 | `officia-pdf` |
| 中文乱码 | `officia-chinese-font` |
| 做成接口 | `officia-spring-integration` |
