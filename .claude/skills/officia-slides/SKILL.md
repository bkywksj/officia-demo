---
name: officia-slides
description: |
  用 OfficiaSlides 把 PPTX 转成 PDF：内容提取式与版式保真式两种模式的差异、如何选、
  以及各自的适用场景与局限。

  触发场景：
  - PPT 转 PDF、pptx 转 PDF
  - 转出来的版式跑掉了 / 文字位置不对
  - 不知道 toPdf 和 toPdfLayoutAware 选哪个
  - 要幻灯片一张一页

  触发词：PPT、pptx、幻灯片、演示文稿、转PDF、版式、保真、layoutAware、母版、占位符
disable-model-invocation: false
allowed-tools: ["Read", "Write", "Edit", "Bash", "Grep"]
---

# PPTX → PDF（`OfficiaSlides`）

## 概述

`OfficiaSlides` 提供**两种转换模式**，这是它与其它门面最大的不同——选错模式是最常见的困惑来源。

## 两种模式对照

| | `toPdf`（内容提取式） | `toPdfLayoutAware`（版式保真式） |
|---|---|---|
| 做法 | 各幻灯片**文本框内容归一为 IR**，复用 engine 流式排版 + render-pdf | 解析形状**绝对定位几何**（`a:xfrm`）与占位符 layout/master 继承，文本按形状坐标绝对摆放 |
| 分页 | 幻灯片间分页 | **每张幻灯片一页** |
| 文字完整性 | ✅ 齐全 | ✅ 齐全 |
| 版式还原 | ❌ 无绝对定位 | ✅ 贴近原稿 |
| 中文 | 走 render-pdf 的 CID 子集嵌入 | 同左 |
| 适合 | 提取内容 / 存档 / 全文检索 | 给人看的演示稿归档 |

```java
import plus.ruoyi.officia.slides.OfficiaSlides;

byte[] pdf1 = OfficiaSlides.toPdf(pptxBytes);             // 内容提取式
byte[] pdf2 = OfficiaSlides.toPdfLayoutAware(pptxBytes);  // 版式保真式（推荐给人看的场景）
```

> **一句话选型**：要"看起来像原来的 PPT" → `toPdfLayoutAware`；只要"文字都在、能搜到" → `toPdf`。

## 全部方法（核实自 `OfficiaSlides` 源码）

```java
// 内容提取式
byte[] toPdf(byte[] pptx)
byte[] toPdf(byte[] pptx, ConvertOptions options)
byte[] toPdf(InputStream in)          // 读取后不主动关闭调用方的流
byte[] toPdf(File file)
ConvertResult convert(byte[] pptx, ConvertOptions options)   // 富结果：页数 + 耗时

// 版式保真式
byte[] toPdfLayoutAware(byte[] pptx)
byte[] toPdfLayoutAware(byte[] pptx, ConvertOptions options)
```

> 注意：**版式保真式没有 `InputStream` / `File` 重载**，也没有 `convert` 富结果版。需要时自己先读成 `byte[]`：
> ```java
> byte[] pdf = OfficiaSlides.toPdfLayoutAware(Files.readAllBytes(Path.of("in.pptx")));
> ```

## 版式保真式的内部流程（理解它的能力边界）

```
ooxml 解包
  → SlidesLayoutParser 解析形状几何与占位符继承 → PresentationModel
  → SlidesPdfRenderer 按绝对坐标渲染（不经 engine 流式排版）
```

含义：

- **占位符继承**（layout / master）已处理——母版上的标题位、页码位会被正确继承
- 走的是**绝对坐标渲染**，不做流式重排——所以位置准，但也不会自动避让/回流
- 中文经 render-pdf 的 CID 子集嵌入

## `ConvertOptions`

与 Words / Cells 通用（纸张 / 字体目录 / 嵌入字体 / 图像降采样 / 超时），见 `officia-words`。中文场景务必配 `fontDirectory`。

```java
ConvertOptions opts = ConvertOptions.defaults().setFontDirectory("/usr/share/fonts");
byte[] pdf = OfficiaSlides.toPdfLayoutAware(pptx, opts);
```

**设计型 PPT 尤其注意 `maxImageDpi`（默认 150）**：模板素材的图片像素数常远超它在页面上占的面积
（实测一张 8684×8684 的底图只画到 724pt 宽 = 863 dpi）。按原始像素嵌入会让产物大得离谱——
实测某 143 页模板 222 MB，开默认降采样后 105 MB、耗时也从 140 s 降到 62 s，肉眼看不出差别。
要原始像素时 `setMaxImageDpi(0)`。

## 排查表

| 现象 | 原因 | 处置 |
|---|---|---|
| 文字都在但位置全乱 / 挤成一列 | 用了内容提取式 `toPdf` | 改用 `toPdfLayoutAware` |
| 页数与幻灯片数不一致 | 内容提取式按流式排版分页 | 要"一张一页"用 `toPdfLayoutAware` |
| 某块内容整个空白 | 该元素类型不在支持范围（见下表） | 先用测试台实测确认是哪类元素 |
| 母版上的元素丢了 | — | 用 `toPdfLayoutAware`（它处理 layout/master 继承） |
| 中文方块 | 字体 | `officia-chinese-font` |
| 有水印 | 未授权 + 门控开 | `officia-license` |

### 版式保真式（`toPdfLayoutAware`）画得出什么

以真实模板逐页比对 PowerPoint 导出的 PDF 得出（上游 `docs/fidelity-benchmark/small-modules-review.md`）：

| 画得出 | 画不出（整块空白或退化） |
|---|---|
| 文本（含母版/版式继承、主题色与主题字体） | **SmartArt**（`dgm`）|
| 图片（PNG/JPEG/GIF/BMP）| **嵌入视频 / 音频 / 动画** |
| 形状与预设几何、自定义几何、渐变、阴影、透明度 | **数学公式**（OMML）|
| 表格 | 文字的发光/描边等文本效果 |
| 图表：折线 / 柱状 / 饼 / 圆环（含 3D 变体，按平面画）| 面积图**按折线**近似（不填充）|
| 弧形艺术字（`prstTxWarp`）| 竖排书写方向（`vert="eaVert"`）|

> ⚠️ 上表是**实测结论**，不是承诺清单。PPT 元素类型极多，遇到没列到的类型仍以**测试台上传真实文件实测**为准；
> 要看细节就读 `../officia/officia-slides/src/main/java/plus/ruoyi/officia/slides/` 下
> `SlidesLayoutParser` / `SlidesPdfRenderer` 的实现。

## 完整示例：两种模式并排输出对比

```java
import plus.ruoyi.officia.slides.OfficiaSlides;
import java.nio.file.*;

public class PptxCompare {
    public static void main(String[] args) throws Exception {
        byte[] pptx = Files.readAllBytes(Path.of("deck.pptx"));
        Files.write(Path.of("deck-content.pdf"), OfficiaSlides.toPdf(pptx));
        Files.write(Path.of("deck-layout.pdf"),  OfficiaSlides.toPdfLayoutAware(pptx));
        System.out.println("两种模式已输出，打开对比后再决定生产用哪个");
    }
}
```

## 在测试台里实测

面板 **「Slides · PPTX」**：上传 pptx，**标准 / 版式保真双模式**并排产出，直接看差异。
端点 `/api/slides/topdf`。

## 相关技能

| 接下来 | 用 |
|---|---|
| 合并多份 PDF / 加页码 | `officia-pdf` |
| 中文乱码 | `officia-chinese-font` |
| 做成接口 | `officia-spring-integration` |
